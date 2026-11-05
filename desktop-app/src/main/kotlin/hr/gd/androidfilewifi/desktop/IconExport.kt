package hr.gd.androidfilewifi.desktop

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/** Build-time helper: renders [AppIcon] into a multi-size Windows .ico (PNG-compressed entries). */
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "packaging/icon.ico" })
    out.parentFile?.mkdirs()
    val sizes = listOf(16, 24, 32, 48, 64, 128, 256)
    val pngs = sizes.map { size ->
        ByteArrayOutputStream().also { ImageIO.write(AppIcon.render(size), "png", it) }.toByteArray()
    }
    val header = ByteBuffer.allocate(6 + 16 * sizes.size).order(ByteOrder.LITTLE_ENDIAN)
    header.putShort(0).putShort(1).putShort(sizes.size.toShort())
    var offset = header.capacity()
    sizes.forEachIndexed { i, size ->
        header.put((if (size >= 256) 0 else size).toByte())   // width (0 = 256)
        header.put((if (size >= 256) 0 else size).toByte())   // height
        header.put(0).put(0)                                   // palette, reserved
        header.putShort(1).putShort(32)                        // planes, bits per pixel
        header.putInt(pngs[i].size).putInt(offset)
        offset += pngs[i].size
    }
    out.outputStream().use { o ->
        o.write(header.array())
        pngs.forEach(o::write)
    }
    // also a PNG for places that prefer it
    ImageIO.write(AppIcon.render(512), "png", File(out.parentFile, "icon.png"))
    println("Wrote ${out.absolutePath}")
}
