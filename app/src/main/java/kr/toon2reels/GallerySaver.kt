package kr.toon2reels

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object GallerySaver {
    /** Saves the current crop as a separate PNG, decoding at source resolution when possible. */
    fun savePanel(context: Context, panel: Panel, number: Int): Uri {
        require(panel.crop.valid()) { "Invalid panel crop" }
        val resolver = context.contentResolver
        val image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, panel.uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val crop = panel.crop
            // Limit decoded source memory even when the chosen panel is very narrow.
            val scale = min(1f, 4096f / max(info.size.width, info.size.height))
            val width = max(1, (info.size.width * scale).roundToInt())
            val height = max(1, (info.size.height * scale).roundToInt())
            if (scale < 1f) decoder.setTargetSize(width, height)
            val left = (width * crop.left).roundToInt().coerceIn(0, width - 1)
            val top = (height * crop.top).roundToInt().coerceIn(0, height - 1)
            val right = (width * crop.right).roundToInt().coerceIn(left + 1, width)
            val bottom = (height * crop.bottom).roundToInt().coerceIn(top + 1, height)
            decoder.setCrop(Rect(left, top, right, bottom))
        }
        var target: Uri? = null
        try {
            target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Toon2Reels_${System.currentTimeMillis()}_${number.toString().padStart(2, '0')}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Toon2Reels")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }) ?: error("갤러리 위치를 열 수 없어요")
            resolver.openOutputStream(target)?.use { output ->
                check(image.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG 저장에 실패했어요" }
            } ?: error("갤러리에 쓸 수 없어요")
            resolver.update(target, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return target
        } catch (e: Exception) {
            if (target != null) runCatching { resolver.delete(target, null, null) }
            throw e
        } finally {
            image.recycle()
        }
    }

    fun save(context: Context, file: File): Uri {
        val resolver = context.contentResolver
        var target: Uri? = null
        try {
            target = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Toon2Reels")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }) ?: error("갤러리 위치를 열 수 없어요")
            resolver.openOutputStream(target)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: error("갤러리에 쓸 수 없어요")
            resolver.update(target, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            return target
        } catch (e: Exception) {
            if (target != null) runCatching { resolver.delete(target, null, null) }
            throw e
        }
    }
}
