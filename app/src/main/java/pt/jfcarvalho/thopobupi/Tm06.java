package pt.jfcarvalho.thopobupi;

/**
 * ETRS89 geográficas (GRS80) -> ETRS89 / Portugal TM06 (EPSG:3763).
 * Projecção Transversa de Mercator, sem mudança de datum.
 */
public final class Tm06 {
    private static final double A = 6378137.0;
    private static final double F = 1.0 / 298.257222101;
    private static final double E2 = 2 * F - F * F;
    private static final double EP2 = E2 / (1 - E2);
    private static final double K0 = 1.0;
    private static final double LAT0 = Math.toRadians(39.0 + 40.0 / 60 + 5.73 / 3600);
    private static final double LON0 = Math.toRadians(-(8.0 + 7.0 / 60 + 59.19 / 3600));
    private static final double M0 = meridian(LAT0);

    private Tm06() {}

    private static double meridian(double phi) {
        double e4 = E2 * E2, e6 = e4 * E2;
        return A * ((1 - E2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi
                - (3 * E2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * Math.sin(2 * phi)
                + (15 * e4 / 256 + 45 * e6 / 1024) * Math.sin(4 * phi)
                - (35 * e6 / 3072) * Math.sin(6 * phi));
    }

    /** @return {E, N} em metros */
    public static double[] fromLatLon(double latDeg, double lonDeg) {
        double phi = Math.toRadians(latDeg);
        double lam = Math.toRadians(lonDeg);
        double s = Math.sin(phi), c = Math.cos(phi), t = Math.tan(phi);
        double n = A / Math.sqrt(1 - E2 * s * s);
        double tt = t * t;
        double cc = EP2 * c * c;
        double a = (lam - LON0) * c;
        double a2 = a * a, a3 = a2 * a, a4 = a3 * a, a5 = a4 * a, a6 = a5 * a;
        double m = meridian(phi);
        double x = K0 * n * (a + (1 - tt + cc) * a3 / 6
                + (5 - 18 * tt + tt * tt + 72 * cc - 58 * EP2) * a5 / 120);
        double y = K0 * (m - M0 + n * t * (a2 / 2 + (5 - tt + 9 * cc + 4 * cc * cc) * a4 / 24
                + (61 - 58 * tt + tt * tt + 600 * cc - 330 * EP2) * a6 / 720));
        return new double[]{x, y};
    }
}
