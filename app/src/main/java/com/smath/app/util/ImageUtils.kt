package com.smath.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.compose.ui.geometry.Rect
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

object ImageUtils {
    fun imagesDir(context: Context): File {
        val dir = File(context.filesDir, "images")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun cameraFile(context: Context): File {
        val dir = File(context.cacheDir, "camera")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "photo_${System.currentTimeMillis()}.jpg")
    }

    /** 相机返回后 Activity 可能被重建，cameraPath 丢失时用它找回刚拍的照片。 */
    fun latestCameraFile(context: Context, notBefore: Long = 0L): File? {
        val dir = File(context.cacheDir, "camera")
        return dir.listFiles()
            ?.filter { it.isFile && it.length() > 0 && it.lastModified() >= notBefore }
            ?.maxByOrNull { it.lastModified() }
    }

    fun copyToCache(context: Context, uri: Uri): String? {
        return try {
            val dir = File(context.cacheDir, "picked")
            if (!dir.exists()) dir.mkdirs()
            val out = File(dir, "pick_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            } ?: return null
            out.absolutePath
        } catch (t: Throwable) {
            null
        }
    }

    fun loadBitmap(path: String, maxDimension: Int = 2400): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                val source = android.graphics.ImageDecoder.createSource(File(path))
                android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val scale = minOf(1f, maxDimension.toFloat() / max(w, h))
                    decoder.setTargetSize((w * scale).roundToInt(), (h * scale).roundToInt())
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                var w = bounds.outWidth
                var h = bounds.outHeight
                while (max(w, h) / sample > maxDimension) sample *= 2
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bmp = BitmapFactory.decodeFile(path, options) ?: return null
                rotateForExif(path, bmp)
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun rotateForExif(path: String, bitmap: Bitmap): Bitmap {
        return try {
            val exif = ExifInterface(path)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees == 0f) bitmap else {
                val matrix = Matrix().apply { postRotate(degrees) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) bitmap.recycle()
                rotated
            }
        } catch (_: Throwable) {
            bitmap
        }
    }

    fun cropToImageDir(context: Context, bitmap: Bitmap, crop: Rect): Bitmap {
        val left = (crop.left.coerceIn(0f, 1f) * bitmap.width).roundToInt()
        val top = (crop.top.coerceIn(0f, 1f) * bitmap.height).roundToInt()
        val right = (crop.right.coerceIn(0f, 1f) * bitmap.width).roundToInt()
        val bottom = (crop.bottom.coerceIn(0f, 1f) * bitmap.height).roundToInt()
        val w = (right - left).coerceAtLeast(1)
        val h = (bottom - top).coerceAtLeast(1)
        val safeLeft = left.coerceIn(0, bitmap.width - 1)
        val safeTop = top.coerceIn(0, bitmap.height - 1)
        val safeW = w.coerceAtMost(bitmap.width - safeLeft)
        val safeH = h.coerceAtMost(bitmap.height - safeTop)
        val cropped = Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeW, safeH)
        val scaled = scaleDown(cropped, 1800)
        return if (scaled !== cropped) {
            cropped.recycle()
            scaled
        } else scaled
    }

    fun saveToImages(context: Context, bitmap: Bitmap): String? {
        return try {
            val file = File(imagesDir(context), "img_${UUID.randomUUID()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            if (!bitmap.isRecycled) bitmap.recycle()
            file.absolutePath
        } catch (t: Throwable) {
            if (!bitmap.isRecycled) bitmap.recycle()
            null
        }
    }

    fun saveCropped(context: Context, sourcePath: String, crop: Rect): String? {
        val bitmap = loadBitmap(sourcePath) ?: return null
        return try {
            val cropped = cropToImageDir(context, bitmap, crop)
            saveToImages(context, cropped)
        } catch (t: Throwable) {
            if (!bitmap.isRecycled) bitmap.recycle()
            null
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (max(w, h) <= maxDim) return bitmap
        val scale = maxDim.toFloat() / max(w, h)
        return Bitmap.createScaledBitmap(bitmap, (w * scale).roundToInt(), (h * scale).roundToInt(), true)
    }

    fun toDataUrl(path: String, maxDimension: Int = 1600): String? {
        return try {
            val loaded = loadBitmap(path, maxDimension) ?: return null
            val scaled = scaleDown(loaded, maxDimension)
            val output = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, output)
            if (scaled !== loaded) loaded.recycle()
            scaled.recycle()
            val base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            "data:image/jpeg;base64,$base64"
        } catch (t: Throwable) {
            null
        }
    }
}