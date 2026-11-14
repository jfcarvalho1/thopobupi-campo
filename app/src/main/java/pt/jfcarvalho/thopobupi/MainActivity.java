package pt.jfcarvalho.thopobupi;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.osmdroid.config.Configuration;
import org.osmdroid.events.DelayedMapListener;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements GnssLink.Listener {

    private static final int REQ_PERM = 11;
    private static final int REQ_BT_ON = 12;
    private static final int[] PORTS = {2101, 2106, 2102};
    private static final String DEFAULT_CODES = "Z-MR,Z-EDIF,ct,MRB,L,OBRA,LIMITES,POLILINHA2D,MARCO_LIM,ESTEIO";
    private static final String DEFAULT_LINE_CODES = "L,LIM,LIMITES,POLILINHA2D,MURO,Z-MR,Z-EDIF";

    private SharedPreferences prefs;
    private Store store;
    private GnssLink link;
    private final GnssState gnss = new GnssState();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private MapView map;
    private SurveyOverlay survey;
    private RggOverlay rggOverlay;
    private RggLoader rggLoader;

    private TextView tvJob, tvProject, btnLigar, tvPole, tvState, tvAge, tvImu, tvBat, tvEnz;
    private TextView tvPronto, tvAttrib, btnSeguir, btnMedir, btnMedirPlus;
    private EditText etPonto, etDesc;
    private LinearLayout chipRow;

    private String job, project;
    private double pole;
    private int mapMode;
    private boolean follow;
    private boolean saved = true;
    private List<Pt> pts = new ArrayList<>();
    private List<CodeEngine.Line> lines = new ArrayList<>();
    private Pt stakeTarget;
    private long rtcmBytes;
    private long lastRtcmMillis;
    private String linkText = "";

    // média de épocas (Medir +)
    private boolean averaging;
    private int avgTarget;
    private final List<double[]> avgSamples = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("thopobupi", MODE_PRIVATE);
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE));
        Configuration.getInstance().setUserAgentValue("ThopoBupiCampo/" + getPackageName());
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        store = new Store(this);
        link = new GnssLink(this);
        rggLoader = new RggLoader(getFilesDir());

        bindViews();
        setupMap();
        loadSettings();
        reloadPoints();
        buildChips();
        askPermissions();
        ui.post(ticker);
    }

    private void bindViews() {
        tvJob = findViewById(R.id.tvJob);
        tvProject = findViewById(R.id.tvProject);
        btnLigar = findViewById(R.id.btnLigar);
        tvPole = findViewById(R.id.tvPole);
        tvState = findViewById(R.id.tvState);
        tvAge = findViewById(R.id.tvAge);
        tvImu = findViewById(R.id.tvImu);
        tvBat = findViewById(R.id.tvBat);
        tvEnz = findViewById(R.id.tvEnz);
        tvPronto = findViewById(R.id.tvPronto);
        tvAttrib = findViewById(R.id.tvAttrib);
        btnSeguir = findViewById(R.id.btnSeguir);
        btnMedir = findViewById(R.id.btnMedir);
        btnMedirPlus = findViewById(R.id.btnMedirPlus);
        etPonto = findViewById(R.id.etPonto);
        etDesc = findViewById(R.id.etDesc);
        chipRow = findViewById(R.id.chipRow);

        findViewById(R.id.boxJob).setOnClickListener(v -> jobsDialog());
        findViewById(R.id.tvGnss).setOnClickListener(v -> gnssMenu());
        btnLigar.setOnClickListener(v -> toggleConnect());
        tvPole.setOnClickListener(v -> poleDialog());
        findViewById(R.id.btnTilt).setOnClickListener(v -> info("Inclinação (IMU)",
                "A compensação de inclinação do G970II usa um protocolo próprio do fabricante, que não vem no NMEA normal.\n\n"
                        + "Até termos esse protocolo, mede com o bastão nivelado (bolha ao centro)."));
        findViewById(R.id.btnLaser).setOnClickListener(v -> toast("Laser ainda não disponível nesta versão"));
        findViewById(R.id.btnCamera).setOnClickListener(v -> openCamera());
        findViewById(R.id.btnConfig).setOnClickListener(v -> settingsDialog());
        btnSeguir.setOnClickListener(v -> {
            follow = !follow;
            btnSeguir.setText(follow ? "Seguir ●" : "Seguir ○");
            if (follow) centerOnPosition();
        });
        findViewById(R.id.btnExtent).setOnClickListener(v -> zoomToSurvey());
        findViewById(R.id.btnMapMore).setOnClickListener(v -> mapMenu());
        findViewById(R.id.btnMapa).setOnClickListener(v -> mapLayerDialog());
        findViewById(R.id.btnCenter).setOnClickListener(v -> centerOnPosition());
        findViewById(R.id.btnSearch).setOnClickListener(v -> codeSearchDialog());
        findViewById(R.id.btnAddCode).setOnClickListener(v -> addCurrentCode());
        btnMedir.setOnClickListener(v -> measure(false));
        btnMedirPlus.setOnClickListener(v -> measure(true));
        findViewById(R.id.navMedir).setOnClickListener(v -> {
            if (stakeTarget != null) stopStake();
        });
        findViewById(R.id.navImplantar).setOnClickListener(v -> stakeDialog());
        findViewById(R.id.navCogo).setOnClickListener(v -> cogoDialog());
        findViewById(R.id.navOpcoes).setOnClickListener(v -> optionsMenu());
        etDesc.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                measure(false);
                return true;
            }
            return false;
        });
    }

    // ------------------------------------------------------------------ mapa

    private void setupMap() {
        map = findViewById(R.id.map);
        map.setTileSource(new OrtoSource());
        map.setMultiTouchControls(true);
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        map.setMinZoomLevel(6.0);
        map.setMaxZoomLevel(22.0);
        map.setTilesScaledToDpi(true);
        float d = getResources().getDisplayMetrics().density;
        rggOverlay = new RggOverlay(d);
        survey = new SurveyOverlay(d);
        map.getOverlays().add(rggOverlay);
        map.getOverlays().add(survey);
        double lat = prefs.getFloat("map_lat", 41.444f);
        double lon = prefs.getFloat("map_lon", -8.295f);
        double z = prefs.getFloat("map_zoom", 16f);
        map.getController().setZoom(z);
        map.getController().setCenter(new GeoPoint(lat, lon));
        map.addMapListener(new DelayedMapListener(new MapListener() {
            @Override
            public boolean onScroll(ScrollEvent event) {
                refreshRgg();
                return false;
            }

            @Override
            public boolean onZoom(ZoomEvent event) {
                refreshRgg();
                return false;
            }
        }, 700));
    }

    private void applyMapMode() {
        map.getOverlayManager().getTilesOverlay().setEnabled(mapMode != 0);
        if (mapMode != 2) {
            rggOverlay.setRings(new ArrayList<>());
        }
        String[] names = {"Mapa desligado", "DGT · OrtoSat 2023", "DGT · OrtoSat 2023 | BUPi · RGG públicas"};
        tvAttrib.setText(names[mapMode]);
        map.invalidate();
        refreshRgg();
    }

    private void refreshRgg() {
        if (mapMode != 2) return;
        if (map.getZoomLevelDouble() < 15) {
            rggOverlay.setRings(new ArrayList<>());
            tvAttrib.setText("Aproxima o mapa para ver as RGG do BUPi");
            map.invalidate();
            return;
        }
        final BoundingBox bb = map.getBoundingBox();
        bg.execute(() -> {
            final RggLoader.Result r = rggLoader.load(bb.getLatSouth(), bb.getLonWest(), bb.getLatNorth(), bb.getLonEast());
            ui.post(() -> {
                if (mapMode != 2) return;
                rggOverlay.setRings(r.rings);
                tvAttrib.setText("BUPi · " + r.count + " RGG públicas" + (r.offline ? " (sem rede, cópia local)" : "")
                        + "\nDGT · OrtoSat 2023 | BUPi · RGG públicas");
                map.invalidate();
            });
        });
    }

    private void mapLayerDialog() {
        String[] items = {"Desligado", "Ortofotomapa DGT", "Ortofotomapa DGT + RGG BUPi"};
        new AlertDialog.Builder(this).setTitle("Mapa")
                .setSingleChoiceItems(items, mapMode, (dlg, which) -> {
                    mapMode = which;
                    prefs.edit().putInt("map_mode", mapMode).apply();
                    applyMapMode();
                    dlg.dismiss();
                }).show();
    }

    private void mapMenu() {
        String[] items = {"Ver todo o levantamento", "Centrar na posição", "Zona de trabalho: Guimarães",
                "Zona de trabalho: Fafe", "Zona de trabalho: Cabeceiras de Basto", "Zona de trabalho: Celorico de Basto",
                "Zona de trabalho: Mondim de Basto"};
        final double[][] zones = {{41.4425, -8.2918}, {41.4502, -8.1704}, {41.5139, -7.9887}, {41.3874, -8.0010}, {41.4135, -7.9534}};
        new AlertDialog.Builder(this).setItems(items, (d, w) -> {
            if (w == 0) zoomToSurvey();
            else if (w == 1) centerOnPosition();
            else {
                double[] z = zones[w - 2];
                map.getController().setZoom(15.0);
                map.getController().setCenter(new GeoPoint(z[0], z[1]));
            }
        }).show();
    }

    private void centerOnPosition() {
        if (gnss.hasPosition()) {
            map.getController().animateTo(new GeoPoint(gnss.lat, gnss.lon));
        } else {
            toast("Sem posição GNSS");
        }
    }

    private void zoomToSurvey() {
        if (pts.isEmpty()) {
            toast("Ainda não há pontos neste trabalho");
            return;
        }
        double n = -90, s = 90, e = -180, w = 180;
        for (Pt p : pts) {
            n = Math.max(n, p.lat); s = Math.min(s, p.lat);
            e = Math.max(e, p.lon); w = Math.min(w, p.lon);
        }
        if (pts.size() == 1 || (n - s) < 1e-5) {
            map.getController().setZoom(19.0);
            map.getController().setCenter(new GeoPoint(pts.get(0).lat, pts.get(0).lon));
            return;
        }
        map.zoomToBoundingBox(new BoundingBox(n, e, s, w), true, 60);
    }

    // ---------------------------------------------------------------- definições

    private void loadSettings() {
        job = prefs.getString("job", "Trabalho 1");
        project = prefs.getString("project", "bupinovo");
        pole = prefs.getFloat("pole", 2.0f);
        mapMode = prefs.getInt("map_mode", 2);
        tvJob.setText(job);
        tvProject.setText(project);
        tvPole.setText(String.format(Locale.US, "%.2f m", pole));
        applyMapMode();
    }

    private int currentPort() {
        return prefs.getInt("port", 2106);
    }

    private String mountFor(int port) {
        String def = port == 2106 ? "NSR5" : port == 2102 ? "BRAG" : "";
        return prefs.getString("mount_" + port, def);
    }

    private GnssLink.NtripConfig ntripConfig() {
        GnssLink.NtripConfig c = new GnssLink.NtripConfig();
        c.enabled = prefs.getBoolean("ntrip_on", true);
        c.host = prefs.getString("host", "193.137.94.71");
        c.port = currentPort();
        c.mount = mountFor(c.port);
        c.user = prefs.getString("user", "");
        c.pass = prefs.getString("pass", "");
        return c;
    }

    private double tolerance() {
        return prefs.getFloat("tol", 0.05f);
    }

    private Set<String> lineCodes() {
        return CodeEngine.parseList(prefs.getString("line_codes", DEFAULT_LINE_CODES));
    }

    private EditText field(LinearLayout parent, String label, String value, int inputType) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        t.setPadding(0, dp(10), 0, 0);
        parent.addView(t);
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(inputType);
        e.setSingleLine(inputType != (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        parent.addView(e);
        return e;
    }

    private void settingsDialog() {
        ScrollView sv = new ScrollView(this);
        final LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(8), dp(20), dp(8));
        sv.addView(l);

        TextView h = new TextView(this);
        h.setText("ReNEP / NTRIP");
        h.setTextSize(16);
        h.setTextColor(0xFF1B6B4F);
        l.addView(h);

        final CheckBox cbOn = new CheckBox(this);
        cbOn.setText("Usar correcções NTRIP ao ligar");
        cbOn.setChecked(prefs.getBoolean("ntrip_on", true));
        l.addView(cbOn);

        final EditText eHost = field(l, "Servidor (caster)", prefs.getString("host", "193.137.94.71"), InputType.TYPE_CLASS_TEXT);

        TextView pl = new TextView(this);
        pl.setText("Porta");
        pl.setTextSize(13);
        pl.setPadding(0, dp(10), 0, 0);
        l.addView(pl);
        final RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        final int[] mountHolder = {currentPort()};
        for (int port : PORTS) {
            RadioButton rb = new RadioButton(this);
            rb.setText(String.valueOf(port));
            rb.setId(port);
            rg.addView(rb);
        }
        rg.check(currentPort());
        l.addView(rg);
        TextView ph = new TextView(this);
        ph.setText("2101 estação manual (Braga) · 2106 a habitual · 2102 pontual");
        ph.setTextSize(12);
        l.addView(ph);

        final EditText eMount = field(l, "Mountpoint desta porta", mountFor(currentPort()), InputType.TYPE_CLASS_TEXT);
        final EditText eUser = field(l, "Utilizador ReNEP", prefs.getString("user", ""), InputType.TYPE_CLASS_TEXT);
        final EditText ePass = field(l, "Password ReNEP", prefs.getString("pass", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        final String[] mounts = new String[PORTS.length];
        for (int i = 0; i < PORTS.length; i++) mounts[i] = mountFor(PORTS[i]);
        rg.setOnCheckedChangeListener((group, checkedId) -> {
            for (int i = 0; i < PORTS.length; i++) if (PORTS[i] == mountHolder[0]) mounts[i] = eMount.getText().toString().trim();
            mountHolder[0] = checkedId;
            for (int i = 0; i < PORTS.length; i++) if (PORTS[i] == checkedId) eMount.setText(mounts[i]);
        });

        TextView bList = new TextView(this);
        bList.setText("Obter lista de mountpoints do servidor");
        bList.setTextColor(0xFF1B6B4F);
        bList.setPadding(0, dp(10), 0, dp(4));
        bList.setOnClickListener(v -> {
            final GnssLink.NtripConfig c = new GnssLink.NtripConfig();
            c.host = eHost.getText().toString().trim();
            c.port = rg.getCheckedRadioButtonId();
            c.user = eUser.getText().toString().trim();
            c.pass = ePass.getText().toString();
            toast("A pedir a lista à porta " + c.port + "…");
            bg.execute(() -> {
                try {
                    final List<String> list = GnssLink.sourceTable(c);
                    ui.post(() -> {
                        if (list.isEmpty()) { toast("O servidor não devolveu mountpoints"); return; }
                        final String[] arr = list.toArray(new String[0]);
                        new AlertDialog.Builder(this).setTitle("Mountpoints " + c.port)
                                .setItems(arr, (d, w) -> {
                                    String m = arr[w];
                                    int sep = m.indexOf(" — ");
                                    eMount.setText(sep > 0 ? m.substring(0, sep) : m);
                                }).show();
                    });
                } catch (Exception ex) {
                    ui.post(() -> toast("Falhou: " + ex.getMessage()));
                }
            });
        });
        l.addView(bList);

        TextView h2 = new TextView(this);
        h2.setText("Levantamento");
        h2.setTextSize(16);
        h2.setTextColor(0xFF1B6B4F);
        h2.setPadding(0, dp(16), 0, 0);
        l.addView(h2);

        final EditText eTol = field(l, "Tolerância do erro total (m)", String.valueOf(tolerance()),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        final EditText eEp = field(l, "Épocas para Medir + (média)", String.valueOf(prefs.getInt("epochs", 5)),
                InputType.TYPE_CLASS_NUMBER);
        final EditText eCodes = field(l, "Códigos (separados por vírgulas)", prefs.getString("codes", DEFAULT_CODES),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        final EditText eLine = field(l, "Códigos que fazem linha", prefs.getString("line_codes", DEFAULT_LINE_CODES),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);

        TextView rx = new TextView(this);
        rx.setText("Receptor: " + prefs.getString("bt_name", "nenhum escolhido") + "  (mudar)");
        rx.setTextColor(0xFF1B6B4F);
        rx.setPadding(0, dp(14), 0, dp(10));
        rx.setOnClickListener(v -> chooseReceiver(null));
        l.addView(rx);

        new AlertDialog.Builder(this).setTitle("Configurar").setView(sv)
                .setPositiveButton("Guardar", (d, w) -> {
                    int port = rg.getCheckedRadioButtonId();
                    for (int i = 0; i < PORTS.length; i++) if (PORTS[i] == port) mounts[i] = eMount.getText().toString().trim();
                    SharedPreferences.Editor ed = prefs.edit();
                    ed.putBoolean("ntrip_on", cbOn.isChecked());
                    ed.putString("host", eHost.getText().toString().trim());
                    ed.putInt("port", port);
                    for (int i = 0; i < PORTS.length; i++) ed.putString("mount_" + PORTS[i], mounts[i]);
                    ed.putString("user", eUser.getText().toString().trim());
                    ed.putString("pass", ePass.getText().toString());
                    ed.putFloat("tol", (float) parseD(eTol.getText().toString(), 0.05));
                    ed.putInt("epochs", (int) Math.max(2, parseD(eEp.getText().toString(), 5)));
                    ed.putString("codes", eCodes.getText().toString());
                    ed.putString("line_codes", eLine.getText().toString());
                    ed.apply();
                    buildChips();
                    reloadPoints();
                    toast(link.isRunning() ? "Guardado. Desliga e volta a ligar para aplicar a porta NTRIP." : "Guardado");
                })
                .setNegativeButton("Cancelar", null).show();
    }

    private static double parseD(String s, double def) {
        try { return Double.parseDouble(s.trim().replace(',', '.')); } catch (Exception e) { return def; }
    }

    private void poleDialog() {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(String.format(Locale.US, "%.3f", pole));
        e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle("Altura do bastão (m)").setView(e)
                .setPositiveButton("OK", (d, w) -> {
                    pole = parseD(e.getText().toString(), pole);
                    prefs.edit().putFloat("pole", (float) pole).apply();
                    tvPole.setText(String.format(Locale.US, "%.2f m", pole));
                    updateGnssViews();
                })
                .setNegativeButton("Cancelar", null).show();
    }

    // ---------------------------------------------------------------- trabalhos

    private void jobsDialog() {
        final List<String> jobs = store.jobs();
        if (!jobs.contains(job)) jobs.add(0, job);
        final List<String> items = new ArrayList<>(jobs);
        items.add("+ Novo trabalho");
        new AlertDialog.Builder(this).setTitle("Trabalhos")
                .setItems(items.toArray(new String[0]), (d, w) -> {
                    if (w == items.size() - 1) newJobDialog();
                    else switchJob(items.get(w), project);
                }).show();
    }

    private void newJobDialog() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), 0, dp(20), 0);
        final EditText eJob = field(l, "Nome do trabalho", "", InputType.TYPE_CLASS_TEXT);
        final EditText eProj = field(l, "Projecto", project, InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this).setTitle("Novo trabalho").setView(l)
                .setPositiveButton("Criar", (d, w) -> {
                    String j = eJob.getText().toString().trim();
                    if (j.isEmpty()) { toast("Falta o nome"); return; }
                    switchJob(j, eProj.getText().toString().trim());
                    etPonto.setText("1");
                })
                .setNegativeButton("Cancelar", null).show();
    }

    private void switchJob(String j, String p) {
        job = j;
        project = p;
        prefs.edit().putString("job", job).putString("project", project).apply();
        tvJob.setText(job);
        tvProject.setText(project);
        stopStake();
        reloadPoints();
        zoomToSurvey();
    }

    private void reloadPoints() {
        pts = store.list(job);
        lines = CodeEngine.build(pts, lineCodes());
        survey.setData(pts, lines);
        if (!pts.isEmpty()) etPonto.setText(nextName(pts.get(pts.size() - 1).name));
        map.invalidate();
    }

    private static String nextName(String name) {
        int i = name.length();
        while (i > 0 && Character.isDigit(name.charAt(i - 1))) i--;
        if (i == name.length()) return name + "1";
        String prefix = name.substring(0, i);
        String digits = name.substring(i);
        try {
            long n = Long.parseLong(digits) + 1;
            String s = String.valueOf(n);
            while (s.length() < digits.length()) s = "0" + s;
            return prefix + s;
        } catch (NumberFormatException e) {
            return name + "1";
        }
    }

    private Pt findPoint(String name) {
        for (Pt p : pts) if (p.name.equalsIgnoreCase(name.trim())) return p;
        return null;
    }

    // ---------------------------------------------------------------- códigos

    private List<String> codes() {
        List<String> out = new ArrayList<>();
        for (String s : prefs.getString("codes", DEFAULT_CODES).split("[,;\\n]+")) {
            String t = s.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    private void buildChips() {
        chipRow.removeAllViews();
        List<String> cs = codes();
        for (int i = 0; i < cs.size(); i++) {
            final String code = cs.get(i);
            TextView t = new TextView(this);
            t.setText(i < 5 ? code : code);
            t.setTextSize(15);
            t.setTextColor(0xFF1C1B1F);
            t.setGravity(android.view.Gravity.CENTER);
            t.setBackgroundResource(R.drawable.bg_chip);
            t.setPadding(dp(16), 0, dp(16), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
            lp.setMarginEnd(dp(6));
            t.setLayoutParams(lp);
            t.setOnClickListener(v -> chooseCode(code));
            t.setOnLongClickListener(v -> {
                lineCommandDialog(code);
                return true;
            });
            chipRow.addView(t);
        }
        highlightChip();
    }

    private void highlightChip() {
        String cur = etDesc.getText().toString().trim();
        String first = cur.contains(" ") ? cur.substring(0, cur.indexOf(' ')) : cur;
        for (int i = 0; i < chipRow.getChildCount(); i++) {
            TextView t = (TextView) chipRow.getChildAt(i);
            boolean on = t.getText().toString().equalsIgnoreCase(first) && !first.isEmpty();
            t.setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip);
        }
    }

    private void chooseCode(String code) {
        String cur = etDesc.getText().toString().trim();
        if (cur.equalsIgnoreCase(code)) {
            etDesc.setText(code + " ST");
        } else if (cur.equalsIgnoreCase(code + " ST")) {
            etDesc.setText(code);
        } else {
            etDesc.setText(code);
        }
        etDesc.setSelection(etDesc.getText().length());
        highlightChip();
    }

    private void lineCommandDialog(final String code) {
        String[] items = {code + " ST  (início de linha)", code + " END  (fim de linha)",
                code + " CLO  (fechar ao 1.º ponto)", code};
        final String[] vals = {code + " ST", code + " END", code + " CLO", code};
        new AlertDialog.Builder(this).setTitle(code).setItems(items, (d, w) -> {
            etDesc.setText(vals[w]);
            etDesc.setSelection(etDesc.getText().length());
            highlightChip();
        }).show();
    }

    private void codeSearchDialog() {
        final List<String> all = codes();
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText q = new EditText(this);
        q.setHint("Procurar código");
        q.setSingleLine(true);
        l.addView(q);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        l.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Códigos").setView(l)
                .setNegativeButton("Fechar", null).create();
        final Runnable fill = () -> {
            list.removeAllViews();
            String f = q.getText().toString().trim().toUpperCase(Locale.ROOT);
            for (final String c : all) {
                if (!f.isEmpty() && !c.toUpperCase(Locale.ROOT).contains(f)) continue;
                TextView t = new TextView(this);
                t.setText(c);
                t.setTextSize(17);
                t.setPadding(dp(4), dp(10), dp(4), dp(10));
                t.setOnClickListener(v -> {
                    chooseCode(c);
                    dlg.dismiss();
                });
                t.setOnLongClickListener(v -> {
                    dlg.dismiss();
                    lineCommandDialog(c);
                    return true;
                });
                list.addView(t);
            }
        };
        q.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { fill.run(); }
            public void afterTextChanged(android.text.Editable s) { }
        });
        fill.run();
        dlg.show();
    }

    private void addCurrentCode() {
        String d = etDesc.getText().toString().trim();
        if (d.isEmpty()) { toast("Escreve primeiro o código na descrição"); return; }
        String code = d.contains(" ") ? d.substring(0, d.indexOf(' ')) : d;
        List<String> cs = codes();
        for (String c : cs) if (c.equalsIgnoreCase(code)) { toast(code + " já está na lista"); return; }
        cs.add(code);
        prefs.edit().putString("codes", android.text.TextUtils.join(",", cs)).apply();
        buildChips();
        toast(code + " adicionado aos códigos");
    }

    // ---------------------------------------------------------------- GNSS

    private void askPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN}, REQ_PERM);
            }
        }
    }

    private boolean hasBtPermission() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void toggleConnect() {
        if (link.isRunning()) {
            link.disconnect();
            averaging = false;
            btnLigar.setText("Ligar");
            btnLigar.setBackgroundResource(R.drawable.bg_pill);
            btnLigar.setTextColor(0xFF1C1B1F);
            return;
        }
        if (!hasBtPermission()) {
            askPermissions();
            toast("Autoriza o Bluetooth e carrega outra vez em Ligar");
            return;
        }
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) { toast("Este aparelho não tem Bluetooth"); return; }
        if (!ad.isEnabled()) {
            enableBluetooth(ad);
            return;
        }
        String addr = prefs.getString("bt_addr", null);
        if (addr == null) {
            chooseReceiver(() -> toggleConnect());
            return;
        }
        BluetoothDevice dev;
        try {
            dev = ad.getRemoteDevice(addr);
        } catch (IllegalArgumentException e) {
            chooseReceiver(() -> toggleConnect());
            return;
        }
        GnssLink.NtripConfig cfg = ntripConfig();
        if (cfg.enabled && (cfg.user.isEmpty() || cfg.mount.isEmpty())) {
            toast("NTRIP sem utilizador ou mountpoint: liga só o receptor. Preenche em Configurar.");
            cfg.enabled = false;
        }
        rtcmBytes = 0;
        link.connect(dev, cfg);
        btnLigar.setText("Desligar");
        btnLigar.setBackgroundResource(R.drawable.bg_pill_on);
        btnLigar.setTextColor(0xFFFFFFFF);
    }

    @SuppressLint("MissingPermission")
    private void enableBluetooth(BluetoothAdapter ad) {
        try {
            if (Build.VERSION.SDK_INT < 33 && ad.enable()) {
                toast("A activar o Bluetooth…");
                ui.postDelayed(this::toggleConnect, 2500);
                return;
            }
        } catch (SecurityException ignored) { }
        try {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT_ON);
        } catch (Exception e) {
            toast("Activa o Bluetooth nas definições");
        }
    }

    @SuppressLint("MissingPermission")
    private void chooseReceiver(final Runnable after) {
        if (!hasBtPermission()) { askPermissions(); return; }
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) { toast("Sem Bluetooth"); return; }
        final List<BluetoothDevice> devs = new ArrayList<>();
        try {
            devs.addAll(ad.getBondedDevices());
        } catch (SecurityException e) {
            toast("Sem autorização de Bluetooth");
            return;
        }
        if (devs.isEmpty()) {
            info("Receptor", "Não há aparelhos emparelhados.\n\nEmparelha primeiro o G970II nas Definições → Bluetooth do Android "
                    + "(PIN 1234 ou 0000) e volta aqui.");
            return;
        }
        String[] names = new String[devs.size()];
        for (int i = 0; i < devs.size(); i++) {
            String n;
            try { n = devs.get(i).getName(); } catch (SecurityException e) { n = null; }
            names[i] = (n == null ? "(sem nome)" : n) + "\n" + devs.get(i).getAddress();
        }
        new AlertDialog.Builder(this).setTitle("Escolhe o receptor GNSS").setItems(names, (d, w) -> {
            BluetoothDevice dv = devs.get(w);
            String n;
            try { n = dv.getName(); } catch (SecurityException e) { n = dv.getAddress(); }
            prefs.edit().putString("bt_addr", dv.getAddress()).putString("bt_name", n == null ? dv.getAddress() : n).apply();
            if (after != null) after.run();
        }).show();
    }

    private void gnssMenu() {
        String[] items = {"Estado detalhado", "Escolher receptor", "Configurar ReNEP / NTRIP"};
        new AlertDialog.Builder(this).setTitle("GNSS").setItems(items, (d, w) -> {
            if (w == 0) gnssDetails();
            else if (w == 1) chooseReceiver(null);
            else settingsDialog();
        }).show();
    }

    private void gnssDetails() {
        StringBuilder b = new StringBuilder();
        b.append("Receptor: ").append(prefs.getString("bt_name", "—")).append('\n');
        b.append(linkText).append("\n\n");
        b.append("Solução: ").append(GnssState.qualityName(gnss.quality)).append('\n');
        b.append("Satélites usados: ").append(gnss.sats).append('\n');
        if (gnss.satsInView > 0) b.append("Satélites em vista: ").append(gnss.satsInView).append('\n');
        if (!Double.isNaN(gnss.hdop)) b.append(String.format(Locale.US, "HDOP: %.1f%n", gnss.hdop));
        if (!Double.isNaN(gnss.hErr())) b.append(String.format(Locale.US, "Precisão H: %.3f m%n", gnss.hErr()));
        else b.append("Precisão H: o receptor não envia GST\n");
        if (!Double.isNaN(gnss.vErr())) b.append(String.format(Locale.US, "Precisão V: %.3f m%n", gnss.vErr()));
        if (!Double.isNaN(gnss.age)) b.append(String.format(Locale.US, "Idade RTK: %.1f s%n", gnss.age));
        b.append("Correcções recebidas: ").append(rtcmBytes / 1024).append(" kB\n");
        if (gnss.hasPosition()) {
            b.append(String.format(Locale.US, "%nLat %.8f%nLon %.8f%nAltitude antena %.3f m%n", gnss.lat, gnss.lon, gnss.alt));
        }
        b.append(String.format(Locale.US, "Bastão %.3f m%n", pole));
        info("Estado GNSS", b.toString());
    }

    @Override
    public void onNmea(String line) {
        String type = Nmea.parse(line, gnss, System.currentTimeMillis());
        if (!"GGA".equals(type)) return;
        if (averaging) collectSample();
        updateGnssViews();
        if (gnss.hasPosition()) {
            GeoPoint gp = new GeoPoint(gnss.lat, gnss.lon);
            survey.setPosition(gp, gnss.quality);
            if (follow) map.getController().setCenter(gp);
            map.invalidate();
        }
    }

    @Override
    public void onLinkStatus(String bluetooth, String ntrip, boolean connected) {
        linkText = bluetooth + "\n" + ntrip;
        tvPronto.setText(connected ? ntrip : bluetooth);
        if (!link.isRunning()) {
            btnLigar.setText("Ligar");
            btnLigar.setBackgroundResource(R.drawable.bg_pill);
            btnLigar.setTextColor(0xFF1C1B1F);
        }
        updateGnssViews();
    }

    @Override
    public void onRtcm(long bytesTotal) {
        rtcmBytes = bytesTotal;
        lastRtcmMillis = System.currentTimeMillis();
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateGnssViews();
            ui.postDelayed(this, 1000);
        }
    };

    private boolean fresh() {
        return System.currentTimeMillis() - gnss.lastGgaMillis < 3000;
    }

    private void updateGnssViews() {
        int red = 0xFFB3261E, green = 0xFF1B6B4F, amber = 0xFFB26A00;
        if (!link.isRunning()) {
            tvState.setText("Liga o GNSS");
            tvState.setTextColor(red);
            tvAge.setText("Idade RTK: —");
            tvAge.setTextColor(red);
            tvEnz.setText("E — · N — · Z —");
        } else if (!link.isConnected()) {
            tvState.setText("A ligar ao receptor…");
            tvState.setTextColor(amber);
        } else if (!fresh()) {
            tvState.setText("Sem dados NMEA do receptor");
            tvState.setTextColor(red);
            tvEnz.setText("E — · N — · Z —");
        } else {
            tvState.setText(gnss.summary());
            tvState.setTextColor(gnss.quality == 4 ? green : gnss.quality == 5 ? amber : red);
            if (!Double.isNaN(gnss.age)) {
                tvAge.setText(String.format(Locale.US, "Idade RTK: %.1f s", gnss.age));
                tvAge.setTextColor(gnss.age < 5 ? green : amber);
            } else {
                boolean rx = System.currentTimeMillis() - lastRtcmMillis < 5000;
                tvAge.setText(rx ? "Idade RTK: a receber correcções" : "Idade RTK: —");
                tvAge.setTextColor(rx ? amber : red);
            }
            if (gnss.hasPosition()) {
                double[] en = Tm06.fromLatLon(gnss.lat, gnss.lon);
                double z = gnss.alt - pole;
                tvEnz.setText(String.format(Locale.US, "E %.3f · N %.3f · Z %s", en[0], en[1],
                        Double.isNaN(z) ? "—" : String.format(Locale.US, "%.3f", z)));
                if (stakeTarget != null) {
                    double de = stakeTarget.e - en[0], dn = stakeTarget.n - en[1];
                    double dist = Math.hypot(de, dn);
                    tvPronto.setText(String.format(Locale.US, "Implantar %s: %.3f m%nΔE %+.3f  ΔN %+.3f",
                            stakeTarget.name, dist, de, dn));
                }
            } else {
                tvEnz.setText("E — · N — · Z —");
            }
        }
        tvBat.setText("Bat. — · " + (saved ? "Guardado ✓" : "Por guardar"));
    }

    // ---------------------------------------------------------------- medir

    private void measure(boolean plus) {
        if (averaging) { toast("Já estou a medir…"); return; }
        if (!link.isConnected() || !fresh() || !gnss.hasPosition()) {
            toast("Sem posição GNSS. Carrega em Ligar.");
            return;
        }
        String name = etPonto.getText().toString().trim();
        if (name.isEmpty()) { toast("Falta o número do ponto"); return; }
        if (findPoint(name) != null) {
            toast("O ponto " + name + " já existe neste trabalho");
            return;
        }
        avgSamples.clear();
        if (plus) {
            avgTarget = prefs.getInt("epochs", 5);
            averaging = true;
            btnMedirPlus.setText("0/" + avgTarget);
        } else {
            avgTarget = 1;
            collectSample();
            finishMeasure();
        }
    }

    private void collectSample() {
        if (!gnss.hasPosition()) return;
        avgSamples.add(new double[]{gnss.lat, gnss.lon, gnss.alt, gnss.quality, gnss.sats, gnss.hErr(), gnss.vErr()});
        if (averaging) {
            btnMedirPlus.setText(avgSamples.size() + "/" + avgTarget);
            if (avgSamples.size() >= avgTarget) {
                averaging = false;
                btnMedirPlus.setText("Medir +");
                finishMeasure();
            }
        }
    }

    private void finishMeasure() {
        if (avgSamples.isEmpty()) return;
        double lat = 0, lon = 0, alt = 0, h = 0, v = 0;
        int worstQ = 4, sats = 99, nAlt = 0;
        boolean hNaN = false, vNaN = false;
        for (double[] s : avgSamples) {
            lat += s[0];
            lon += s[1];
            if (!Double.isNaN(s[2])) { alt += s[2]; nAlt++; }
            int q = (int) s[3];
            if (q != 4) worstQ = q;
            sats = Math.min(sats, (int) s[4]);
            if (Double.isNaN(s[5])) hNaN = true; else h = Math.max(h, s[5]);
            if (Double.isNaN(s[6])) vNaN = true; else v = Math.max(v, s[6]);
        }
        int n = avgSamples.size();
        final Pt p = new Pt();
        p.job = job;
        p.name = etPonto.getText().toString().trim();
        p.desc = etDesc.getText().toString().trim();
        p.lat = lat / n;
        p.lon = lon / n;
        double[] en = Tm06.fromLatLon(p.lat, p.lon);
        p.e = en[0];
        p.n = en[1];
        p.z = nAlt == n ? alt / n - pole : Double.NaN;
        p.quality = worstQ;
        p.sats = sats;
        p.hErr = hNaN ? Double.NaN : h;
        p.vErr = vNaN ? Double.NaN : v;
        p.pole = pole;
        p.time = System.currentTimeMillis();
        if (n > 1) p.obs = "média " + n + " épocas";

        if (Double.isNaN(p.z)) {
            info("Ponto não gravado", "O receptor não deu cota válida. Não gravo pontos sem Z.");
            return;
        }
        double tol = tolerance();
        double total = (Double.isNaN(p.hErr) || Double.isNaN(p.vErr)) ? Double.NaN : Math.hypot(p.hErr, p.vErr);
        StringBuilder msg = new StringBuilder();
        boolean ok = p.quality == 4 && !Double.isNaN(total) && total <= tol;
        msg.append(String.format(Locale.US, "Ponto %s · %s%n", p.name, p.desc.isEmpty() ? "(sem descrição)" : p.desc));
        msg.append(String.format(Locale.US, "E %.3f  N %.3f  Z %.3f%n%n", p.e, p.n, p.z));
        msg.append("Solução: ").append(GnssState.qualityName(p.quality)).append('\n');
        if (Double.isNaN(total)) {
            msg.append("Erro total: desconhecido (o receptor não envia GST)\n");
        } else {
            msg.append(String.format(Locale.US, "Erro total: %.1f cm (H %.1f · V %.1f) — limite %.1f cm%n",
                    total * 100, p.hErr * 100, p.vErr * 100, tol * 100));
        }
        if (!ok) {
            msg.append("\n⚠ FORA DA TOLERÂNCIA. Gravar mesmo assim?");
            p.obs = (p.obs.isEmpty() ? "" : p.obs + "; ") + "FORA DA TOLERÂNCIA";
        } else {
            msg.append("\nDentro da tolerância. Gravar?");
        }
        new AlertDialog.Builder(this).setTitle(ok ? "Gravar ponto" : "Atenção").setMessage(msg.toString())
                .setPositiveButton("Sim", (d, w) -> savePoint(p))
                .setNegativeButton("Não", null).show();
    }

    private void savePoint(Pt p) {
        saved = false;
        updateGnssViews();
        store.insert(p);
        saved = true;
        reloadPoints();
        etPonto.setText(nextName(p.name));
        String d = p.desc;
        if (d.toUpperCase(Locale.ROOT).endsWith(" ST")) {
            etDesc.setText(d.substring(0, d.length() - 3));
        } else if (d.toUpperCase(Locale.ROOT).endsWith(" END") || d.toUpperCase(Locale.ROOT).endsWith(" CLO")) {
            etDesc.setText(d.substring(0, d.lastIndexOf(' ')));
        }
        etDesc.setSelection(etDesc.getText().length());
        highlightChip();
        tvPronto.setText("Ponto " + p.name + " gravado");
        updateGnssViews();
    }

    private void deleteLastDialog() {
        if (pts.isEmpty()) { toast("Não há pontos"); return; }
        final Pt last = pts.get(pts.size() - 1);
        new AlertDialog.Builder(this).setTitle("Apagar ponto")
                .setMessage("Apagar o último ponto, " + last.name + " (" + last.desc + ")?")
                .setPositiveButton("Apagar", (d, w) -> {
                    store.delete(last.id);
                    reloadPoints();
                    etPonto.setText(last.name);
                })
                .setNegativeButton("Cancelar", null).show();
    }

    private void pointsList() {
        if (pts.isEmpty()) { toast("Ainda não há pontos"); return; }
        StringBuilder b = new StringBuilder();
        for (int i = pts.size() - 1; i >= 0; i--) {
            Pt p = pts.get(i);
            b.append(String.format(Locale.US, "%s  %s%n  E %.3f N %.3f Z %s  %s%s%n",
                    p.name, p.desc, p.e, p.n, Double.isNaN(p.z) ? "—" : String.format(Locale.US, "%.3f", p.z),
                    GnssState.qualityName(p.quality), p.obs.isEmpty() ? "" : "  [" + p.obs + "]"));
        }
        info("Pontos (" + pts.size() + ")", b.toString());
    }

    // ---------------------------------------------------------------- implantar / COGO

    private void stakeDialog() {
        if (stakeTarget != null) {
            new AlertDialog.Builder(this).setTitle("Implantação")
                    .setMessage("A implantar o ponto " + stakeTarget.name + ".")
                    .setPositiveButton("Outro ponto", (d, w) -> { stopStake(); stakeDialog(); })
                    .setNegativeButton("Parar", (d, w) -> stopStake()).show();
            return;
        }
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), 0, dp(20), 0);
        final EditText eName = field(l, "Ponto existente (número)", "", InputType.TYPE_CLASS_TEXT);
        final EditText eE = field(l, "ou coordenada E (TM06)", "", InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        final EditText eN = field(l, "e coordenada N (TM06)", "", InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        new AlertDialog.Builder(this).setTitle("Implantar").setView(l)
                .setPositiveButton("Implantar", (d, w) -> {
                    Pt t = findPoint(eName.getText().toString());
                    if (t == null) {
                        double e = parseD(eE.getText().toString(), Double.NaN);
                        double n = parseD(eN.getText().toString(), Double.NaN);
                        if (Double.isNaN(e) || Double.isNaN(n)) { toast("Ponto não encontrado"); return; }
                        t = new Pt();
                        t.name = "E/N";
                        t.e = e;
                        t.n = n;
                        double[] ll = inverseTm06(e, n);
                        t.lat = ll[0];
                        t.lon = ll[1];
                    }
                    stakeTarget = t;
                    survey.setStakeTarget(new GeoPoint(t.lat, t.lon));
                    findViewById(R.id.navImplantarIcon).setBackgroundResource(R.drawable.bg_nav_on);
                    findViewById(R.id.navMedirIcon).setBackgroundResource(0);
                    map.invalidate();
                    updateGnssViews();
                })
                .setNegativeButton("Cancelar", null).show();
    }

    private void stopStake() {
        stakeTarget = null;
        survey.setStakeTarget(null);
        findViewById(R.id.navImplantarIcon).setBackgroundResource(0);
        findViewById(R.id.navMedirIcon).setBackgroundResource(R.drawable.bg_nav_on);
        tvPronto.setText("Pronto");
        map.invalidate();
    }

    /** Inversa numérica da TM06 (iterativa), suficiente para desenhar no mapa. */
    private static double[] inverseTm06(double e, double n) {
        double lat = 39.668, lon = -8.133;
        for (int i = 0; i < 20; i++) {
            double[] f = Tm06.fromLatLon(lat, lon);
            double[] fl = Tm06.fromLatLon(lat + 1e-5, lon);
            double[] fo = Tm06.fromLatLon(lat, lon + 1e-5);
            double a = (fl[0] - f[0]) / 1e-5, b = (fo[0] - f[0]) / 1e-5;
            double c = (fl[1] - f[1]) / 1e-5, d = (fo[1] - f[1]) / 1e-5;
            double det = a * d - b * c;
            double re = e - f[0], rn = n - f[1];
            lat += (d * re - b * rn) / det;
            lon += (-c * re + a * rn) / det;
        }
        return new double[]{lat, lon};
    }

    private void cogoDialog() {
        String[] items = {"Distância e rumo entre dois pontos", "Áreas e perímetros das linhas fechadas", "Lista de pontos"};
        new AlertDialog.Builder(this).setTitle("COGO").setItems(items, (d, w) -> {
            if (w == 0) inverseDialog();
            else if (w == 1) areasDialog();
            else pointsList();
        }).show();
    }

    private void inverseDialog() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), 0, dp(20), 0);
        final EditText a = field(l, "Do ponto", "", InputType.TYPE_CLASS_TEXT);
        final EditText b = field(l, "Ao ponto", "", InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this).setTitle("Distância e rumo").setView(l)
                .setPositiveButton("Calcular", (d, w) -> {
                    Pt pa = findPoint(a.getText().toString()), pb = findPoint(b.getText().toString());
                    if (pa == null || pb == null) { toast("Ponto não encontrado"); return; }
                    double dh = CodeEngine.dist(pa, pb);
                    double dz = (Double.isNaN(pa.z) || Double.isNaN(pb.z)) ? Double.NaN : pb.z - pa.z;
                    double az = CodeEngine.azimuthDeg(pa.e, pa.n, pb.e, pb.n);
                    info(pa.name + " → " + pb.name, String.format(Locale.US,
                            "Distância horizontal: %.3f m%nDesnível: %s%nDistância inclinada: %s%nRumo: %.4f° (%.4f gon)%nΔE %+.3f  ΔN %+.3f",
                            dh, Double.isNaN(dz) ? "—" : String.format(Locale.US, "%+.3f m", dz),
                            Double.isNaN(dz) ? "—" : String.format(Locale.US, "%.3f m", Math.hypot(dh, dz)),
                            az, az * 400 / 360, pb.e - pa.e, pb.n - pa.n));
                })
                .setNegativeButton("Cancelar", null).show();
    }

    private void areasDialog() {
        StringBuilder b = new StringBuilder();
        for (CodeEngine.Line l : lines) {
            String first = l.verts.get(0).name, last = l.verts.get(l.verts.size() - 1).name;
            if (l.closed) {
                b.append(String.format(Locale.US, "%s (%s…%s): área %.2f m² · perímetro %.2f m%n",
                        l.code, first, last, CodeEngine.area(l), CodeEngine.length(l)));
            } else {
                b.append(String.format(Locale.US, "%s (%s…%s): aberta · comprimento %.2f m%n",
                        l.code, first, last, CodeEngine.length(l)));
            }
        }
        if (b.length() == 0) b.append("Ainda não há linhas. Usa um código de linha com ST e termina com CLO para fechar.");
        info("Linhas", b.toString());
    }

    // ---------------------------------------------------------------- opções / exportar

    private void optionsMenu() {
        String[] items = {"Exportar pontos TXT (P,N,E,Z,D)", "Exportar DXF (pontos e linhas)", "Exportar TXT e DXF",
                "Lista de pontos", "Apagar último ponto", "Trabalhos", "Configurar", "Teclas da P9IV", "Sobre"};
        new AlertDialog.Builder(this).setTitle("Opções").setItems(items, (d, w) -> {
            switch (w) {
                case 0: export(true, false); break;
                case 1: export(false, true); break;
                case 2: export(true, true); break;
                case 3: pointsList(); break;
                case 4: deleteLastDialog(); break;
                case 5: jobsDialog(); break;
                case 6: settingsDialog(); break;
                case 7: info("Teclas", "F1 a F5: primeiros cinco códigos da barra\nEnter: Medir\n* : exportar TXT e DXF\n"
                        + "Voltar: fecha janelas / Não"); break;
                default: info("ThopoBupi Campo", "Versão " + BuildConfig.VERSION_NAME + "\nCoordenadas ETRS89 / PT-TM06 (EPSG:3763)\n"
                        + "Ortofotomapa: DGT OrtoSat 2023 (WMS oficial)\nRGG: dados abertos eBUPi, só para ver\n"
                        + "Código aberto, gratuito.");
            }
        }).show();
    }

    private String safeJob() {
        return job.replaceAll("[^A-Za-z0-9_\\-]+", "_");
    }

    private void export(boolean txt, boolean dxf) {
        if (pts.isEmpty()) { toast("Não há pontos para exportar"); return; }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
        StringBuilder done = new StringBuilder();
        try {
            if (txt) done.append(writeExport(safeJob() + "_" + stamp + ".txt", "text/plain", Exporters.txt(pts))).append('\n');
            if (dxf) done.append(writeExport(safeJob() + "_" + stamp + ".dxf", "application/octet-stream",
                    Exporters.dxf(pts, lines))).append('\n');
            info("Exportado", "Ficheiros gravados em:\n" + done);
        } catch (Exception e) {
            info("Erro a exportar", String.valueOf(e.getMessage()));
        }
    }

    private String writeExport(String name, String mime, String content) throws Exception {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver r = getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ThopoBupi");
            Uri uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("não consegui criar " + name);
            try (OutputStream os = r.openOutputStream(uri)) {
                if (os == null) throw new Exception("sem acesso a " + name);
                os.write(data);
            }
            return "Transferências/ThopoBupi/" + name;
        } else {
            File dir = new File(getExternalFilesDir(null), "ThopoBupi");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("não consegui criar a pasta");
            File f = new File(dir, name);
            try (FileOutputStream os = new FileOutputStream(f)) {
                os.write(data);
            }
            return f.getAbsolutePath();
        }
    }

    // ---------------------------------------------------------------- câmara

    private void openCamera() {
        try {
            startActivity(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
        } catch (ActivityNotFoundException e) {
            toast("Não encontrei a app da câmara");
        }
    }

    // ---------------------------------------------------------------- teclas físicas

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int k = event.getKeyCode();
            if (k >= KeyEvent.KEYCODE_F1 && k <= KeyEvent.KEYCODE_F5) {
                int idx = k - KeyEvent.KEYCODE_F1;
                if (idx < chipRow.getChildCount()) {
                    chooseCode(((TextView) chipRow.getChildAt(idx)).getText().toString());
                }
                return true;
            }
            if (k == KeyEvent.KEYCODE_STAR || k == KeyEvent.KEYCODE_NUMPAD_MULTIPLY) {
                if (!(getCurrentFocus() instanceof EditText)) {
                    export(true, true);
                    return true;
                }
            }
            if ((k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_DPAD_CENTER)
                    && getCurrentFocus() != etDesc) {
                if (getCurrentFocus() == etPonto) {
                    etDesc.requestFocus();
                    return true;
                }
                measure(false);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ---------------------------------------------------------------- utilitários

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void info(String title, String msg) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_BT_ON && resultCode == RESULT_OK) toggleConnect();
    }

    @Override
    protected void onResume() {
        super.onResume();
        map.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        map.onPause();
        org.osmdroid.api.IGeoPoint c = map.getMapCenter();
        prefs.edit().putFloat("map_lat", (float) c.getLatitude()).putFloat("map_lon", (float) c.getLongitude())
                .putFloat("map_zoom", (float) map.getZoomLevelDouble()).apply();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(ticker);
        link.disconnect();
        bg.shutdownNow();
        super.onDestroy();
    }
}
