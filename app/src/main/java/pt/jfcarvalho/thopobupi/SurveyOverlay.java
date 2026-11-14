package pt.jfcarvalho.thopobupi;

import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.util.ArrayList;
import java.util.List;

/** Desenha o levantamento (pontos e linhas a vermelho), a posição actual e a linha de implantação. */
public class SurveyOverlay extends Overlay {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint posFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint posRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stake = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private List<Pt> pts = new ArrayList<>();
    private List<CodeEngine.Line> lines = new ArrayList<>();
    private GeoPoint position;
    private int quality;
    private GeoPoint stakeTarget;
    private final Point p1 = new Point(), p2 = new Point();

    public SurveyOverlay(float density) {
        this.density = density;
        line.setColor(0xFFE53935);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.5f * density);
        dot.setColor(0xFFE53935);
        label.setColor(0xFFFFFFFF);
        label.setTextSize(11 * density);
        label.setShadowLayer(2 * density, 0, 0, 0xFF000000);
        posFill.setColor(0xFF1E88E5);
        posRing.setColor(0xFFFFFFFF);
        posRing.setStyle(Paint.Style.STROKE);
        posRing.setStrokeWidth(2 * density);
        stake.setColor(0xFF00E676);
        stake.setStyle(Paint.Style.STROKE);
        stake.setStrokeWidth(2 * density);
        stake.setPathEffect(new DashPathEffect(new float[]{8 * density, 6 * density}, 0));
    }

    public void setData(List<Pt> pts, List<CodeEngine.Line> lines) {
        this.pts = pts;
        this.lines = lines;
    }

    public void setPosition(GeoPoint p, int quality) {
        this.position = p;
        this.quality = quality;
    }

    public void setStakeTarget(GeoPoint t) {
        this.stakeTarget = t;
    }

    @Override
    public void draw(Canvas c, MapView map, boolean shadow) {
        if (shadow) return;
        Projection pj = map.getProjection();
        for (CodeEngine.Line l : lines) {
            Path path = new Path();
            boolean first = true;
            for (Pt p : l.verts) {
                pj.toPixels(new GeoPoint(p.lat, p.lon), p1);
                if (first) { path.moveTo(p1.x, p1.y); first = false; }
                else path.lineTo(p1.x, p1.y);
            }
            if (l.closed) path.close();
            c.drawPath(path, line);
        }
        boolean labels = map.getZoomLevelDouble() >= 19;
        for (Pt p : pts) {
            pj.toPixels(new GeoPoint(p.lat, p.lon), p1);
            c.drawCircle(p1.x, p1.y, 2.5f * density, dot);
            if (labels) c.drawText(p.name, p1.x + 4 * density, p1.y - 4 * density, label);
        }
        if (position != null) {
            pj.toPixels(position, p1);
            if (stakeTarget != null) {
                pj.toPixels(stakeTarget, p2);
                c.drawLine(p1.x, p1.y, p2.x, p2.y, stake);
                c.drawCircle(p2.x, p2.y, 6 * density, stake);
            }
            posFill.setColor(quality == 4 ? 0xFF2E7D32 : quality == 5 ? 0xFFF9A825 : 0xFF1E88E5);
            c.drawCircle(p1.x, p1.y, 6 * density, posFill);
            c.drawCircle(p1.x, p1.y, 6 * density, posRing);
        } else if (stakeTarget != null) {
            pj.toPixels(stakeTarget, p2);
            c.drawCircle(p2.x, p2.y, 6 * density, stake);
        }
    }
}
