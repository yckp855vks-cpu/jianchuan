import importlib.util, json, os, threading, time, urllib.request

spec = importlib.util.spec_from_file_location("srv", "server.py")
srv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(srv)

srv.PASSWORD = ""
srv.LAN_IP = "127.0.0.1"
srv.PORT = 8802
httpd = srv.ThreadingHTTPServer(("127.0.0.1", 8802), srv.Handler)
threading.Thread(target=httpd.serve_forever, daemon=True).start()
time.sleep(0.4)
BASE = "http://127.0.0.1:8802"

name = "定位测试 带空格&符号.txt"
body = ("测试内容 " * 40).encode("utf-8")
url = BASE + "/api/upload?name=" + urllib.request.quote(name) + "&client=phone-test"
req = urllib.request.Request(url, method="POST", data=body)
req.add_header("Content-Type", "application/octet-stream")
req.add_header("Content-Length", str(len(body)))
with urllib.request.urlopen(req, timeout=15) as r:
    m = json.loads(r.read().decode())["message"]

print("上传消息 id      :", m["id"])
print("file.stored      :", m["file"]["stored"])
print("saved            :", m.get("saved"))

with urllib.request.urlopen(BASE + "/api/messages?after=0&client=pc", timeout=10) as r:
    d = json.loads(r.read().decode())
recv = (d.get("received") or [])[-1]
print("received[0].stored:", recv.get("stored"))
print("received[0].path  :", recv.get("path"))

# 用 messages 里拿到的 saved.path 调 reveal（不真的弹窗：先查白名单内）
sys_path = (m.get("saved") or {}).get("path")
if sys_path and os.path.isfile(sys_path):
    print("落盘文件存在      : True")
else:
    print("落盘文件存在      : False ->", sys_path)

httpd.shutdown()
time.sleep(0.5)
# 清理
for p in [sys_path, os.path.join(srv.FILE_DIR, m["file"]["stored"])]:
    try:
        if p: os.remove(p)
    except OSError:
        pass
print("已清理测试文件")
