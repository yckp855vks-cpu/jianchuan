package com.gathin.tvplayer;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 在局域网里找「可以播片的服务端」，并且认得出是哪一种。
 *
 * 之前只认电脑上的 MediaShare（8848 + /api/list 里的 entries 字段），
 * 手机上的简传Lite（8788）永远发现不了，只能手输地址。现在两种都扫、都认证：
 *
 *   简传Lite    8788  GET /api/alive  返回里带 "ok"（新版还有 app:"jianchuan-lite"）
 *   MediaShare  8848  GET /api/list   返回里带 "entries"
 *
 * 端口只是线索，身份靠上面的字段判定 —— 端口被占用、改过端口号也不会认错。
 */
public final class ServerFinder {

    public static final int PORT_MEDIA = 8848;
    public static final int PORT_JIANCHUAN = 8788;

    public static final String KIND_MEDIA = "media";
    public static final String KIND_JIANCHUAN = "jianchuan";

    /** 必须与手机端 MobileServer.APP_TAG 一致 */
    private static final String FINGERPRINT = "jianchuan-lite";

    private ServerFinder() {
    }

    /** 一台可用服务端。 */
    public static final class Server {
        public final String kind;    // media / jianchuan
        public final String host;
        public final int port;
        public final String name;    // 给人看的名字
        public final String detail;  // 附加信息，可空
        public final String url;     // 直接丢给 WebView 的地址

        Server(String kind, String host, int port, String name, String detail) {
            this.kind = kind;
            this.host = host;
            this.port = port;
            this.name = (name == null || name.length() == 0) ? defaultName(kind) : name;
            this.detail = detail;
            // 简传要落电视页；MediaShare 首页本身就是列表页
            this.url = KIND_JIANCHUAN.equals(kind)
                    ? "http://" + host + ":" + port + "/tv"
                    : "http://" + host + ":" + port;
        }

        /** 稳定标识：不含路径，用来在记忆里比对同一台设备 */
        public String key() {
            return kind + "|" + host + "|" + port;
        }

        private static String defaultName(String kind) {
            return KIND_JIANCHUAN.equals(kind) ? "简传Lite（手机）" : "电脑媒体库";
        }
    }

    // ---------------- 全量扫描 ----------------

