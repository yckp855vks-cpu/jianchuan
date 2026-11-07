import importlib.util, json, os, threading, time, urllib.error, urllib.request

spec = importlib.util.spec_from_file_location("srv", "server.py")
srv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(srv)

srv.PASSWORD = ""
srv.LAN_IP = "127.0.0.1"
srv.PORT = 8801
httpd = srv.ThreadingHTTPServer(("127.0.0.1", 8801), srv.Handler)
threading.Thread(target=httpd.serve_forever, daemon=True).start()
time.sleep(0.4)
BASE = "http://127.0.0.1:8801"


def post(body):
    data = json.dumps(body).encode()
    req = urllib.request.Request(BASE + "/api/reveal", method="POST", data=data)
    req.add_header("Content-Type", "application/json")
    req.add_header("Content-Length", str(len(data)))
    try:
        with urllib.request.urlopen(req, timeout=8) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())


print("落盘目录:", srv.user_save_dir())
print("文件目录:", srv.FILE_DIR)

# 1) 造一个真实文件在 data/files 里
stored = "_reveal_probe.txt"
p = os.path.join(srv.FILE_DIR, stored)
with open(p, "w", encoding="utf-8") as f:
    f.write("hi")
print("1) stored 命中          ->", post({"stored": stored}))
os.remove(p)
print("2) 文件已删除           ->", post({"stored": stored}))

# 3) 路径穿越
print("3) ../../Windows/win.ini ->", post({"path": "C://Windows//win.ini"}))
print("4) stored 带路径分隔符   ->", post({"stored": "..\\..\\Windows\\win.ini"}))
print("5) 随便一个用户目录文件  ->", post({"path": os.path.expanduser("~\\Desktop")}))
print("6) 空 body              ->", post({}))

# 7) 正常路径（会真的弹出资源管理器）
d = srv.user_save_dir()
probe = os.path.join(d, "_简传Lite定位测试.txt")
with open(probe, "w", encoding="utf-8") as f:
    f.write("test")
print("7) 正常落盘文件          ->", post({"path": probe}))

httpd.shutdown()
time.sleep(0.6)
try:
    os.remove(probe)
    print("   (探针文件已删除)")
except OSError as e:
    print("   删除探针失败:", e)
