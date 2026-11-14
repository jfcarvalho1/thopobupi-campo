package pt.jfcarvalho.thopobupi;

/** Ponto do levantamento. */
public class Pt {
    public long id;
    public String job = "";
    public String name = "";
    public String desc = "";
    public double e, n, z = Double.NaN;
    public double lat, lon;
    public int quality;
    public int sats;
    public double hErr = Double.NaN, vErr = Double.NaN;
    public double pole;
    public long time;
    public String obs = "";

    /** Primeira palavra da descrição, em maiúsculas. */
    public String code() {
        String d = desc == null ? "" : desc.trim();
        if (d.isEmpty()) return "";
        int sp = d.indexOf(' ');
        return (sp < 0 ? d : d.substring(0, sp)).toUpperCase(java.util.Locale.ROOT);
    }
}
