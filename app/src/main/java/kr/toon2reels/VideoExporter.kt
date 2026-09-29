package kr.toon2reels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.*
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

class CancelledExport : Exception()

/** Surface-input AVC, platform AAC, MP4 muxing. No third-party encoder, network or FFmpeg binary. */
class VideoExporter(private val context: Context) {
    private val images = ImageRepository(context)
    private fun check(cancel: AtomicBoolean) { if (cancel.get()) throw CancelledExport() }

    fun export(project: Project, cancel: AtomicBoolean, progress: (Int) -> Unit): File {
        require(project.panels.isNotEmpty())
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val video = File(dir, "video-${System.nanoTime()}.mp4")
        val audio = File(dir, "audio-${System.nanoTime()}.mp4")
        val pcm = File(dir, "pcm-${System.nanoTime()}.raw")
        val result = File(dir, "toon-${System.currentTimeMillis()}.mp4")
        try {
            renderVideo(project, video, cancel, progress)
            check(cancel)
            if (project.music == null) {
                if (!video.renameTo(result)) video.copyTo(result, overwrite = true)
            } else {
                makeAudio(project.music, project.seconds, project.musicVolume, pcm, audio, cancel) { progress(85 + it * 10 / 100) }
                check(cancel)
                muxTogether(video, audio, result, cancel)
            }
            check(cancel)
            progress(100)
            return result
        } catch (t: Throwable) {
            result.delete()
            throw t
        } finally {
            video.delete(); audio.delete(); pcm.delete()
        }
    }

