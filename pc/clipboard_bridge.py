"""简传 Lite · 剪贴板双向同步桥

手机 ↔ 电脑 的剪贴板自动同步，两条通道并存：

  1) 蓝牙 BLE（主通道，没网也能用）
     手机当 GATT Server 一直广播，本机作为 Central 连上去：
       - 手机复制内容 → TX 特征值 notify 过来 → 写进本机剪贴板
       - 本机复制内容 → 写 RX 特征值 → 手机写进自己的剪贴板
     BLE 单包只有 MTU-3 字节，所以自定义了 6 字节头的分片协议。

  2) 局域网 HTTP（兜底，蓝牙坏了/台式机没蓝牙时用）
       - 手机 → 电脑：手机 POST http://本机:8787/api/clip
       - 电脑 → 手机：内容排队，手机网页轮询 /api/messages 时取走，
         页面再调 AndroidBridge.setClipboard() 写进手机剪贴板

回环抑制：无论从哪条通道收到内容，写进本机剪贴板后都会登记指纹，
本机的剪贴板监听线程读到相同指纹就跳过，不会又发回去形成死循环。
"""

from __future__ import annotations

import asyncio
import threading
import time
from collections import deque

# ---------------------------------------------------------------- 协议常量
# 必须与安卓端 ClipBridge.kt / BleClipServer 保持一致
SERVICE_UUID = "0000a100-0000-1000-8000-00805f9b34fb"
RX_UUID = "0000a101-0000-1000-8000-00805f9b34fb"   # 电脑 → 手机（write）
TX_UUID = "0000a102-0000-1000-8000-00805f9b34fb"   # 手机 → 电脑（notify）
INFO_UUID = "0000a103-0000-1000-8000-00805f9b34fb"

HEADER = 6
MAX_MESSAGE_BYTES = 48 * 1024
POLL_INTERVAL = 0.5        # 本机剪贴板轮询间隔（秒）
RECONNECT_DELAY = 2.0
HISTORY_LIMIT = 12

# ---------------------------------------------------------------- 可选依赖
try:
    import pyperclip
except Exception:                                    # 没装 pyperclip 就退化成"只收不发"
    pyperclip = None

try:
    from bleak import BleakClient, BleakScanner
    BLE_AVAILABLE = True
except Exception:
    BleakClient = None                               # type: ignore
    BleakScanner = None                              # type: ignore
    BLE_AVAILABLE = False


def _clip_get() -> str:
    if pyperclip is None:
        return ""
    try:
        return pyperclip.paste() or ""
    except Exception:
        return ""


def _clip_set(text: str) -> bool:
    if pyperclip is None:
        return False
    try:
        pyperclip.copy(text)
        return True
    except Exception:
        return False


def _preview(text: str, n: int = 60) -> str:
    flat = " ".join((text or "").split())
    return flat[:n] + ("…" if len(flat) > n else "")


class _Reassembler:
    """BLE 分片重组。帧头：[类型1][msgId1][total2][index2] + 负载"""

    def __init__(self) -> None:
        self._buf: dict[int, list[bytes | None]] = {}
        self._ts: dict[int, float] = {}
        self._lock = threading.Lock()

    def feed(self, frame: bytes) -> str | None:
        if len(frame) < HEADER or frame[0] != 1:
            return None
        msg_id = frame[1]
        total = (frame[2] << 8) | frame[3]
        index = (frame[4] << 8) | frame[5]
        if total <= 0 or index >= total:
            return None

        with self._lock:
            now = time.time()
            stale = [k for k, t in self._ts.items() if now - t > 30]
            for k in stale:
                self._buf.pop(k, None)
                self._ts.pop(k, None)

            parts = self._buf.get(msg_id)
            if parts is None or len(parts) != total:
                parts = [None] * total
                self._buf[msg_id] = parts
            parts[index] = frame[HEADER:]
            self._ts[msg_id] = now

            if any(p is None for p in parts):
                return None
            self._buf.pop(msg_id, None)
            self._ts.pop(msg_id, None)
            raw = b"".join(p for p in parts if p is not None)

        return raw.decode("utf-8", "replace")


