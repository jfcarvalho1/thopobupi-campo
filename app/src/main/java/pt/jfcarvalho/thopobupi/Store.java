package pt.jfcarvalho.thopobupi;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Base de dados local dos pontos (SQLite). */
public class Store extends SQLiteOpenHelper {

    public Store(Context ctx) {
        super(ctx, "thopobupi.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE pontos (id INTEGER PRIMARY KEY AUTOINCREMENT, job TEXT, nome TEXT, descricao TEXT,"
                + " e REAL, n REAL, z REAL, lat REAL, lon REAL, qual INTEGER, sats INTEGER,"
                + " herr REAL, verr REAL, bastao REAL, tempo INTEGER, obs TEXT)");
        db.execSQL("CREATE INDEX idx_job ON pontos(job)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) { }

    private static Double nn(double v) {
        return Double.isNaN(v) ? null : v;
    }

    public long insert(Pt p) {
        ContentValues v = new ContentValues();
        v.put("job", p.job);
        v.put("nome", p.name);
        v.put("descricao", p.desc);
        v.put("e", p.e);
        v.put("n", p.n);
        v.put("z", nn(p.z));
        v.put("lat", p.lat);
        v.put("lon", p.lon);
        v.put("qual", p.quality);
        v.put("sats", p.sats);
        v.put("herr", nn(p.hErr));
        v.put("verr", nn(p.vErr));
        v.put("bastao", p.pole);
        v.put("tempo", p.time);
        v.put("obs", p.obs);
        p.id = getWritableDatabase().insert("pontos", null, v);
        return p.id;
    }

    public void delete(long id) {
        getWritableDatabase().delete("pontos", "id=?", new String[]{String.valueOf(id)});
    }

    private static double d(Cursor c, int i) {
        return c.isNull(i) ? Double.NaN : c.getDouble(i);
    }

    public List<Pt> list(String job) {
        List<Pt> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,job,nome,descricao,e,n,z,lat,lon,qual,sats,herr,verr,bastao,tempo,obs FROM pontos WHERE job=? ORDER BY id",
                new String[]{job})) {
            while (c.moveToNext()) {
                Pt p = new Pt();
                p.id = c.getLong(0);
                p.job = c.getString(1);
                p.name = c.getString(2);
                p.desc = c.getString(3);
                p.e = c.getDouble(4);
                p.n = c.getDouble(5);
                p.z = d(c, 6);
                p.lat = c.getDouble(7);
                p.lon = c.getDouble(8);
                p.quality = c.getInt(9);
                p.sats = c.getInt(10);
                p.hErr = d(c, 11);
                p.vErr = d(c, 12);
                p.pole = c.getDouble(13);
                p.time = c.getLong(14);
                p.obs = c.isNull(15) ? "" : c.getString(15);
                out.add(p);
            }
        }
        return out;
    }

    public List<String> jobs() {
        List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT job, COUNT(*) FROM pontos GROUP BY job ORDER BY MAX(id) DESC", null)) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }
}