    private fun renderVideo(project: Project, out: File, cancel: AtomicBoolean, progress: (Int) -> Unit) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1080, 1920).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var egl: EncoderSurface? = null
        var muxer: MediaMuxer? = null
        var muxStarted = false
        var completed = false
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            egl = EncoderSurface(surface)
            encoder.start()
            val painter = FrameComposer(images)
            val frameBitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
            val frameCanvas = Canvas(frameBitmap)
            val total = max(1, ceil(project.seconds * 30).toInt())
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var track = -1
            fun drain(end: Boolean) {
                var emptyTries = 0
                while (true) {
                    val index = encoder.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                    when (index) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!end || ++emptyTries > 300) {
                                if (end) error("비디오 인코더가 종료되지 않았어요")
                                break
                            }
                        }
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer!!.addTrack(encoder.outputFormat); muxer!!.start(); muxStarted = true
                        }
                        else -> if (index >= 0) {
                            val buffer = encoder.getOutputBuffer(index) ?: error("인코더 출력이 비어 있어요")
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0) {
                                check(muxStarted)
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                muxer!!.writeSampleData(track, buffer, info)
                            }
                            val done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoder.releaseOutputBuffer(index, false)
                            if (done) return
                        }
                    }
                }
            }
            try {
                for (i in 0 until total) {
                    check(cancel)
                    painter.draw(frameCanvas, project, i / 30f, 1080, 1920)
                    egl.draw(frameBitmap, i * 1_000_000_000L / 30)
                    drain(false)
                    if (i % 5 == 0) progress(i * 85 / total)
                }
                encoder.signalEndOfInputStream()
                drain(true)
                completed = true
            } finally { frameBitmap.recycle() }
        } finally {
            try { egl?.release() } catch (_: Exception) {}
            try { encoder.stop() } catch (_: Exception) {}
            encoder.release()
            try { if (muxStarted) { if (completed) muxer?.stop() else runCatching { muxer?.stop() } } }
            finally { muxer?.release() }
        }
    }

    private data class PcmFormat(val rate: Int, val channels: Int, val size: Long)
    private fun decodePcm(uri: Uri, maxSeconds: Float, raw: File, cancel: AtomicBoolean): PcmFormat {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("오디오 트랙을 찾을 수 없어요")
            extractor.selectTrack(track)
            val input = extractor.getTrackFormat(track)
            decoder = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(input, null, null, 0); decoder.start()
            var rate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            require(channels in 1..2) { "mono/stereo only" }
            var inputDone = false; var outputDone = false; var size = 0L
            val info = MediaCodec.BufferInfo()
            FileOutputStream(raw).use { file ->
                var stalled = 0
                while (!outputDone) {
                    check(cancel)
                    if (!inputDone) {
                        val index = decoder.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = decoder.getInputBuffer(index)!!
                            val n = extractor.readSampleData(buffer, 0)
                            if (n < 0 || extractor.sampleTime >= maxSeconds * 1_000_000) {
                                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(index, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val index = decoder.dequeueOutputBuffer(info, 10_000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val output = decoder.outputFormat
                        rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        require(channels in 1..2 && encoding == AudioFormat.ENCODING_PCM_16BIT) {
                            "16-bit mono/stereo PCM required"
                        }
                    } else if (index >= 0) {
                        val buffer = decoder.getOutputBuffer(index)!!
                        if (info.size > 0) {
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val data = ByteArray(info.size); buffer.get(data); file.write(data); size += data.size
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(index, false)
                        stalled = 0
                    } else if (++stalled > 500) error("음악 파일을 읽는 시간이 초과됐어요")
                }
            }
            require(size >= channels * 2L) { "empty audio" }
            return PcmFormat(rate, channels, size - size % (channels * 2))
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            decoder?.release(); extractor.release()
        }
    }

    private fun makeAudio(uri: Uri, seconds: Float, volume: Float, raw: File, out: File,
                          cancel: AtomicBoolean, progress: (Int) -> Unit) {
        val source = decodePcm(uri, seconds, raw, cancel)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, source.rate, source.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, if (source.channels == 1) 96_000 else 160_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        var completed = false
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); encoder.start()
            val info = MediaCodec.BufferInfo(); var track = -1
            fun drain(end: Boolean) {
                var empty = 0
                while (true) {
                    val index = encoder.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                    when (index) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!end) break
                            if (++empty > 300) error("오디오 인코더가 종료되지 않았어요")
                        }
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(encoder.outputFormat); muxer.start(); started = true
                        }
                        else -> if (index >= 0) {
                            val buffer = encoder.getOutputBuffer(index)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0) {
                                check(started)
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buffer, info)
                            }
                            val done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoder.releaseOutputBuffer(index, false)
                            if (done) return
                        }
                    }
                }
            }
            val totalFrames = ceil(seconds * source.rate).toLong()
            var frame = 0L
            RandomAccessFile(raw, "r").use { file ->
                while (frame < totalFrames) {
                    check(cancel)
                    val index = encoder.dequeueInputBuffer(10_000)
                    if (index < 0) { drain(false); continue }
                    val buffer = encoder.getInputBuffer(index)!!
                    buffer.clear(); buffer.order(ByteOrder.LITTLE_ENDIAN)
                    val chunk = min(1024L, totalFrames - frame).toInt()
                    for (i in 0 until chunk) {
                        val progressTime = (frame+i).toFloat() / source.rate
                        val gain = volume.coerceIn(0f,1f) * ((seconds-progressTime)/.6f).coerceIn(0f,1f)
                        for (channel in 0 until source.channels) {
                            if (file.filePointer >= source.size) file.seek(0)
                            val low = file.read(); val high = file.read()
                            val sample = ((high shl 8) or low).toShort().toInt()
                            buffer.putShort((sample * gain).roundToInt().coerceIn(-32768, 32767).toShort())
                        }
                    }
                    encoder.queueInputBuffer(index, 0, chunk * source.channels * 2, frame * 1_000_000 / source.rate, 0)
                    frame += chunk
                    drain(false)
                    progress((frame * 100 / totalFrames).toInt())
                }
            }
            var index: Int
            do { index = encoder.dequeueInputBuffer(10_000); if (index < 0) drain(false) } while (index < 0)
            encoder.queueInputBuffer(index, 0, 0, totalFrames * 1_000_000 / source.rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            completed = true
        } finally {
            try { encoder.stop() } catch (_: Exception) {}
            encoder.release()
            try { if (started) { if (completed) muxer.stop() else runCatching { muxer.stop() } } }
            finally { muxer.release() }
        }
    }

    private fun muxTogether(video: File, audio: File, out: File, cancel: AtomicBoolean) {
        val v = MediaExtractor(); val a = MediaExtractor()
        var muxer: MediaMuxer? = null; var started = false; var completed = false
        try {
            v.setDataSource(video.absolutePath); a.setDataSource(audio.absolutePath)
            v.selectTrack(0); a.selectTrack(0)
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vt = muxer.addTrack(v.getTrackFormat(0)); val at = muxer.addTrack(a.getTrackFormat(0))
            muxer.start(); started = true
            val data = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                check(cancel)
                val fromVideo = v.sampleTime >= 0 && (a.sampleTime < 0 || v.sampleTime <= a.sampleTime)
                val extractor = if (fromVideo) v else a
                if (extractor.sampleTime < 0) break
                data.clear()
                val count = extractor.readSampleData(data, 0)
                if (count < 0) break
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, count, extractor.sampleTime, flags)
                data.position(0); data.limit(count)
                muxer.writeSampleData(if (fromVideo) vt else at, data, info)
                extractor.advance()
            }
            completed = true
        } finally {
            v.release(); a.release()
            try { if (started) { if (completed) muxer?.stop() else runCatching { muxer?.stop() } } }
            finally { muxer?.release() }
        }
    }
}