class ClipboardBridge:
    """电脑侧剪贴板同步总控。线程安全，server.py 可随时调。"""

    def __init__(self, log=None) -> None:
        self._log_cb = log
        self._lock = threading.RLock()

        self._stop = threading.Event()
        self._enabled = False
        self._clip_thread: threading.Thread | None = None
        self._ble_thread: threading.Thread | None = None
        # asyncio 只在蓝牙线程里用，这里不 import，避免没有蓝牙的机器也要加载事件循环
        self._loop = None

        # 本机剪贴板状态
        self._last_local = ""
        self._sent_hashes: deque[int] = deque(maxlen=16)

        # 电脑 → 手机的 HTTP 待发队列（BLE 不可用时）
        self._outbox: deque[dict] = deque(maxlen=20)
        self._outbox_seq = 0
        self._outbox_lock = threading.Lock()

        # BLE 状态
        self._ble_connected = False
        self._ble_peer = ""
        self._ble_error = ""
        self._ble_send = None          # 由 BLE 线程注入的发送函数
        self._reasm = _Reassembler()

        # 展示用
        self._history: deque[dict] = deque(maxlen=HISTORY_LIMIT)
        self._last_event = ""

    # ------------------------------------------------------------ 对外接口
    def log(self, msg: str) -> None:
        if self._log_cb:
            try:
                self._log_cb(msg)
            except Exception:
                pass

    def start(self) -> dict:
        with self._lock:
            if self._enabled:
                return self.status()
            self._enabled = True
            self._stop.clear()

        self._clip_thread = threading.Thread(
            target=self._watch_clipboard, name="clip-watch", daemon=True
        )
        self._clip_thread.start()

        if BLE_AVAILABLE:
            self._ble_thread = threading.Thread(
                target=self._ble_main, name="clip-ble", daemon=True
            )
            self._ble_thread.start()
        else:
            self._ble_error = "未安装 bleak，蓝牙通道不可用（仅局域网）"
            self.log(self._ble_error)

        self._last_event = "剪贴板同步已开启"
        self.log("剪贴板同步已开启")
        return self.status()

    def stop(self) -> dict:
        with self._lock:
            if not self._enabled:
                return self.status()
            self._enabled = False
            self._stop.set()
            self._ble_connected = False
            self._ble_send = None
        self._last_event = "剪贴板同步已关闭"
        self.log("剪贴板同步已关闭")
        return self.status()

    def status(self) -> dict:
        with self._lock:
            return {
                "enabled": self._enabled,
                "ble": {
                    "available": BLE_AVAILABLE,
                    "connected": self._ble_connected,
                    "peer": self._ble_peer,
                    "error": self._ble_error,
                },
                "clipboard": pyperclip is not None,
                "queued": len(self._outbox),
                "last": self._last_event,
                "history": list(self._history),
            }

    # ------------------------------------------------------------ 本机剪贴板监听
    def _is_echo(self, text: str) -> bool:
        h = hash(text)
        with self._lock:
            return h in self._sent_hashes

    def _mark(self, text: str) -> None:
        with self._lock:
            self._sent_hashes.append(hash(text))

    def _watch_clipboard(self) -> None:
        # 初值取当前剪贴板，避免一启动就把老内容推到手机
        try:
            self._last_local = _clip_get()
        except Exception:
            self._last_local = ""

        while not self._stop.is_set():
            try:
                text = _clip_get()
            except Exception:
                text = ""
            if text and text != self._last_local:
                self._last_local = text
                if not self._is_echo(text):
                    self.send(text, origin="本机")
            self._stop.wait(POLL_INTERVAL)

    # ------------------------------------------------------------ 发送
    def send(self, text: str, origin: str = "本机") -> bool:
        """把文本发给手机。优先蓝牙，蓝牙没连上就排进 HTTP 队列。"""
        if not text:
            return False
        self._mark(text)

        by_ble = False
        sender = self._ble_send
        if sender and self._ble_connected:
            try:
                by_ble = bool(sender(text))
            except Exception:
                by_ble = False

        if not by_ble:
            self._enqueue_outbox(text)

        self._record(text, origin, "out")
        # 返回「已交付」而不是「走了蓝牙」：进队列也算交付，
        # 手机下次轮询就会取走，UI 说"没发出去"反而会误导用户。
        return True

    def _enqueue_outbox(self, text: str) -> None:
        with self._outbox_lock:
            self._outbox_seq += 1
            self._outbox.append({"id": self._outbox_seq, "text": text, "ts": time.time()})

    def force_sync(self) -> bool:
        """立刻把本机当前剪贴板推给手机（网页上的「推电脑剪贴板」按钮）"""
        text = _clip_get()
        if not text:
            return False
        return self.send(text, origin="手动")

    def drain_outbox(self) -> list[dict]:
        """手机网页轮询时取走待发内容（HTTP 通道：电脑 → 手机）"""
        with self._outbox_lock:
            if not self._outbox:
                return []
            items = list(self._outbox)
            self._outbox.clear()
            return items

    # ------------------------------------------------------------ 接收
    def on_remote_text(self, text: str, via: str = "蓝牙") -> None:
        """手机发来的剪贴板内容（HTTP 通道由 server.py 调这里）"""
        if not text:
            return
        self._mark(text)
        self._last_local = text
        ok = _clip_set(text)
        self._record(text, via, "in", ok)
        self.log(f"从手机收到 {len(text)} 字（{via}）{'，已写入剪贴板' if ok else '，写剪贴板失败'}")

    def _record(self, text: str, origin: str, direction: str, extra_ok: bool = True) -> None:
        direction_cn = "收到" if direction == "in" else "发出"
        self._last_event = f"{direction_cn} {len(text)} 字 · {origin}"
        with self._lock:
            self._history.appendleft({
                "ts": time.time(),
                "dir": direction,
                "origin": origin,
                "chars": len(text),
                "preview": _preview(text),
                "ok": extra_ok,
            })

    # ------------------------------------------------------------ BLE 主循环
    def _ble_main(self) -> None:
        self._loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self._loop)
        try:
            self._loop.run_until_complete(self._ble_loop())
        except Exception as e:                       # 整个蓝牙线程挂了也别影响主服务
            self._ble_error = f"蓝牙线程异常：{e}"
            self.log(self._ble_error)
        finally:
            try:
                self._loop.close()
            except Exception:
                pass

    async def _ble_loop(self) -> None:
        while not self._stop.is_set():
            try:
                device = await self._scan_once()
                if device is None:
                    await asyncio.sleep(RECONNECT_DELAY)
                    continue

                name = getattr(device, "name", "") or "手机"
                self._ble_peer = name
                self._ble_error = ""
                self.log(f"蓝牙正在连接「{name}」…")

                client = BleakClient(device, timeout=20.0)
                await client.connect()
                if not client.is_connected:
                    raise RuntimeError("连接未建立")

                self._ble_connected = True
                self._ble_error = ""
                self.log(f"蓝牙已连接「{name}」")
                self._last_event = f"蓝牙已连「{name}」"

                # 分片大小按协商后的 MTU 算
                try:
                    mtu = int(getattr(client, "mtu_size", 23) or 23)
                except Exception:
                    mtu = 23
                chunk = max(8, min(mtu - 3 - HEADER, 480))

                def _sender(text: str, _client=client, _chunk=chunk) -> bool:
                    return self._ble_write(_client, text, _chunk)

                self._ble_send = _sender

                await client.start_notify(TX_UUID, self._on_ble_notify)

                # 连上后把本机当前剪贴板推一次，手机端立刻对齐
                warm = _clip_get()
                if warm:
                    try:
                        _sender(warm)
                    except Exception:
                        pass

                while client.is_connected and not self._stop.is_set():
                    await asyncio.sleep(1.0)

                try:
                    await client.stop_notify(TX_UUID)
                except Exception:
                    pass
                try:
                    await client.disconnect()
                except Exception:
                    pass

            except Exception as e:
                self._ble_error = f"{type(e).__name__}: {e}"
            finally:
                self._ble_connected = False
                self._ble_send = None

            if not self._stop.is_set():
                await asyncio.sleep(RECONNECT_DELAY)

    async def _scan_once(self):
        """扫一台广播了简传 Lite 服务 UUID 的手机，最多 12 秒"""
        found = []

        def _on_detect(device, adv):
            if found:
                return
            uuids = [str(u).lower() for u in (getattr(adv, "service_uuids", None) or [])]
            if SERVICE_UUID in uuids:
                found.append(device)
                return
            # 有些机型广播包会裁掉 128 位 UUID，退一步按名字认
            name = getattr(adv, "local_name", None) or getattr(device, "name", None) or ""
            if "简传" in name:
                found.append(device)

        scanner = BleakScanner(detection_callback=_on_detect)
        await scanner.start()
        try:
            deadline = time.time() + 12
            while time.time() < deadline and not found and not self._stop.is_set():
                await asyncio.sleep(0.25)
        finally:
            try:
                await scanner.stop()
            except Exception:
                pass
        return found[0] if found else None

    def _on_ble_notify(self, _sender, data: bytearray) -> None:
        text = self._reasm.feed(bytes(data))
        if text:
            self.on_remote_text(text, via="蓝牙")

    def _ble_write(self, client, text: str, chunk: int) -> bool:
        """把文本分片写进 RX 特征值。必须在本线程的 event loop 里同步执行。"""
        loop = self._loop
        if loop is None:
            return False
        data = text.encode("utf-8")[:MAX_MESSAGE_BYTES]
        if not data:
            return False
        total = max(1, (len(data) + chunk - 1) // chunk)
        msg_id = int(time.time() * 1000) & 0xFF

        async def _do() -> bool:
            for i in range(total):
                part = data[i * chunk:(i + 1) * chunk]
                frame = bytes([
                    1,
                    msg_id,
                    (total >> 8) & 0xFF, total & 0xFF,
                    (i >> 8) & 0xFF, i & 0xFF,
                ]) + part
                await client.write_gatt_char(RX_UUID, frame, response=True)
            return True

        # 从剪贴板线程或者事件循环内部调用都能跑
        try:
            running = asyncio.get_running_loop()
        except RuntimeError:
            running = None
        if running is not None:
            running.create_task(_do())
            return True
        try:
            fut = asyncio.run_coroutine_threadsafe(_do(), loop)
            return bool(fut.result(timeout=15))
        except Exception:
            return False


_bridge: ClipboardBridge | None = None
_bridge_lock = threading.Lock()


def get_bridge(log=None) -> ClipboardBridge:
    global _bridge
    with _bridge_lock:
        if _bridge is None:
            _bridge = ClipboardBridge(log=log)
        return _bridge