    /**
     * 扫一遍所有私有网段，返回找到的服务端。
     * budgetMs 是总预算，超时就把已找到的先返回。
     */
    public static List<Server> scan(long budgetMs) {
        final List<Server> out = Collections.synchronizedList(new ArrayList<Server>());
        final List<String> hosts = candidateHosts();
        if (hosts.isEmpty()) return out;

        final int[] ports = {PORT_JIANCHUAN, PORT_MEDIA};
        ExecutorService pool = Executors.newFixedThreadPool(64);
        for (final String h : hosts) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    for (int p : ports) {
                        // 端口不通立刻返回，绝大多数地址在这里几毫秒内结束
                        if (!portOpen(h, p, 280)) continue;
                        Server s = probe(h, p);
                        if (s != null) out.add(s);
                    }
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(budgetMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        pool.shutdownNow();

        List<Server> list = new ArrayList<Server>(out);
        Collections.sort(list, new Comparator<Server>() {
            @Override
            public int compare(Server a, Server b) {
                if (a.kind.equals(b.kind)) return a.host.compareTo(b.host);
                // 手机排前面，用户更可能是在找它
                return a.kind.equals(KIND_JIANCHUAN) ? -1 : 1;
            }
        });
        return list;
    }

    // ---------------- 单点探测 ----------------

    /**
     * 认证某个 host:port 上跑的是什么服务，认不出返回 null。
     * 按端口先猜一次能省一半请求，猜错再试另一种。
     */
    public static Server probe(String host, int port) {
        boolean jcFirst = (port == PORT_JIANCHUAN);
        Server s = jcFirst ? asJianchuan(host, port) : asMedia(host, port);
        if (s != null) return s;
        return jcFirst ? asMedia(host, port) : asJianchuan(host, port);
    }

    /** 按 key（kind|host|port）重新探测，用于「离线设备重新上线」 */
    public static Server probeKey(String key) {
        if (key == null) return null;
        String[] p = key.split("\\|");
        if (p.length < 3) return null;
        try {
            Server s = probe(p[1], Integer.parseInt(p[2]));
            // 类型对不上就不认：同一个 IP 可能换了服务
            return (s != null && s.kind.equals(p[0])) ? s : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 这个地址现在还能连上吗（只探端口，不校验是什么服务） */
    public static boolean alive(String baseUrl) {
        if (baseUrl == null) return false;
        try {
            URI u = URI.create(baseUrl);
            String host = u.getHost();
            if (host == null) return false;
            int port = u.getPort() > 0 ? u.getPort() : PORT_MEDIA;
            return portOpen(host, port, 700);
        } catch (Exception e) {
            return false;
        }
    }

    /** 从一条 URL 反推服务端并认证。手输地址后校验用。 */
    public static Server probeUrl(String url) {
        if (url == null) return null;
        try {
            URI u = URI.create(url);
            String host = u.getHost();
            if (host == null) return null;
            int port = u.getPort() > 0 ? u.getPort() : 80;
            return probe(host, port);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- 指纹 ----------------

    /** 简传Lite：/api/alive 是它独有的轻量接口，电脑端没有这个路由（会 404） */
    private static Server asJianchuan(String host, int port) {
        String body = httpGet("http://" + host + ":" + port + "/api/alive", 800, 1200, 2048);
        if (body == null) return null;
        boolean isJc = body.contains("\"" + FINGERPRINT + "\"")
                || (body.contains("\"ok\"") && body.contains("\"ips\""));
        if (!isJc) return null;
        String dev = strField(body, "name");
        return new Server(KIND_JIANCHUAN, host, port, dev, null);
    }

    /** MediaShare：/api/list 的返回里一定有 entries 和 path */
    private static Server asMedia(String host, int port) {
        String body = httpGet("http://" + host + ":" + port + "/api/list", 800, 1600, 4096);
        if (body == null) return null;
        if (!body.contains("\"entries\"") || !body.contains("\"path\"")) return null;
        return new Server(KIND_MEDIA, host, port, "电脑媒体库", null);
    }

    // ---------------- 内部 ----------------

    private static String httpGet(String url, int connectMs, int readMs, int maxBytes) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Connection", "close");
            conn.setRequestProperty("User-Agent", "tvplayer");
            if (conn.getResponseCode() != 200) return null;
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[maxBytes];
            int n = 0, r;
            while (n < maxBytes && (r = in.read(buf, n, maxBytes - n)) > 0) n += r;
            in.close();
            return n > 0 ? new String(buf, 0, n, "UTF-8") : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 从 JSON 里抠一个字符串字段，够用，不引 json 库 */
    private static String strField(String json, String key) {
        if (json == null) return null;
        String pat = "\"" + key + "\":";
        int i = json.indexOf(pat);
        if (i < 0) return null;
        int s = i + pat.length();
        // 容错冒号后的空格（有些序列化实现会带）
        while (s < json.length() && json.charAt(s) == ' ') s++;
        if (s >= json.length() || json.charAt(s) != '"') return null;
        int e = json.indexOf('"', s + 1);
        if (e <= s + 1) return null;
        String v = json.substring(s + 1, e).trim();
        return v.length() == 0 ? null : v;
    }

    private static List<String> candidateHosts() {
        List<String> out = new ArrayList<String>();
        for (String prefix : localPrefixes()) {
            for (int i = 1; i <= 254; i++) out.add(prefix + "." + i);
        }
        return out;
    }

    /**
     * 取本机所有 IPv4 地址的前三段。
     *
     * 这里原来只认 192.168.x：路由器换成 10.0.x 或 172.16.x 网段时
     * candidateHosts 会直接返回空、扫描静默失效。现在三种私有网段都认。
     */
    private static List<String> localPrefixes() {
        List<String> out = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                // 用原始类型：Android 与标准 JDK 的 InetAddress 签名不一致
                Enumeration<?> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    Object o = addrs.nextElement();
                    if (!(o instanceof Inet4Address)) continue;
                    String ip = ((InetAddress) o).getHostAddress();
                    if (ip == null || !isPrivateV4(ip)) continue;
                    int idx = ip.lastIndexOf('.');
                    if (idx <= 0) continue;
                    String prefix = ip.substring(0, idx);
                    if (!out.contains(prefix)) out.add(prefix);
                }
            }
        } catch (Exception ignored) {
        }
        Collections.sort(out);
        return out;
    }

    /** 10.0.0.0/8、172.16.0.0/12、192.168.0.0/16 */
    private static boolean isPrivateV4(String ip) {
        if (ip.startsWith("192.168.")) return true;
        if (ip.startsWith("10.")) return true;
        if (ip.startsWith("172.")) {
            int dot = ip.indexOf('.', 4);
            if (dot < 0) return false;
            try {
                int b = Integer.parseInt(ip.substring(4, dot));
                return b >= 16 && b <= 31;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    private static boolean portOpen(String host, int port, int timeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }
}
