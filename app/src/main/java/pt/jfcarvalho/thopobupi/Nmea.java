package pt.jfcarvalho.thopobupi;

/** Leitor de frases NMEA 0183. Devolve o tipo da frase lida ou null. */
public final class Nmea {
    private Nmea() {}

    public static boolean checksumOk(String line) {
        int star = line.lastIndexOf('*');
        if (!line.startsWith("$") || star < 0) return false;
        if (star + 3 > line.length()) return false;
        int cs = 0;
        for (int i = 1; i < star; i++) cs ^= line.charAt(i);
        try {
            int given = Integer.parseInt(line.substring(star + 1, star + 3), 16);
            return given == cs;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static String parse(String raw, GnssState s, long now) {
        if (raw == null) return null;
        String line = raw.trim();
        if (line.length() < 7 || !checksumOk(line)) return null;
        String body = line.substring(1, line.lastIndexOf('*'));
        String[] f = body.split(",", -1);
        if (f[0].length() < 5) return null;
        String type = f[0].substring(f[0].length() - 3);
        switch (type) {
            case "GGA": gga(f, s, now, line); break;
            case "GST": gst(f, s, now); break;
            case "GSV": gsv(f, s); break;
            default: break;
        }
        return type;
    }

    private static double num(String v) {
        if (v == null || v.isEmpty()) return Double.NaN;
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return Double.NaN; }
    }

    private static int inum(String v, int def) {
        if (v == null || v.isEmpty()) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    static double coord(String v, String hemi, boolean lat) {
        if (v == null || v.isEmpty()) return Double.NaN;
        int degLen = lat ? 2 : 3;
        if (v.length() < degLen + 2) return Double.NaN;
        double deg = num(v.substring(0, degLen));
        double min = num(v.substring(degLen));
        double d = deg + min / 60.0;
        if ("S".equals(hemi) || "W".equals(hemi)) d = -d;
        return d;
    }

    private static void gga(String[] f, GnssState s, long now, String line) {
        if (f.length < 15) return;
        s.quality = inum(f[6], 0);
        s.sats = inum(f[7], 0);
        s.hdop = num(f[8]);
        if (s.quality > 0) {
            s.lat = coord(f[2], f[3], true);
            s.lon = coord(f[4], f[5], false);
            s.alt = num(f[9]);
            s.geoidSep = num(f[11]);
        } else {
            s.lat = Double.NaN; s.lon = Double.NaN; s.alt = Double.NaN;
        }
        s.age = num(f[13]);
        s.lastGgaMillis = now;
        s.lastGga = line;
    }

    private static void gst(String[] f, GnssState s, long now) {
        if (f.length < 9) return;
        s.latErr = num(f[6]);
        s.lonErr = num(f[7]);
        s.altErr = num(f[8]);
        s.lastGstMillis = now;
    }

    private static void gsv(String[] f, GnssState s) {
        if (f.length < 4) return;
        // Total de satélites em vista da primeira mensagem do sistema GP (os restantes somam-se)
        if ("1".equals(f[2])) {
            int n = inum(f[3], 0);
            if (f[0].startsWith("GP") || f[0].startsWith("GN")) s.satsInView = n;
        }
    }
}