/** EGL window surface bridges a Canvas-rendered bitmap to the hardware H.264 encoder. */
private class EncoderSurface(private val surface: Surface) {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: android.opengl.EGLContext
    private val eglSurface: android.opengl.EGLSurface
    private val program: Int
    private val texture: Int
    private val vertices = ByteBuffer.allocateDirect(4*4*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f, 0f,1f, 1f,-1f, 1f,1f, -1f,1f, 0f,0f, 1f,1f, 1f,0f)); position(0)
    }
    init {
        check(display != EGL14.EGL_NO_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val attributes = intArrayOf(EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
            EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,0x3142,1,EGL14.EGL_NONE)
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        check(EGL14.eglChooseConfig(display,attributes,0,configs,0,1,IntArray(1),0))
        context = EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
        eglSurface = EGL14.eglCreateWindowSurface(display,configs[0],surface,intArrayOf(EGL14.EGL_NONE),0)
        check(EGL14.eglMakeCurrent(display,eglSurface,eglSurface,context))
        val vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 p; attribute vec2 uv; varying vec2 v; void main(){gl_Position=vec4(p,0.,1.);v=uv;}")
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, "precision mediump float; varying vec2 v; uniform sampler2D tex; void main(){gl_FragColor=texture2D(tex,v);}")
        program = GLES20.glCreateProgram(); GLES20.glAttachShader(program,vertex); GLES20.glAttachShader(program,fragment)
        GLES20.glLinkProgram(program); GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
        val ids = IntArray(1); GLES20.glGenTextures(1,ids,0); texture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
    }
    private fun shader(type:Int, src:String):Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id,src); GLES20.glCompileShader(id)
        val status = IntArray(1); GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,status,0)
        check(status[0] != 0) { "OpenGL shader failed: ${GLES20.glGetShaderInfoLog(id)}" }
        return id
    }
    fun draw(bitmap:Bitmap, timeNs:Long) {
        GLES20.glViewport(0,0,1080,1920)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"tex"),0)
        vertices.position(0); val position=GLES20.glGetAttribLocation(program,"p")
        GLES20.glEnableVertexAttribArray(position); GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,16,vertices)
        vertices.position(2); val uv=GLES20.glGetAttribLocation(program,"uv")
        GLES20.glEnableVertexAttribArray(uv); GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,vertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        EGLExt.eglPresentationTimeANDROID(display,eglSurface,timeNs)
        check(EGL14.eglSwapBuffers(display,eglSurface)) { "영상 프레임 저장에 실패했어요" }
    }
    fun release() {
        EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display,eglSurface); EGL14.eglDestroyContext(display,context)
        EGL14.eglTerminate(display); surface.release()
    }
}
