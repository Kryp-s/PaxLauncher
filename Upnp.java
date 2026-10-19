import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.*;

/** Автоматичне відкриття порту на роутері через UPnP та визначення зовнішньої адреси. Працює без прав адміністратора. */
final class Upnp {
    static final class Result {
        final String ip;
        final boolean mapped;
        final String note;
        Result(String ip, boolean mapped, String note) { this.ip = ip; this.mapped = mapped; this.note = note; }
    }

    private static final class Map1 {
        final String control, service;
        final int port;
        Map1(String control, String service, int port) { this.control = control; this.service = service; this.port = port; }
    }

    private static final List<Map1> active = new CopyOnWriteArrayList<>();

    private Upnp() { }

    static Result open(int port) {
        String extIp = null;
        boolean mapped = false;
        try {
            for (String loc : discover()) {
                try {
                    String desc = http("GET", loc, null, null, null);
                    String[] svc = findService(desc, loc);
                    if (svc == null) continue;
                    String control = svc[0], type = svc[1];
                    String local = localIp(control);
                    if (local == null) continue;
                    boolean ok = add(control, type, port, local, 0) || add(control, type, port, local, 7200);
                    if (!ok) continue;
                    active.add(new Map1(control, type, port));
                    mapped = true;
                    try {
                        String r = soap(control, type, "GetExternalIPAddress", "");
                        Matcher m = Pattern.compile("<NewExternalIPAddress>\\s*([0-9.]+)\\s*</NewExternalIPAddress>").matcher(r);
                        if (m.find()) extIp = m.group(1);
                    } catch (Exception ignored) { }
                    break;
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        if (extIp == null || extIp.equals("0.0.0.0") || extIp.isEmpty()) extIp = publicIp();
        if (extIp == null) return new Result(null, mapped, "Не вдалося визначити зовнішню адресу. Перевір інтернет.");
        String note;
        if (isPrivate(extIp)) note = "Провайдер, схоже, не видає публічну адресу — друг може не підключитися.";
        else if (!mapped) note = "Роутер не підтримує UPnP. Один раз відкрий порт " + port + " (TCP) у роутері на цей комп'ютер — наступні сесії використають той самий порт.";
        else note = " ";
        return new Result(extIp, mapped, note);
    }

    static void close(int port) {
        for (Map1 m : active) {
            if (m.port != port) continue;
            try {
                soap(m.control, m.service, "DeletePortMapping",
                        "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + port + "</NewExternalPort><NewProtocol>TCP</NewProtocol>");
            } catch (Exception ignored) { }
            active.remove(m);
        }
    }

    static void closeAll() {
        for (Map1 m : new ArrayList<>(active)) close(m.port);
    }

    // ---------- internals ----------

    private static boolean add(String control, String type, int port, String local, int lease) {
        try {
            soap(control, type, "AddPortMapping",
                    "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + port + "</NewExternalPort><NewProtocol>TCP</NewProtocol>"
                            + "<NewInternalPort>" + port + "</NewInternalPort><NewInternalClient>" + local + "</NewInternalClient>"
                            + "<NewEnabled>1</NewEnabled><NewPortMappingDescription>PaxLauncher</NewPortMappingDescription>"
                            + "<NewLeaseDuration>" + lease + "</NewLeaseDuration>");
            return true;
        } catch (Exception e) { return false; }
    }

    private static List<String> discover() throws IOException {
        List<String> out = new ArrayList<>();
        String[] sts = {"urn:schemas-upnp-org:device:InternetGatewayDevice:1",
                "urn:schemas-upnp-org:service:WANIPConnection:1", "upnp:rootdevice"};
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(1200);
            InetAddress grp = InetAddress.getByName("239.255.255.250");
            for (String st : sts) {
                String msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + st + "\r\n\r\n";
                byte[] b = msg.getBytes(StandardCharsets.US_ASCII);
                s.send(new DatagramPacket(b, b.length, grp, 1900));
            }
            long end = System.currentTimeMillis() + 3000;
            Pattern p = Pattern.compile("(?im)^location:\\s*(\\S+)");
            while (System.currentTimeMillis() < end) {
                try {
                    DatagramPacket pk = new DatagramPacket(new byte[2048], 2048);
                    s.receive(pk);
                    Matcher m = p.matcher(new String(pk.getData(), 0, pk.getLength(), StandardCharsets.ISO_8859_1));
                    if (m.find() && !out.contains(m.group(1))) out.add(m.group(1));
                } catch (SocketTimeoutException e) {
                    if (!out.isEmpty()) break;
                }
            }
        }
        return out;
    }

    private static String[] findService(String desc, String location) throws IOException {
        Matcher sm = Pattern.compile("(?is)<service>(.*?)</service>").matcher(desc);
        while (sm.find()) {
            String blk = sm.group(1);
            Matcher t = Pattern.compile("(?is)<serviceType>\\s*(.*?)\\s*</serviceType>").matcher(blk);
            Matcher c = Pattern.compile("(?is)<controlURL>\\s*(.*?)\\s*</controlURL>").matcher(blk);
            if (!t.find() || !c.find()) continue;
            String type = t.group(1);
            if (!type.contains("WANIPConnection") && !type.contains("WANPPPConnection")) continue;
            String url = new URL(new URL(location), c.group(1)).toString();
            return new String[]{url, type};
        }
        return null;
    }

    private static String localIp(String control) {
        try {
            URL u = new URL(control);
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(u.getHost(), u.getPort() > 0 ? u.getPort() : 80), 2000);
                return s.getLocalAddress().getHostAddress();
            }
        } catch (Exception e) { return null; }
    }

    private static String soap(String control, String type, String action, String args) throws IOException {
        String body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:" + action + " xmlns:u=\"" + type + "\">"
                + args + "</u:" + action + "></s:Body></s:Envelope>";
        return http("POST", control, body, "text/xml; charset=\"utf-8\"", "\"" + type + "#" + action + "\"");
    }

    private static String http(String method, String url, String body, String ctype, String soapAction) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(4000);
        c.setRequestMethod(method);
        if (ctype != null) c.setRequestProperty("Content-Type", ctype);
        if (soapAction != null) c.setRequestProperty("SOAPAction", soapAction);
        if (body != null) {
            c.setDoOutput(true);
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String r = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        if (code >= 400) throw new IOException("HTTP " + code);
        return r;
    }

    static String publicIp() {
        for (String u : new String[]{"https://api.ipify.org", "https://ipv4.icanhazip.com"}) {
            try {
                String r = http("GET", u, null, null, null).trim();
                if (r.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return r;
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static boolean isPrivate(String ip) {
        try {
            String[] p = ip.split("\\.");
            int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
            return a == 10 || a == 127 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168)
                    || (a == 100 && b >= 64 && b <= 127) || (a == 169 && b == 254);
        } catch (Exception e) { return false; }
    }
}
