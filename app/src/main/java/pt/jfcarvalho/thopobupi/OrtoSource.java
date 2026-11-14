package pt.jfcarvalho.thopobupi;

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.util.MapTileIndex;

import java.util.Locale;

/** Ortofotomapa OrtoSat 2023 da DGT pelo serviço WMS oficial, pedido em mosaicos EPSG:3857. */
public class OrtoSource extends OnlineTileSourceBase {
    private static final double HALF = 20037508.342789244;
    private static final String BASE = "https://ortos.dgterritorio.gov.pt/wms/ortosat2023";

    public OrtoSource() {
        super("DGT_OrtoSat2023", 6, 21, 256, ".jpg", new String[]{BASE});
    }

    @Override
    public String getTileURLString(long index) {
        int z = MapTileIndex.getZoom(index);
        int x = MapTileIndex.getX(index);
        int y = MapTileIndex.getY(index);
        double size = 2 * HALF / Math.pow(2, z);
        double minx = -HALF + x * size;
        double maxy = HALF - y * size;
        double maxx = minx + size;
        double miny = maxy - size;
        return String.format(Locale.US,
                "%s?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=ortoSat2023-CorVerdadeira&STYLES="
                        + "&CRS=EPSG:3857&BBOX=%.3f,%.3f,%.3f,%.3f&WIDTH=256&HEIGHT=256&FORMAT=image/jpeg",
                BASE, minx, miny, maxx, maxy);
    }
}
