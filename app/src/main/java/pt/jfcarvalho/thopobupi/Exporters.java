package pt.jfcarvalho.thopobupi;

import java.util.List;
import java.util.Locale;

/** Exportação TXT (P,N,E,Z,D) e DXF R12. Coordenadas em PT-TM06 (EPSG:3763). */
public final class Exporters {
    private Exporters() {}

    private static String f3(double v) {
        return String.format(Locale.US, "%.3f", v);
    }

    public static String txt(List<Pt> pts) {
        StringBuilder b = new StringBuilder();
        for (Pt p : pts) {
            b.append(p.name).append(',')
                    .append(f3(p.n)).append(',')
                    .append(f3(p.e)).append(',')
                    .append(Double.isNaN(p.z) ? "" : f3(p.z)).append(',')
                    .append(p.desc == null ? "" : p.desc.replace(',', ' '))
                    .append("\r\n");
        }
        return b.toString();
    }

    private static void g(StringBuilder b, int code, String v) {
        b.append(code).append("\r\n").append(v).append("\r\n");
    }

    private static String layer(String code) {
        String l = (code == null || code.isEmpty()) ? "PONTOS" : code;
        return l.replaceAll("[^A-Za-z0-9_\\-]", "_");
    }

    public static String dxf(List<Pt> pts, List<CodeEngine.Line> lines) {
        StringBuilder b = new StringBuilder();
        g(b, 0, "SECTION");
        g(b, 2, "ENTITIES");
        for (Pt p : pts) {
            double z = Double.isNaN(p.z) ? 0 : p.z;
            String ly = layer(p.code());
            g(b, 0, "POINT");
            g(b, 8, ly);
            g(b, 10, f3(p.e)); g(b, 20, f3(p.n)); g(b, 30, f3(z));
            g(b, 0, "TEXT");
            g(b, 8, "NUMEROS");
            g(b, 10, f3(p.e + 0.2)); g(b, 20, f3(p.n + 0.2)); g(b, 30, f3(z));
            g(b, 40, "0.25");
            g(b, 1, p.name);
            if (p.desc != null && !p.desc.trim().isEmpty()) {
                g(b, 0, "TEXT");
                g(b, 8, "DESCRICOES");
                g(b, 10, f3(p.e + 0.2)); g(b, 20, f3(p.n - 0.2)); g(b, 30, f3(z));
                g(b, 40, "0.20");
                g(b, 1, p.desc.trim());
            }
        }
        for (CodeEngine.Line l : lines) {
            g(b, 0, "POLYLINE");
            g(b, 8, layer(l.code));
            g(b, 66, "1");
            g(b, 10, "0.0"); g(b, 20, "0.0"); g(b, 30, "0.0");
            g(b, 70, l.closed ? "1" : "0");
            for (Pt p : l.verts) {
                g(b, 0, "VERTEX");
                g(b, 8, layer(l.code));
                g(b, 10, f3(p.e)); g(b, 20, f3(p.n)); g(b, 30, "0.0");
            }
            g(b, 0, "SEQEND");
            g(b, 8, layer(l.code));
        }
        g(b, 0, "ENDSEC");
        g(b, 0, "EOF");
        return b.toString();
    }
}
