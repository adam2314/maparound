import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Draws the MapAround icon (the launcher icon: app/src/main/res/drawable/ic_launcher_foreground.xml
 * on ic_launcher_background) as an opaque square PNG, for the stores and the iOS app.
 * The adaptive icon's visible 72dp (18..90 of its 108 viewport) fills the square, as on a phone.
 *
 * Usage (JDK 11+): java tools/icon/Icon.java <size> <out.png>
 *   512:  docs/store/icon-512.png (Google Play)
 *   1024: iosApp/MapAround/Assets.xcassets/AppIcon.appiconset/icon-1024.png
 */
public class Icon {
    public static void main(String[] args) throws Exception {
        int size = Integer.parseInt(args[0]);
        // RGB with no alpha: the App Store refuses icons with an alpha channel.
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(new Color(0x1B6B4A));
        g.fillRect(0, 0, size, size);
        double s = size / 72.0;
        g.transform(new AffineTransform(s, 0, 0, s, -18 * s, -18 * s));

        // folded map
        fill(g, 0xE8F5EE, 30, 36, 44, 31, 64, 37, 78, 32, 78, 72, 64, 77, 44, 71, 30, 76);
        fill(g, 0xB9E0CB, 44, 31, 64, 37, 64, 77, 44, 71);
        // compass needle
        fill(g, 0xD32F2F, 54, 38, 59, 54, 49, 54);
        fill(g, 0x37474F, 54, 70, 59, 54, 49, 54);
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(54 - 2.5, 54 - 2.5, 5, 5));
        g.dispose();
        ImageIO.write(img, "png", new File(args[1]));
    }

    private static void fill(Graphics2D g, int rgb, double... xy) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        p.closePath();
        g.setColor(new Color(rgb));
        g.fill(p);
    }
}
