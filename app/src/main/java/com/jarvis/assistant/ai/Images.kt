package com.jarvis.assistant.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Sohbete eklenen görseller: küçültülüp JPEG olarak telefonda saklanır (az yer kaplasın),
 * yapay zekaya base64 olarak gönderilir.
 */
object Images {
    private const val KEEP = 40

    private fun dir(ctx: Context) = File(ctx.filesDir, "images").apply { mkdirs() }

    fun toJpeg(bmp: Bitmap, maxSide: Int = 1280, quality: Int = 80): ByteArray {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled !== bmp) scaled.recycle()
        return out.toByteArray()
    }

    /** Seçilen/paylaşılan görseli okuyup küçültür ve kaydeder. @return dosya yolu */
    fun importUri(ctx: Context, uri: Uri): String? = runCatching {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val side = maxOf(info.size.width, info.size.height)
            if (side > 2048) dec.setTargetSampleSize(side / 2048)
        }
        save(ctx, toJpeg(bmp, 1600))
    }.getOrNull()

    fun save(ctx: Context, jpeg: ByteArray): String {
        val f = File(dir(ctx), "img_${System.currentTimeMillis()}.jpg")
        f.writeBytes(jpeg)
        prune(ctx)
        return f.absolutePath
    }

    /** Eski görselleri siler; sadece son birkaç tanesi kalır. */
    private fun prune(ctx: Context) {
        val files = dir(ctx).listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(KEEP).forEach { it.delete() }
    }

    fun base64(path: String): String? = runCatching {
        Base64.encodeToString(File(path).readBytes(), Base64.NO_WRAP)
    }.getOrNull()

    /** Sohbet balonunda göstermek için küçük önizleme. */
    fun thumbnail(path: String, size: Int = 360): Bitmap? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= size) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}
