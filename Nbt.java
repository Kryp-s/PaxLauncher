import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Мінімальний читач/писач NBT (без стиснення) — лише для того, щоб дописувати сервери в servers.dat. */
final class Nbt {
    private static final class Tag {
        final int type;
        Object v;
        Tag(int type, Object v) { this.type = type; this.v = v; }
    }

    private static final class Lst {
        int elem;
        final List<Object> items = new ArrayList<>();
        Lst(int elem) { this.elem = elem; }
    }

    private Nbt() { }

    /** Додає (або оновлює за назвою) сервер у файлі servers.dat. */
    static void addServer(Path dat, String name, String ip) throws IOException { addServer(dat, name, ip, null); }

    /** Те саме, але з іконкою сервера (PNG 64×64 у base64 без префікса). */
    static void addServer(Path dat, String name, String ip, String icon) throws IOException {
        Map<String, Tag> root = new LinkedHashMap<>();
        if (Files.isRegularFile(dat) && Files.size(dat) > 0) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(dat)))) {
                if (in.readByte() == 10) {
                    in.readUTF();
                    @SuppressWarnings("unchecked") Map<String, Tag> r = (Map<String, Tag>) payload(in, 10);
                    root = r;
                }
            } catch (Exception e) {
                root = new LinkedHashMap<>();
            }
        }
        Tag st = root.get("servers");
        Lst list;
        if (st == null || st.type != 9) {
            list = new Lst(10);
            root.put("servers", new Tag(9, list));
        } else {
            list = (Lst) st.v;
            if (list.items.isEmpty()) list.elem = 10;
        }
        boolean found = false;
        if (list.elem == 10) {
            for (Object o : list.items) {
                @SuppressWarnings("unchecked") Map<String, Tag> c = (Map<String, Tag>) o;
                Tag n = c.get("name");
                if (n != null && n.type == 8 && name.equals(n.v)) {
                    c.put("ip", new Tag(8, ip));
                    if (icon != null) c.put("icon", new Tag(8, icon));
                    found = true;
                }
            }
        }
        if (!found && list.elem == 10) {
            Map<String, Tag> c = new LinkedHashMap<>();
            c.put("name", new Tag(8, name));
            c.put("ip", new Tag(8, ip));
            if (icon != null) c.put("icon", new Tag(8, icon));
            list.items.add(c);
        }
        Path tmp = dat.resolveSibling(dat.getFileName() + ".tmp");
        Files.createDirectories(dat.getParent());
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeByte(10);
            out.writeUTF("");
            write(out, 10, root);
        }
        Files.move(tmp, dat, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Прибирає сервер із servers.dat за назвою (якщо файл і запис є). */
    static void removeServer(Path dat, String name) throws IOException {
        if (!Files.isRegularFile(dat) || Files.size(dat) == 0) return;
        Map<String, Tag> root;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(dat)))) {
            if (in.readByte() != 10) return;
            in.readUTF();
            @SuppressWarnings("unchecked") Map<String, Tag> r = (Map<String, Tag>) payload(in, 10);
            root = r;
        } catch (Exception e) { return; }
        Tag st = root.get("servers");
        if (st == null || st.type != 9) return;
        Lst list = (Lst) st.v;
        if (list.elem != 10) return;
        int before = list.items.size();
        list.items.removeIf(o -> {
            @SuppressWarnings("unchecked") Map<String, Tag> c = (Map<String, Tag>) o;
            Tag t = c.get("name");
            return t != null && t.type == 8 && name.equals(t.v);
        });
        if (list.items.size() == before) return;
        Path tmp = dat.resolveSibling(dat.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeByte(10);
            out.writeUTF("");
            write(out, 10, root);
        }
        Files.move(tmp, dat, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Object payload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: { byte[] b = new byte[in.readInt()]; in.readFully(b); return b; }
            case 8: return in.readUTF();
            case 9: {
                int et = in.readByte();
                int n = in.readInt();
                Lst l = new Lst(et);
                for (int i = 0; i < n; i++) l.items.add(payload(in, et));
                return l;
            }
            case 10: {
                Map<String, Tag> m = new LinkedHashMap<>();
                while (true) {
                    int t = in.readByte();
                    if (t == 0) break;
                    String k = in.readUTF();
                    m.put(k, new Tag(t, payload(in, t)));
                }
                return m;
            }
            case 11: { int[] a = new int[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readInt(); return a; }
            case 12: { long[] a = new long[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readLong(); return a; }
            default: throw new IOException("NBT tag " + type);
        }
    }

    @SuppressWarnings("unchecked")
    private static void write(DataOutputStream out, int type, Object v) throws IOException {
        switch (type) {
            case 1: out.writeByte((Byte) v); break;
            case 2: out.writeShort((Short) v); break;
            case 3: out.writeInt((Integer) v); break;
            case 4: out.writeLong((Long) v); break;
            case 5: out.writeFloat((Float) v); break;
            case 6: out.writeDouble((Double) v); break;
            case 7: { byte[] b = (byte[]) v; out.writeInt(b.length); out.write(b); break; }
            case 8: out.writeUTF((String) v); break;
            case 9: {
                Lst l = (Lst) v;
                out.writeByte(l.items.isEmpty() ? 0 : l.elem);
                out.writeInt(l.items.size());
                for (Object o : l.items) write(out, l.elem, o);
                break;
            }
            case 10: {
                for (Map.Entry<String, Tag> e : ((Map<String, Tag>) v).entrySet()) {
                    out.writeByte(e.getValue().type);
                    out.writeUTF(e.getKey());
                    write(out, e.getValue().type, e.getValue().v);
                }
                out.writeByte(0);
                break;
            }
            case 11: { int[] a = (int[]) v; out.writeInt(a.length); for (int x : a) out.writeInt(x); break; }
            case 12: { long[] a = (long[]) v; out.writeInt(a.length); for (long x : a) out.writeLong(x); break; }
            default: throw new IOException("NBT tag " + type);
        }
    }
}
