package kr.toon2reels

import android.graphics.*
import kotlin.math.*

/** One painter for preview and export. All coordinates are in a virtual 1080x1920 frame. */
class FrameComposer(private val images: ImageRepository) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val blurred = mutableMapOf<String, Bitmap>()

    fun draw(canvas: Canvas, project: Project, time: Float, width: Int, height: Int) {
        if (project.panels.isEmpty()) return
        val checkpoint = canvas.save()
        canvas.scale(width / 1080f, height / 1920f)
        val frame = Timeline.locate(project, time)
        if (frame.next == null) drawPanel(canvas, project, frame.current)
        else {
            val blend = Timeline.ease(frame.blend)
            when (project.transition) {
                Transition.FADE -> {
                    drawPanel(canvas, project, frame.current)
                    val layer = canvas.saveLayerAlpha(0f, 0f, 1080f, 1920f, (blend * 255).roundToInt())
                    drawPanel(canvas, project, frame.next)
                    canvas.restoreToCount(layer)
                }
                Transition.SLIDE -> {
                    val first = canvas.save(); canvas.translate(-1080f * blend, 0f)
                    drawPanel(canvas, project, frame.current); canvas.restoreToCount(first)
                    val second = canvas.save(); canvas.translate(1080f * (1f-blend), 0f)
                    drawPanel(canvas, project, frame.next); canvas.restoreToCount(second)
                }
                Transition.NONE -> drawPanel(canvas, project, frame.current)
            }
        }
        if (project.showTitle && project.title.isNotBlank()) drawTitle(canvas, project)
        canvas.restoreToCount(checkpoint)
    }

    private fun drawPanel(canvas: Canvas, project: Project, index: Int) {
        val panel = project.panels[index]
        val bitmap = images.load(panel.uri)
        val src = Rect(
            (bitmap.width * panel.crop.left).roundToInt().coerceIn(0, bitmap.width-1),
            (bitmap.height * panel.crop.top).roundToInt().coerceIn(0, bitmap.height-1),
            (bitmap.width * panel.crop.right).roundToInt().coerceIn(1, bitmap.width),
            (bitmap.height * panel.crop.bottom).roundToInt().coerceIn(1, bitmap.height)
        )
        if (src.width() <= 0 || src.height() <= 0) return
        when (project.background) {
            Background.BLACK -> canvas.drawColor(Color.BLACK)
            Background.WHITE -> canvas.drawColor(Color.WHITE)
            Background.CUSTOM -> canvas.drawColor(project.color)
            Background.BLUR -> {
                val key = "${panel.uri}/${panel.crop}"
                val background = blurred.getOrPut(key) { blurBackdrop(bitmap, src) }
                paint.alpha = 255
                canvas.drawBitmap(background, null, Rect(0, 0, 1080, 1920), paint)
                canvas.drawColor(0x33000000)
            }
        }
        // Fit the entire source inside the safe reading area without changing its aspect ratio.
        val fit = min(920f / src.width(), 1210f / src.height())
        val rw = src.width() * fit; val rh = src.height() * fit
        val cx = 540f; val cy = 930f
        paint.alpha = 255
        canvas.drawBitmap(bitmap, src, RectF(cx-rw/2, cy-rh/2, cx+rw/2, cy+rh/2), paint)
    }

    private fun drawTitle(canvas: Canvas, project: Project) {
        val font = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textSize = project.titleSize.toFloat(); color = Color.WHITE
            textAlign = Paint.Align.CENTER
            setShadowLayer(3f, 0f, 2f, Color.BLACK)
        }
        val text = project.title.trim().replace('\n', ' ')
        val lines = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotEmpty() && lines.size < 3) {
            val maxChars = font.breakText(remaining, true, 900f, null).coerceAtLeast(1)
            if (maxChars == remaining.length) { lines += remaining; break }
            val breakAt = remaining.lastIndexOf(' ', maxChars-1).takeIf { it > maxChars/2 } ?: maxChars
            lines += remaining.take(breakAt).trim()
            remaining = remaining.drop(breakAt).trimStart()
        }
        if (lines.isEmpty()) return
        val lineHeight = project.titleSize * 1.25f
        val centerY = if (project.titleBottom) 1650f else 190f
        val boxHeight = max(90f, lineHeight * lines.size + 28f)
        val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA000000.toInt() }
        canvas.drawRoundRect(64f, centerY-boxHeight/2, 1016f, centerY+boxHeight/2, 22f, 22f, box)
        val firstBaseline = centerY - (lines.size-1)*lineHeight/2 - (font.ascent()+font.descent())/2
        lines.forEachIndexed { i, line -> canvas.drawText(line, 540f, firstBaseline + i*lineHeight, font) }
    }

    private fun blurBackdrop(source: Bitmap, crop: Rect): Bitmap {
        val w = 90; val h = 160
        val scaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(scaled)
        val aspect = max(w.toFloat()/crop.width(), h.toFloat()/crop.height())
        val rw = crop.width()*aspect; val rh = crop.height()*aspect
        c.drawBitmap(source, crop, RectF((w-rw)/2, (h-rh)/2, (w+rw)/2, (h+rh)/2), paint)
        val a = IntArray(w*h); val b = IntArray(w*h)
        scaled.getPixels(a, 0, w, 0, 0, w, h)
        repeat(3) {
            for (y in 0 until h) for (x in 0 until w) {
                var r = 0; var g = 0; var blue = 0; var n = 0
                for (offset in -5..5) {
                    val color = a[y*w + (x+offset).coerceIn(0, w-1)]
                    r += (color shr 16) and 255; g += (color shr 8) and 255; blue += color and 255; n++
                }
                b[y*w+x] = Color.rgb(r/n, g/n, blue/n)
            }
            for (y in 0 until h) for (x in 0 until w) {
                var r = 0; var g = 0; var blue = 0; var n = 0
                for (offset in -5..5) {
                    val color = b[(y+offset).coerceIn(0, h-1)*w + x]
                    r += (color shr 16) and 255; g += (color shr 8) and 255; blue += color and 255; n++
                }
                a[y*w+x] = Color.rgb(r/n, g/n, blue/n)
            }
        }
        scaled.setPixels(a, 0, w, 0, 0, w, h)
        return scaled
    }
}
