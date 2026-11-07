package com.jianchuan.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID

/**
 * 手机剪贴板桥：读写系统剪贴板，并做「回环抑制」。
 *
 * 为什么需要回环抑制：电脑 → 手机写入剪贴板后，手机侧监听线程会立刻发现「剪贴板变了」，
 * 于是又发回电脑，电脑再写回手机……无限循环。做法是写剪贴板时记下内容指纹，
 * 监听线程读到相同指纹就认为这是自己刚写进去的，直接跳过。
 */
class ClipBridge(private val ctx: Context) {

    private val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** 最近一次由「远端」写入的内容指纹，监听线程据此判断回声 */
    @Volatile
    private var echoHash: Int = 0

    @Volatile
    var lastSeenHash: Int = 0
        private set

    /**
     * 读当前剪贴板文本。
     * Android 10+ 只有在应用位于前台（或为默认输入法）时才能读，否则返回 null。
     */
    @SuppressLint("MissingPermission")
    fun readText(): String? {
        return try {
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            val item = clip.getItemAt(0)
            item.coerceToText(ctx)?.toString()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /** 写剪贴板，并登记指纹，防止被监听线程当成用户新复制的内容再发出去 */
    fun writeText(text: String) {
        if (text.isBlank()) return
        try {
            echoHash = text.hashCode()
            lastSeenHash = echoHash
            cm.setPrimaryClip(ClipData.newPlainText("简传Lite", text))
        } catch (_: Exception) {
        }
    }

    /** 读到的内容是否是我们刚写进去的回声 */
    fun isEcho(text: String): Boolean = text.hashCode() == echoHash
}

/**
 * 手机作为 BLE 外设（GATT Server）对外广播，电脑当 Central 连上来。
 *
 * 为什么手机当 Server 而不是 Client：Android 当 Central 需要扫描并维持连接，国产 ROM 后台
 * 会杀；而 GATT Server + 广播在 App 前台时非常稳定，电脑侧 bleak 做 Central 也更成熟。
 *
 * 特征值布局（16 位 UUID，短 UUID 广播只占 2 字节，省空间）：
 *   a100 服务
 *   a101 RX  —— 电脑 write 进来，手机收文本
 *   a102 TX  —— 手机 notify 出去，电脑订阅
 *   a103 INFO—— 只读，返回设备名/版本，纯粹给人看的
 *
 * 分片协议（BLE 单包上限 = MTU-3，默认 MTU 23 时只有 20 字节，长文本必须分片）：
 *   byte0      帧类型，固定 1
 *   byte1      msgId（0..255，滚动）
 *   byte2..3   分片总数 total（大端）
 *   byte4..5   当前分片序号 index（大端）
 *   byte6..    负载（UTF-8 文本片段）
 */
class BleClipServer(private val ctx: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000a100-0000-1000-8000-00805f9b34fb")
        val RX_UUID: UUID = UUID.fromString("0000a101-0000-1000-8000-00805f9b34fb")
        val TX_UUID: UUID = UUID.fromString("0000a102-0000-1000-8000-00805f9b34fb")
        val INFO_UUID: UUID = UUID.fromString("0000a103-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** 单条消息上限，超了就截断（BLE 传大文本意义不大，而且分片太多容易丢） */
        const val MAX_MESSAGE_BYTES = 48 * 1024
        private const val HEADER = 6
    }

    /** 收到电脑发来的文本 */
    @Volatile
    var onText: ((String) -> Unit)? = null

    /** 连接状态变化 */
    @Volatile
    var onState: ((connected: Boolean, peerName: String?) -> Unit)? = null

    private val appCtx = ctx.applicationContext
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var infoChar: BluetoothGattCharacteristic? = null

    @Volatile
    private var connectedDevice: BluetoothDevice? = null

    @Volatile
    private var peerName: String = ""

    /** 协商后的 MTU，默认 23（未协商时） */
    @Volatile
    private var mtu: Int = 23

    @Volatile
    private var notifying: Boolean = false

    @Volatile
    var running: Boolean = false
        private set

    private var msgSeq = 0
    private val sendLock = Any()

    // 接收重组：msgId -> 分片数组
    private val rxBuf = HashMap<Int, Array<ByteArray?>>()
    private val rxMeta = HashMap<Int, Int>()
    private val rxTs = HashMap<Int, Long>()

    private val adapter: BluetoothAdapter?
        get() = (appCtx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun isSupported(): Boolean = adapter?.isEnabled == true &&
            appCtx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE)

    fun deviceName(): String {
        val n = try { adapter?.name } catch (_: Exception) { null }
        return n ?: "简传Lite"
    }

    fun peerDisplayName(): String = peerName

    /** 启动 GATT Server 并开始广播。返回 false 表示蓝牙不可用/权限缺失。 */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val ad = adapter ?: return false
        if (!ad.isEnabled) return false

        return try {
            val mgr = appCtx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val server = mgr.openGattServer(appCtx, callback) ?: return false

            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

            rxChar = BluetoothGattCharacteristic(
                RX_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            txChar = BluetoothGattCharacteristic(
                TX_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            ).apply {
                addDescriptor(
                    BluetoothGattDescriptor(
                        CCCD_UUID,
                        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                    )
                )
            }
            infoChar = BluetoothGattCharacteristic(
                INFO_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            ).apply {
                value = "${Build.MODEL}|简传Lite/1.9".toByteArray(Charsets.UTF_8)
            }

            service.addCharacteristic(rxChar)
            service.addCharacteristic(txChar)
            service.addCharacteristic(infoChar)
            server.addService(service)

            gattServer = server
            running = true
            startAdvertise(ad)
            true
        } catch (_: Exception) {
            stop()
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertise(ad: BluetoothAdapter) {
        if (Build.VERSION.SDK_INT < 21) return
        val adv = ad.bluetoothLeAdvertiser ?: return
        advertiser = adv

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        // 广播包里只放服务 UUID（短 UUID 占 2 字节），设备名放 scan response 里省主包空间
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val scanResp = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        try {
            adv.startAdvertising(settings, data, scanResp, advCallback)
        } catch (_: Exception) {
        }
    }

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {}
        override fun onStartFailure(errorCode: Int) {}
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        running = false
        try { advertiser?.stopAdvertising(advCallback) } catch (_: Exception) {}
        advertiser = null
        try { gattServer?.clearServices() } catch (_: Exception) {}
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        connectedDevice = null
        notifying = false
        synchronized(rxBuf) { rxBuf.clear(); rxMeta.clear(); rxTs.clear() }
    }

    @SuppressLint("MissingPermission")
    private fun restartAdvertise() {
        val ad = adapter ?: return
        try { advertiser?.stopAdvertising(advCallback) } catch (_: Exception) {}
        startAdvertise(ad)
    }

    // ------------------------------------------------------------ GATT 回调
    private val callback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device
                peerName = try { device.name ?: "电脑" } catch (_: Exception) { "电脑" }
                mtu = 23
                notifying = false
                onState?.invoke(true, peerName)
            } else {
                connectedDevice = null
                notifying = false
                synchronized(rxBuf) { rxBuf.clear(); rxMeta.clear(); rxTs.clear() }
                onState?.invoke(false, null)
                // 断开后必须重新广播，否则电脑再也扫不到
                restartAdvertise()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            // Windows 连上后一般会协商到 500+，分片大小随之变大，长文本才传得快
            this@BleClipServer.mtu = mtu.coerceAtLeast(23)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                notifying = value.isNotEmpty() && value[0] == 1.toByte()
            }
            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                } catch (_: Exception) {
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (characteristic.uuid == RX_UUID && value != null && value.isNotEmpty()) {
                handleFrame(value)
            }
            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                } catch (_: Exception) {
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val payload = when (characteristic.uuid) {
                INFO_UUID -> "${Build.MODEL}|简传Lite/1.9".toByteArray(Charsets.UTF_8)
                else -> ByteArray(0)
            }
            try {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, payload)
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------ 收：分片重组
    private fun handleFrame(frame: ByteArray) {
        if (frame.size < HEADER || frame[0].toInt() != 1) return
        val msgId = frame[1].toInt() and 0xFF
        val total = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
        val index = ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)
        if (total <= 0 || index >= total) return

        val payload = frame.copyOfRange(HEADER, frame.size)
        var done: ByteArray? = null

        synchronized(rxBuf) {
            // 清理 30 秒还没收齐的残包，防止内存泄漏
            val now = System.currentTimeMillis()
            val stale = rxTs.filterValues { now - it > 30_000 }.keys.toList()
            for (k in stale) {
                rxBuf.remove(k); rxMeta.remove(k); rxTs.remove(k)
            }

            var parts = rxBuf[msgId]
            if (parts == null || parts.size != total) {
                parts = arrayOfNulls(total)
                rxBuf[msgId] = parts
                rxMeta[msgId] = total
            }
            parts[index] = payload
            rxTs[msgId] = now

            if (parts.all { it != null }) {
                var size = 0
                for (p in parts) size += p!!.size
                val out = ByteArray(size)
                var off = 0
                for (p in parts) {
                    System.arraycopy(p!!, 0, out, off, p.size)
                    off += p.size
                }
                rxBuf.remove(msgId); rxMeta.remove(msgId); rxTs.remove(msgId)
                done = out
            }
        }

        done?.let { bytes ->
            val text = try { String(bytes, Charsets.UTF_8) } catch (_: Exception) { null }
            if (!text.isNullOrEmpty()) onText?.invoke(text)
        }
    }

    // ------------------------------------------------------------ 发：分片 notify
    /**
     * 把文本通过 notify 发给电脑。返回 false 表示当前没连接/没订阅。
     * 调用方（剪贴板监听线程）可能在任意线程，这里用 sendLock 串行化。
     */
    @SuppressLint("MissingPermission")
    fun sendText(text: String): Boolean {
        val device = connectedDevice ?: return false
        val char = txChar ?: return false
        val server = gattServer ?: return false
        if (!notifying) return false

        var bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_MESSAGE_BYTES) {
            bytes = String(bytes, 0, MAX_MESSAGE_BYTES, Charsets.UTF_8).toByteArray(Charsets.UTF_8)
        }

        val chunk = (mtu - 3 - HEADER).coerceIn(8, 480)
        val total = ((bytes.size + chunk - 1) / chunk).coerceAtLeast(1)

        synchronized(sendLock) {
            val id = (msgSeq++ and 0xFF).toByte()
            for (i in 0 until total) {
                val from = i * chunk
                val to = minOf(from + chunk, bytes.size)
                val sliceLen = (to - from).coerceAtLeast(0)
                val pkt = ByteArray(HEADER + sliceLen)
                pkt[0] = 1
                pkt[1] = id
                pkt[2] = ((total shr 8) and 0xFF).toByte()
                pkt[3] = (total and 0xFF).toByte()
                pkt[4] = ((i shr 8) and 0xFF).toByte()
                pkt[5] = (i and 0xFF).toByte()
                if (sliceLen > 0) System.arraycopy(bytes, from, pkt, HEADER, sliceLen)

                try {
                    char.value = pkt
                    server.notifyCharacteristicChanged(device, char, false)
                } catch (_: Exception) {
                    return false
                }
                // 背压：连发太快（尤其 MTU 小的时候）BLE 协议栈会丢包
                if (total > 1) {
                    try { Thread.sleep(10) } catch (_: Exception) {}
                }
            }
        }
        return true
    }
}
