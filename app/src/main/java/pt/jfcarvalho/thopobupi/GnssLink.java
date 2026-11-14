package pt.jfcarvalho.thopobupi;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ligação ao receptor GNSS por Bluetooth (perfil série SPP) e cliente NTRIP
 * que envia as correcções RTCM da ReNEP para o receptor.
 */
public class GnssLink {

    public interface Listener {
        void onNmea(String line);
        void onLinkStatus(String bluetooth, String ntrip, boolean connected);
        void onRtcm(long bytesTotal);
    }

    public static class NtripConfig {
        public boolean enabled;
        public String host;
        public int port;
        public String mount;
        public String user;
        public String pass;
    }

    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private volatile int gen;
    private volatile BluetoothSocket bt;
    private volatile OutputStream btOut;
    private volatile Socket ntrip;
    private volatile String lastGga;
    private volatile String btStatus = "Desligado";
    private volatile String ntripStatus = "NTRIP desligado";
    private volatile boolean btConnected;
    private long rtcmTotal;

    public GnssLink(Listener l) {
        this.listener = l;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isConnected() {
        return btConnected;
    }

    private void status() {
        final String b = btStatus, n = ntripStatus;
        final boolean c = btConnected;
        main.post(() -> listener.onLinkStatus(b, n, c));
    }

    public void connect(final BluetoothDevice dev, final NtripConfig cfg) {
        if (running) return;
        running = true;
        final int my = ++gen;
        new Thread(() -> runBluetooth(dev, cfg, my), "bt").start();
    }

    public void disconnect() {
        running = false;
        gen++;
        closeQuiet(ntrip);
        closeQuiet(bt);
        btConnected = false;
        btStatus = "Desligado";
        ntripStatus = "NTRIP desligado";
        status();
    }

    private static void closeQuiet(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (IOException ignored) { }
    }

    @SuppressLint("MissingPermission")
    private BluetoothSocket openSocket(BluetoothDevice dev) throws IOException {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        try { if (ad != null) ad.cancelDiscovery(); } catch (SecurityException ignored) { }
        IOException first;
        try {
            BluetoothSocket s = dev.createRfcommSocketToServiceRecord(SPP);
            s.connect();
            return s;
        } catch (IOException e) {
            first = e;
        }
        try {
            BluetoothSocket s = dev.createInsecureRfcommSocketToServiceRecord(SPP);
            s.connect();
            return s;
        } catch (IOException ignored) { }
        try {
            Method m = dev.getClass().getMethod("createRfcommSocket", int.class);
            BluetoothSocket s = (BluetoothSocket) m.invoke(dev, 1);
            s.connect();
            return s;
        } catch (Exception e) {
            throw first;
        }
    }

    @SuppressLint("MissingPermission")
    private void runBluetooth(BluetoothDevice dev, NtripConfig cfg, int my) {
        while (running && gen == my) {
            btStatus = "A ligar ao receptor…";
            status();
            try {
                bt = openSocket(dev);
                btOut = bt.getOutputStream();
                btConnected = true;
                btStatus = "Receptor ligado";
                status();
                if (cfg != null && cfg.enabled) startNtrip(cfg, my);
                readLoop(bt.getInputStream(), my);
            } catch (IOException | SecurityException e) {
                btStatus = "Falha Bluetooth: " + e.getMessage();
            }
            btConnected = false;
            closeQuiet(bt);
            closeQuiet(ntrip);
            if (!running || gen != my) return;
            btStatus = "Ligação perdida, a repetir…";
            status();
            sleep(3000);
        }
        if (gen == my) {
            btConnected = false;
            status();
        }
    }

    private void readLoop(InputStream in, int my) throws IOException {
        byte[] buf = new byte[2048];
        StringBuilder line = new StringBuilder();
        while (running && gen == my) {
            int n = in.read(buf);
            if (n < 0) throw new IOException("fim de dados");
            for (int i = 0; i < n; i++) {
                char ch = (char) (buf[i] & 0xFF);
                if (ch == '$') {
                    line.setLength(0);
                    line.append(ch);
                } else if (ch == '\n' || ch == '\r') {
                    if (line.length() > 6 && line.charAt(0) == '$') {
                        final String s = line.toString();
                        if (s.length() > 6 && s.substring(3, 6).equals("GGA")) lastGga = s;
                        main.post(() -> listener.onNmea(s));
                    }
                    line.setLength(0);
                } else if (line.length() > 0 && line.length() < 200) {
                    if (ch >= 32 && ch < 127) line.append(ch);
                    else line.setLength(0);
                }
            }
        }
    }

    private void startNtrip(final NtripConfig cfg, final int my) {
        new Thread(() -> {
            while (running && btConnected && gen == my) {
                try {
                    runNtrip(cfg);
                } catch (Exception e) {
                    ntripStatus = "NTRIP: " + e.getMessage();
                    status();
                }
                closeQuiet(ntrip);
                if (!running || !btConnected || gen != my) break;
                sleep(5000);
            }
        }, "ntrip").start();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 4096) break;
        }
        if (c < 0 && b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static String request(NtripConfig cfg, String mount) {
        StringBuilder r = new StringBuilder();
        r.append("GET /").append(mount == null ? "" : mount).append(" HTTP/1.0\r\n");
        r.append("User-Agent: NTRIP ThopoBupiCampo/1.0\r\n");
        r.append("Accept: */*\r\n");
        if (cfg.user != null && !cfg.user.isEmpty()) {
            String auth = cfg.user + ":" + (cfg.pass == null ? "" : cfg.pass);
            r.append("Authorization: Basic ")
                    .append(Base64.encodeToString(auth.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP))
                    .append("\r\n");
        }
        r.append("Connection: close\r\n\r\n");
        return r.toString();
    }

    private void runNtrip(NtripConfig cfg) throws IOException {
        ntripStatus = "NTRIP: a ligar " + cfg.port + "/" + cfg.mount;
        status();
        Socket s = new Socket();
        ntrip = s;
        s.connect(new InetSocketAddress(cfg.host, cfg.port), 10000);
        s.setSoTimeout(30000);
        OutputStream out = s.getOutputStream();
        InputStream in = s.getInputStream();
        out.write(request(cfg, cfg.mount).getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        String first = readLine(in);
        if (first == null) throw new IOException("sem resposta do servidor");
        if (first.contains("401")) throw new IOException("utilizador ou password errados (401)");
        if (first.startsWith("SOURCETABLE")) throw new IOException("mountpoint inexistente: " + cfg.mount);
        if (!first.contains("200")) throw new IOException("resposta: " + first);
        if (first.startsWith("HTTP")) {
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) { /* cabeçalhos */ }
        }
        ntripStatus = "NTRIP ligado " + cfg.port + "/" + cfg.mount;
        status();
        String gga = lastGga;
        if (gga != null) {
            out.write((gga + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }
        long lastSend = System.currentTimeMillis();
        long lastNotify = 0;
        byte[] buf = new byte[4096];
        while (running && btConnected) {
            int n = in.read(buf);
            if (n < 0) throw new IOException("servidor fechou a ligação");
            OutputStream bo = btOut;
            if (bo != null) {
                synchronized (this) {
                    bo.write(buf, 0, n);
                    bo.flush();
                }
            }
            rtcmTotal += n;
            long now = System.currentTimeMillis();
            if (now - lastNotify > 1000) {
                lastNotify = now;
                final long t = rtcmTotal;
                main.post(() -> listener.onRtcm(t));
            }
            if (now - lastSend > 10000 && lastGga != null) {
                out.write((lastGga + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                lastSend = now;
            }
        }
    }

    /** Lê a lista de mountpoints (sourcetable) do caster. Corre fora da thread principal. */
    public static List<String> sourceTable(NtripConfig cfg) throws IOException {
        List<String> out = new ArrayList<>();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(cfg.host, cfg.port), 10000);
            s.setSoTimeout(15000);
            OutputStream o = s.getOutputStream();
            o.write(request(cfg, "").getBytes(StandardCharsets.ISO_8859_1));
            o.flush();
            InputStream in = s.getInputStream();
            String l;
            while ((l = readLine(in)) != null) {
                if (l.startsWith("STR;")) {
                    String[] f = l.split(";");
                    if (f.length > 2) out.add(f[1] + " — " + f[2]);
                    else if (f.length > 1) out.add(f[1]);
                }
                if (l.startsWith("ENDSOURCETABLE")) break;
            }
        }
        return out;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
