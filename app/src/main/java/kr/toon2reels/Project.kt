package kr.toon2reels

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.content.Context
import android.util.LruCache
import kotlin.math.*

data class Crop(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    fun valid() = left >= 0f && top >= 0f && right <= 1f && bottom <= 1f && right - left >= .05f && bottom - top >= .05f
}
data class Panel(val id: Long, val uri: Uri, val crop: Crop = Crop(), val duration: Float = 2.5f, val detected: Boolean = false)
enum class Background { BLACK, WHITE, CUSTOM, BLUR }
enum class Transition { NONE, FADE, SLIDE }
data class Project(
    val panels: List<Panel> = emptyList(), val title: String = "", val showTitle: Boolean = false,
    val titleBottom: Boolean = false, val titleSize: Int = 56,
    val background: Background = Background.BLACK, val color: Int = 0xFF232630.toInt(),
    val transition: Transition = Transition.FADE,
    val music: Uri? = null, val musicVolume: Float = .7f
) {
    val seconds: Float get() = panels.sumOf { it.duration.toDouble() }.toFloat()
}
object PanelOps {
    fun move(panels: List<Panel>, from: Int, to: Int): List<Panel> {
        if (from !in panels.indices || to !in panels.indices) return panels
        val result = panels.toMutableList(); result.add(to, result.removeAt(from))
        return result
    }
}

/** Shared by real-time preview and export. Each cut owns its full duration; transitions occupy its last 0.35 s. */
data class FramePosition(val current: Int, val next: Int?, val local: Float, val blend: Float)
object Timeline {
    const val TRANSITION_SECONDS = .35f
    fun locate(project: Project, seconds: Float): FramePosition {
        require(project.panels.isNotEmpty())
        var start = 0f
        project.panels.forEachIndexed { i, panel ->
            val end = start + panel.duration
            if (seconds < end || i == project.panels.lastIndex) {
                val local = (seconds - start).coerceIn(0f, panel.duration)
                val transitionLength = min(TRANSITION_SECONDS, panel.duration * .35f)
                val fade = if (project.transition == Transition.NONE || i == project.panels.lastIndex) 0f
                else ((local - (panel.duration - transitionLength)) / transitionLength).coerceIn(0f, 1f)
                return FramePosition(i, if (fade > 0f) i + 1 else null, local, fade)
            }
            start = end
        }
        error("empty timeline")
    }
    fun ease(t: Float): Float = t * t * (3f - 2f * t)
}

/** Decodes with a firm size cap; ImageDecoder applies EXIF orientation before crop coordinates are used. */
class ImageRepository(private val context: Context) {
    private val cache = object : LruCache<String, Bitmap>(64 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    @Synchronized fun load(uri: Uri): Bitmap {
        val key = uri.toString()
        cache.get(key)?.let { return it }
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = min(1f, 3000f / max(info.size.width, info.size.height))
            decoder.setTargetSize(max(1, (info.size.width * scale).roundToInt()), max(1, (info.size.height * scale).roundToInt()))
        }
        cache.put(key, bitmap)
        return bitmap
    }
}

/** Dark-line morphology plus enclosed-component discovery, adapted from the supplied Colab approach. */
object PanelDetector {
    fun detect(source: Bitmap): List<Crop> {
        val scale = min(1f, 1100f / max(source.width, source.height))
        val w = max(1, (source.width * scale).roundToInt()); val h = max(1, (source.height * scale).roundToInt())
        if (w < 120 || h < 120) return emptyList()
        val bitmap = Bitmap.createScaledBitmap(source, w, h, true)
        val pixels = IntArray(w * h); bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        if (bitmap !== source) bitmap.recycle()
        val dark = BooleanArray(pixels.size) { i ->
            val p = pixels[i]
            (((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) / 256 < 105
        }
        return detectGrid(dark, w, h)
    }

    /** Exposed to JVM unit tests: true denotes a dark pixel. */
    fun detectGrid(dark: BooleanArray, w: Int, h: Int): List<Crop> {
        require(dark.size == w * h)
        if (w < 120 || h < 120) return emptyList()
        val grid = BooleanArray(dark.size)
        val minHorizontal = max(18, w / 12); val minVertical = max(18, h / 12)
        for (y in 0 until h) {
            var x = 0
            while (x < w) {
                if (!dark[y * w + x]) { x++; continue }
                val start = x
                while (x < w && dark[y * w + x]) x++
                if (x - start >= minHorizontal) for (k in start until x) grid[y * w + k] = true
            }
        }
        for (x in 0 until w) {
            var y = 0
            while (y < h) {
                if (!dark[y * w + x]) { y++; continue }
                val start = y
                while (y < h && dark[y * w + x]) y++
                if (y - start >= minVertical) for (k in start until y) grid[k * w + x] = true
            }
        }
        // Join one/two pixel breaks in the grid. Surround the page to close edge-touching panels.
        val blocked = BooleanArray(grid.size)
        for (y in 0 until h) for (x in 0 until w) {
            val index = y * w + x
            if (x == 0 || y == 0 || x == w - 1 || y == h - 1) { blocked[index] = true; continue }
            for (dy in -2..2) for (dx in -2..2) if (grid[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]) blocked[index] = true
        }
        val seen = BooleanArray(grid.size); val queue = IntArray(grid.size)
        val candidates = mutableListOf<IntArray>()
        for (start in blocked.indices) {
            if (blocked[start] || seen[start]) continue
            var head = 0; var tail = 0; queue[tail++] = start; seen[start] = true
            var minX = w; var minY = h; var maxX = 0; var maxY = 0
            while (head < tail) {
                val p = queue[head++]; val x = p % w; val y = p / w
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                if (x > 0 && !blocked[p-1] && !seen[p-1]) { seen[p-1] = true; queue[tail++] = p-1 }
                if (x < w-1 && !blocked[p+1] && !seen[p+1]) { seen[p+1] = true; queue[tail++] = p+1 }
                if (y > 0 && !blocked[p-w] && !seen[p-w]) { seen[p-w] = true; queue[tail++] = p-w }
                if (y < h-1 && !blocked[p+w] && !seen[p+w]) { seen[p+w] = true; queue[tail++] = p+w }
            }
            val bw = maxX-minX+1; val bh = maxY-minY+1
            if (bw > w*.12 && bh > h*.06 && bw.toLong()*bh > w.toLong()*h*.012 &&
                !(bw > w*.93 && bh > h*.93) && tail > bw.toLong()*bh*.40) {
                candidates += intArrayOf(minX, minY, maxX+1, maxY+1)
            }
        }
        if (candidates.size < 2 || candidates.size > 40) return emptyList()
        val rows = mutableListOf<MutableList<IntArray>>()
        for (box in candidates.sortedBy { (it[1]+it[3])/2f }) {
            val cy = (box[1]+box[3])/2f
            val row = rows.firstOrNull { r ->
                val mean = r.map { (it[1]+it[3])/2f }.average().toFloat()
                val avgHeight = r.map { it[3]-it[1] }.average().toFloat()
                abs(cy-mean) < avgHeight*.4f
            }
            if (row == null) rows += mutableListOf(box) else row += box
        }
        return rows.sortedBy { r -> r.map { (it[1]+it[3])/2f }.average() }
            .flatMap { it.sortedBy { box -> box[0] } }
            .map { Crop(it[0].toFloat()/w, it[1].toFloat()/h, it[2].toFloat()/w, it[3].toFloat()/h) }
    }
}
