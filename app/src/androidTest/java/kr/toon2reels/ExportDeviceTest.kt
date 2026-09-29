package kr.toon2reels

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.app.Application
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Runs only on a device/emulator: verifies actual hardware/platform codec output. */
@RunWith(AndroidJUnit4::class)
class ExportDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun source(): Pair<Uri, List<Crop>> {
        val image = Bitmap.createBitmap(600,800,Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(Color.WHITE)
            val pen = Paint().apply { color = Color.BLACK; strokeWidth = 8f }
            for (x in listOf(12f,300f,588f)) drawLine(x,12f,x,788f,pen)
            for (y in listOf(12f,400f,788f)) drawLine(12f,y,588f,y,pen)
            val colored = Paint().apply { color = Color.rgb(210,110,125) }
            drawCircle(120f,160f,55f,colored)
            drawCircle(460f,620f,75f,colored)
        }
        val file = File(app.cacheDir,"comic-fixture.png")
        FileOutputStream(file).use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        val detected = PanelDetector.detect(image)
        assertEquals(4,detected.size)
        image.recycle()
        return Uri.fromFile(file) to detected
    }
    private fun inspect(file: File, withAudio: Boolean) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val tracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            val video = tracks.first { it.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC }
            assertEquals(1080,video.getInteger(MediaFormat.KEY_WIDTH))
            assertEquals(1920,video.getInteger(MediaFormat.KEY_HEIGHT))
            assertEquals(withAudio, tracks.any { it.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC })
        } finally { extractor.release() }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            assertNotNull("MP4 should decode its first frame", retriever.getFrameAtTime(0))
        } finally { retriever.release() }
    }

    @Test fun detectedComicBlurKoreanTitleAndSilentMp4() {
        val (uri,crops) = source()
        val project = Project(panels=crops.take(2).mapIndexed { i,c -> Panel(i.toLong(),uri,c,.5f,true) },
            background=Background.BLUR, title="한글 제목", showTitle=true)
        val video = VideoExporter(app).export(project,AtomicBoolean(false)) { }
        inspect(video,false)
        val saved = GallerySaver.save(app,video)
        assertTrue(app.contentResolver.openInputStream(saved)!!.use { it.read() >= 0 })
        app.contentResolver.delete(saved,null,null)
        val savedPanels = project.panels.mapIndexed { index, panel -> GallerySaver.savePanel(app, panel, index + 1) }
        try {
            savedPanels.forEachIndexed { index, imageUri ->
                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, imageUri))
                val crop = crops[index]
                assertEquals(kotlin.math.round(600f * (crop.right - crop.left)).toInt(), bitmap.width)
                assertEquals(kotlin.math.round(800f * (crop.bottom - crop.top)).toInt(), bitmap.height)
                bitmap.recycle()
            }
        } finally { savedPanels.forEach { app.contentResolver.delete(it, null, null) } }
        video.delete()
    }

    @Test fun multipleImagesAndRepeatedAudioMp4() {
        val (uri,_) = source()
        val music = File(app.cacheDir,"tone.mp3")
        InstrumentationRegistry.getInstrumentation().context.assets.open("tone.mp3").use { input ->
            music.outputStream().use { input.copyTo(it) }
        }
        val panels = listOf(Panel(1,uri,Crop(0f,0f,.5f,1f),.5f),Panel(2,uri,Crop(0f,0f,1f,.5f),.5f))
        val project = Project(panels=PanelOps.move(panels,0,1),background=Background.WHITE,music=Uri.fromFile(music))
        val video = VideoExporter(app).export(project,AtomicBoolean(false)) { }
        inspect(video,true)
        video.delete()
    }

    @Test fun cancelledExportCanBeRetried() {
        val (uri, _) = source()
        val project = Project(panels = listOf(Panel(1, uri, duration = .5f)))
        try {
            VideoExporter(app).export(project, AtomicBoolean(true)) { }
            fail("Expected cancellation")
        } catch (_: CancelledExport) { }
        val retry = VideoExporter(app).export(project, AtomicBoolean(false)) { }
        inspect(retry, false)
        retry.delete()
    }

    @Test fun importModesAndDetectorFailureAreRecoverable() {
        val (uri, _) = source()
        val model = EditorModel(app.applicationContext as Application)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun awaitPanels(count: Int) {
            repeat(1800) {
                if (!model.busy && model.project.panels.size == count) return
                if (!model.busy && model.note.contains("읽을 수 없어요")) fail("Import failed: ${model.note}")
                Thread.sleep(50)
            }
            fail("Import did not finish: busy=${model.busy}, panels=${model.project.panels.size}, note=${model.note}")
        }
        instrumentation.runOnMainSync { model.importSingle(uri) }
        awaitPanels(4)
        assertTrue(model.project.panels.all { it.detected })
        instrumentation.runOnMainSync { model.importMany(listOf(uri, uri)) }
        awaitPanels(2)
        assertTrue(model.project.panels.none { it.detected })
        val before = model.project.panels.map { it.id }
        instrumentation.runOnMainSync { model.swap(0, 1) }
        assertEquals(before.reversed(), model.project.panels.map { it.id })

        val plain = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val file = File(app.cacheDir, "plain-fixture.png")
        file.outputStream().use { plain.compress(Bitmap.CompressFormat.PNG, 100, it) }
        plain.recycle()
        instrumentation.runOnMainSync { model.importSingle(Uri.fromFile(file)) }
        awaitPanels(1)
        assertFalse(model.project.panels.single().detected)
        instrumentation.runOnMainSync { model.duplicate(0) }
        assertEquals(2, model.project.panels.size)
    }
}
