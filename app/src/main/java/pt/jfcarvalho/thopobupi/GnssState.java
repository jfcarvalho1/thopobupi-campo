package pt.jfcarvalho.thopobupi;

import java.util.Locale;

/** Estado GNSS lido das mensagens NMEA (GGA, GST, GSV, GSA, RMC). */
public class GnssState {
    public double lat = Double.NaN, lon = Double.NaN;
    public double alt = Double.NaN;      // altitude ortométrica (GGA campo 9)
    public double geoidSep = Double.NaN;
    public int quality = 0;              // 0 sem, 1 autónomo, 2 DGPS, 4 FIX, 5 FLOAT
    public int sats = 0;
    public int satsInView = 0;
    public double hdop = Double.NaN;
    public double age = Double.NaN;      // idade das correcções (s)
    public double latErr = Double.NaN, lonErr = Double.NaN, altErr = Double.NaN;
    public long lastGgaMillis = 0;
    public long lastGstMillis = 0;
    public String lastGga = null;

    public boolean hasPosition() {
        return quality > 0 && !Double.isNaN(lat) && !Double.isNaN(lon);
    }

    public double hErr() {
        if (Double.isNaN(latErr) || Double.isNaN(lonErr)) return Double.NaN;
        return Math.sqrt(latErr * latErr + lonErr * lonErr);
    }

    public double vErr() {
        return altErr;
    }

    public static String qualityName(int q) {
        switch (q) {
            case 0: return "Sem posição";
            case 1: return "Autónomo";
            case 2: return "DGPS";
            case 4: return "FIX";
            case 5: return "FLOAT";
            case 6: return "Estimado";
            default: return "Q" + q;
        }
    }

    public String summary() {
        StringBuilder b = new StringBuilder(qualityName(quality));
        b.append(" · ").append(sats).append(" sat");
        double h = hErr(), v = vErr();
        if (!Double.isNaN(h)) b.append(String.format(Locale.US, " · H %.3f", h));
        if (!Double.isNaN(v)) b.append(String.format(Locale.US, " V %.3f", v));
        return b.toString();
    }
}
