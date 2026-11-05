package hr.gd.androidfilewifi.desktop

import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage

/** Draws the app icon (blue tile, white landscape with a sun) at any size. Used for window, tray and installer. */
object AppIcon {
    fun render(size: Int): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val s = size / 108.0
        g.color = Color(0x1E, 0x6F, 0xD9)
        g.fill(RoundRectangle2D.Double(0.0, 0.0, size.toDouble(), size.toDouble(), 24 * s, 24 * s))
        g.color = Color.WHITE
        val hills = Path2D.Double().apply {
            moveTo(18 * s, 84 * s); lineTo(42 * s, 50 * s); lineTo(58 * s, 70 * s)
            lineTo(68 * s, 57 * s); lineTo(90 * s, 84 * s); closePath()
        }
        g.fill(hills)
        g.fill(Ellipse2D.Double(66 * s, 20 * s, 18 * s, 18 * s))
        g.dispose()
        return img
    }
}
