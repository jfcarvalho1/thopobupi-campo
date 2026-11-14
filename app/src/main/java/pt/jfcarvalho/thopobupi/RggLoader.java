package pt.jfcarvalho.thopobupi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lê as RGG públicas do BUPi (serviço de dados abertos eBUPi) para a área visível.
 * Só leitura. Guarda uma cópia local para trabalhar sem rede.
 */
public class RggLoader {
    private static final String URL_BASE =
            "https://geo.bupi.gov.pt/gisbupi/rest/services/opendata/RGG_DadosGovPT/MapServer/0/query";
    private static final int MAX_CACHE = 30000;

    /** id -> anéis (cada anel: lista de {lat, lon}) */
    private final Map<String, List<List<double[]>>> cache = new LinkedHashMap<>();
    private final File file;

    public static class Result {
        public List<List<double[]>> rings = new ArrayList<>();
        public int count;
        public boolean offline;
    }

    public RggLoader(File dir) {
        file = new File(dir, "rgg_cache.txt");
        loadCache();
    }

    private synchronized void loadCache() {
        if (!file.exists()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int bar = line.indexOf('|');
                if (bar < 0) continue;
                String id = line.substring(0, bar);
                List<List<double[]>> rings = new ArrayList<>();
                for (String rs : line.substring(bar + 1).split("\\|")) {
                    List<double[]> ring = new ArrayList<>();
                    for (String v : rs.split(";")) {
                        int c = v.indexOf(',');
                        if (c < 0) continue;
                        ring.add(new double[]{Double.parseDouble(v.substring(0, c)), Double.parseDouble(v.substring(c + 1))});
                    }
                    if (ring.size() > 2) rings.add(ring);
                }
                cache.put(id, rings);
            }
        } catch (Exception ignored) { }
    }

    private synchronized void appendCache(Map<String, List<List<double[]>>> fresh) {
        if (cache.size() > MAX_CACHE) return;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, List<List<double[]>>> e : fresh.entrySet()) {
                if (cache.containsKey(e.getKey())) continue;
                cache.put(e.getKey(), e.getValue());
                StringBuilder b = new StringBuilder(e.getKey());
                for (List<double[]> ring : e.getValue()) {
                    b.append('|');
                    boolean first = true;
                    for (double[] v : ring) {
                        if (!first) b.append(';');
                        first = false;
                        b.append(String.format(Locale.US, "%.7f,%.7f", v[0], v[1]));
                    }
                }
                b.append('\n');
                w.write(b.toString());
            }
        } catch (Exception ignored) { }
    }

    /** Corre fora da thread principal. */
    public Result load(double south, double west, double north, double east) {
        Result res = new Result();
        try {
            String q = String.format(Locale.US,
                    "%s?geometry=%.6f,%.6f,%.6f,%.6f&geometryType=esriGeometryEnvelope&inSR=4326"
                            + "&spatialRel=esriSpatialRelIntersects&outFields=&returnGeometry=true&outSR=4326&f=geojson",
                    URL_BASE, west, south, east, north);
            HttpURLConnection c = (HttpURLConnection) new URL(q).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "ThopoBupiCampo/1.0");
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            String body;
            try (InputStream in = c.getInputStream()) {
                body = readAll(in);
            }
            JSONObject root = new JSONObject(body);
            JSONArray feats = root.optJSONArray("features");
            Map<String, List<List<double[]>>> fresh = new LinkedHashMap<>();
            if (feats != null) {
                for (int i = 0; i < feats.length(); i++) {
                    JSONObject f = feats.getJSONObject(i);
                    String id = f.has("id") ? f.get("id").toString() : ("x" + i + "_" + body.hashCode());
                    JSONObject g = f.optJSONObject("geometry");
                    if (g == null) continue;
                    List<List<double[]>> rings = new ArrayList<>();
                    String type = g.optString("type");
                    JSONArray coords = g.optJSONArray("coordinates");
                    if (coords == null) continue;
                    if ("Polygon".equals(type)) addPolygon(coords, rings);
                    else if ("MultiPolygon".equals(type)) {
                        for (int k = 0; k < coords.length(); k++) addPolygon(coords.getJSONArray(k), rings);
                    }
                    fresh.put(id, rings);
                }
            }
            appendCache(fresh);
            for (List<List<double[]>> r : fresh.values()) res.rings.addAll(r);
            res.count = fresh.size();
        } catch (Exception e) {
            res.offline = true;
            synchronized (this) {
                for (List<List<double[]>> rings : cache.values()) {
                    if (rings.isEmpty()) continue;
                    double[] v = rings.get(0).get(0);
                    if (v[0] >= south - 0.01 && v[0] <= north + 0.01 && v[1] >= west - 0.01 && v[1] <= east + 0.01) {
                        res.rings.addAll(rings);
                        res.count++;
                    }
                }
            }
        }
        return res;
    }

    private static void addPolygon(JSONArray poly, List<List<double[]>> out) throws Exception {
        for (int r = 0; r < poly.length(); r++) {
            JSONArray ring = poly.getJSONArray(r);
            List<double[]> pts = new ArrayList<>();
            for (int i = 0; i < ring.length(); i++) {
                JSONArray xy = ring.getJSONArray(i);
                pts.add(new double[]{xy.getDouble(1), xy.getDouble(0)});
            }
            if (pts.size() > 2) out.add(pts);
        }
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) b.append(buf, 0, n);
        }
        return b.toString();
    }
}
