import java.util.*;
import java.util.regex.*;

/** Код запрошення «PAX-XXXX-XXXX-XXXX»: зовнішня IPv4-адреса + порт + контрольна сума (7 байт у base32). */
final class Invite {
    private static final String A = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Invite() { }

    static String encode(String ip, int port) {
        if (ip == null || port < 1 || port > 65535) return null;
        String[] p = ip.trim().split("\\.");
        if (p.length != 4) return null;
        byte[] b = new byte[7];
        int sum = 0;
        try {
            for (int i = 0; i < 4; i++) {
                int v = Integer.parseInt(p[i]);
                if (v < 0 || v > 255) return null;
                b[i] = (byte) v;
                sum += v;
            }
        } catch (NumberFormatException e) { return null; }
        b[4] = (byte) (port >> 8);
        b[5] = (byte) port;
        sum += ((port >> 8) & 255) + (port & 255);
        b[6] = (byte) sum;
        StringBuilder sb = new StringBuilder();
        long acc = 0;
        int bits = 0;
        for (byte x : b) {
            acc = (acc << 8) | (x & 255);
            bits += 8;
            while (bits >= 5) {
                sb.append(A.charAt((int) ((acc >> (bits - 5)) & 31)));
                bits -= 5;
                acc &= (1L << bits) - 1;
            }
        }
        if (bits > 0) sb.append(A.charAt((int) ((acc << (5 - bits)) & 31)));
        String s = sb.toString();
        return "PAX-" + s.substring(0, 4) + "-" + s.substring(4, 8) + "-" + s.substring(8);
    }

    /** Повертає {ip, port} або null, якщо код невірний. */
    static String[] decode(String code) {
        if (code == null) return null;
        String c = code.trim().toUpperCase(Locale.ROOT);
        if (c.startsWith("PAX-")) c = c.substring(4);
        c = c.replaceAll("[^A-Z2-7]", "");
        if (c.length() != 12) return null;
        byte[] out = new byte[7];
        int n = 0, bits = 0;
        long acc = 0;
        for (int i = 0; i < c.length() && n < 7; i++) {
            acc = (acc << 5) | A.indexOf(c.charAt(i));
            bits += 5;
            if (bits >= 8) {
                out[n++] = (byte) ((acc >> (bits - 8)) & 255);
                bits -= 8;
                acc &= (1L << bits) - 1;
            }
        }
        if (n < 7) return null;
        int sum = 0;
        for (int i = 0; i < 6; i++) sum += out[i] & 255;
        if ((byte) sum != out[6]) return null;
        int port = ((out[4] & 255) << 8) | (out[5] & 255);
        if (port < 1) return null;
        String ip = (out[0] & 255) + "." + (out[1] & 255) + "." + (out[2] & 255) + "." + (out[3] & 255);
        return new String[]{ip, String.valueOf(port)};
    }

    /** Чи підтримує версія аргумент --quickPlaySingleplayer / --quickPlayMultiplayer (1.20+). */
    static boolean modern(String mc) {
        Matcher m = Pattern.compile("^1\\.(\\d+)").matcher(mc == null ? "" : mc);
        return !(m.find() && Integer.parseInt(m.group(1)) < 20);
    }

    /** Аргументи гри для автоматичного входу на сервер (1.20+ — quickPlay, старіші — --server/--port). */
    static List<String> joinArgs(String mc, String hostPort) {
        Matcher m = Pattern.compile("^1\\.(\\d+)").matcher(mc == null ? "" : mc);
        boolean old = m.find() && Integer.parseInt(m.group(1)) < 20;
        if (old) {
            String[] hp = hostPort.split(":");
            return List.of("--server", hp[0], "--port", hp[1]);
        }
        return List.of("--quickPlayMultiplayer", hostPort);
    }
}
