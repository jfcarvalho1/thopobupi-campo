package pt.jfcarvalho.thopobupi;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.util.ArrayList;
import java.util.List;

/** RGG públicas do BUPi: só desenho, sem toque, sem edição. */
public class RggOverlay extends Overlay {
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private volatile List<List<double[]>> rings = new ArrayList<>();
    private final Point p = new Point();

    public RggOverlay(float density) {
        stroke.setColor(0xFF26C6DA);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2f * density);
    }

    /** Cada anel é uma lista de {lat, lon}. */
    public void setRings(List<List<double[]>> r) {
        this.rings = r;
    }

    @Override
    public void draw(Canvas c, MapView map, boolean shadow) {
        if (shadow) return;
        Projection pj = map.getProjection();
        for (List<double[]> ring : rings) {
            if (ring.size() < 2) continue;
            Path path = new Path();
            boolean first = true;
            for (double[] v : ring) {
                pj.toPixels(new GeoPoint(v[0], v[1]), p);
                if (first) { path.moveTo(p.x, p.y); first = false; }
                else path.lineTo(p.x, p.y);
            }
            path.close();
            c.drawPath(path, stroke);
        }
    }
}
