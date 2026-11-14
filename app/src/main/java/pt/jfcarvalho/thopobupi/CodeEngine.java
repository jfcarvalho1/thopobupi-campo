package pt.jfcarvalho.thopobupi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Gera linhas a partir dos códigos dos pontos.
 * Pontos seguidos com o mesmo código de linha ligam-se (1-2, 2-3, ...).
 * Comandos na descrição: ST ou +7 inicia linha nova, END ou -7 termina,
 * CLO fecha a linha ao primeiro vértice.
 */
public final class CodeEngine {

    public static class Line {
        public String code;
        public final List<Pt> verts = new ArrayList<>();
        public boolean closed;
    }

    private CodeEngine() {}

    public static Set<String> parseList(String csv) {
        Set<String> out = new HashSet<>();
        if (csv == null) return out;
        for (String s : csv.split("[,;\\s]+")) {
            String t = s.trim().toUpperCase(Locale.ROOT);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public static List<Line> build(List<Pt> pts, Set<String> lineCodes) {
        List<Line> done = new ArrayList<>();
        Map<String, Line> open = new HashMap<>();
        for (Pt p : pts) {
            String code = p.code();
            if (code.isEmpty() || !lineCodes.contains(code)) continue;
            Set<String> cmds = commands(p.desc);
            Line cur = open.get(code);
            if (cmds.contains("ST") || cmds.contains("+7")) {
                if (cur != null) finish(cur, done);
                cur = new Line();
                cur.code = code;
                open.put(code, cur);
            }
            if (cur == null) {
                cur = new Line();
                cur.code = code;
                open.put(code, cur);
            }
            cur.verts.add(p);
            if (cmds.contains("CLO")) {
                cur.closed = true;
                finish(cur, done);
                open.remove(code);
            } else if (cmds.contains("END") || cmds.contains("-7")) {
                finish(cur, done);
                open.remove(code);
            }
        }
        for (Line l : open.values()) finish(l, done);
        return done;
    }

    private static void finish(Line l, List<Line> done) {
        if (l.verts.size() >= 2) done.add(l);
    }

    private static Set<String> commands(String desc) {
        Set<String> c = new HashSet<>();
        if (desc == null) return c;
        String[] parts = desc.trim().split("\\s+");
        for (int i = 1; i < parts.length; i++) c.add(parts[i].toUpperCase(Locale.ROOT));
        return c;
    }

    /** Área (m²) de uma linha fechada, pela fórmula de Gauss. */
    public static double area(Line l) {
        int n = l.verts.size();
        if (n < 3) return 0;
        double s = 0;
        for (int i = 0; i < n; i++) {
            Pt a = l.verts.get(i), b = l.verts.get((i + 1) % n);
            s += a.e * b.n - b.e * a.n;
        }
        return Math.abs(s) / 2.0;
    }

    public static double length(Line l) {
        double s = 0;
        int n = l.verts.size();
        for (int i = 1; i < n; i++) s += dist(l.verts.get(i - 1), l.verts.get(i));
        if (l.closed && n > 2) s += dist(l.verts.get(n - 1), l.verts.get(0));
        return s;
    }

    public static double dist(Pt a, Pt b) {
        return Math.hypot(b.e - a.e, b.n - a.n);
    }

    /** Rumo (azimute) de A para B em graus, a partir do Norte, sentido horário. */
    public static double azimuthDeg(double e1, double n1, double e2, double n2) {
        double az = Math.toDegrees(Math.atan2(e2 - e1, n2 - n1));
        if (az < 0) az += 360;
        return az;
    }
}
