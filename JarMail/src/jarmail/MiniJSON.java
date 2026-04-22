package jarmail;
import java.util.Hashtable;
import java.util.Vector;

public class MiniJSON {
    public static Object parse(String s) throws Exception {
        char[] c = s.toCharArray();
        int[] p = new int[]{0};
        return readVal(c, p);
    }

    private static Object readVal(char[] c, int[] p) throws Exception {
        skip(c, p);
        if (p[0] >= c.length) return null;
        char first = c[p[0]];
        if (first == '{') return readObj(c, p);
        if (first == '[') return readArr(c, p);
        if (first == '"') return readStr(c, p);
        return readLit(c, p);
    }

    private static Hashtable readObj(char[] c, int[] p) throws Exception {
        Hashtable h = new Hashtable();
        p[0]++;
        while (p[0] < c.length) {
            skip(c, p);
            if (c[p[0]] == '}') { p[0]++; break; }
            String key = readStr(c, p);
            skip(c, p);
            p[0]++;
            h.put(key, readVal(c, p));
            skip(c, p);
            if (p[0] < c.length && c[p[0]] == ',') p[0]++;
        }
        return h;
    }

    private static Vector readArr(char[] c, int[] p) throws Exception {
        Vector v = new Vector();
        p[0]++;
        while (p[0] < c.length) {
            skip(c, p);
            if (c[p[0]] == ']') { p[0]++; break; }
            v.addElement(readVal(c, p));
            skip(c, p);
            if (p[0] < c.length && c[p[0]] == ',') p[0]++;
        }
        return v;
    }

    private static String readStr(char[] c, int[] p) {
        p[0]++;
        StringBuffer b = new StringBuffer();
        while (p[0] < c.length) {
            char curr = c[p[0]++];
            if (curr == '"') break;
            if (curr == '\\' && p[0] < c.length) {
                char next = c[p[0]++];
                if (next == 'n') b.append('\n');
                else b.append(next);
            } else b.append(curr);
        }
        return b.toString();
    }

    private static Object readLit(char[] c, int[] p) {
        StringBuffer b = new StringBuffer();
        while (p[0] < c.length && c[p[0]] != ',' && c[p[0]] != '}' && c[p[0]] != ']' && c[p[0]] > ' ') {
            b.append(c[p[0]++]);
        }
        return b.toString();
    }

    private static void skip(char[] c, int[] p) {
        while (p[0] < c.length && c[p[0]] <= ' ') p[0]++;
    }
}