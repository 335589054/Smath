package com.smath.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 画笔的一笔。
 *
 * 坐标全部是「源图归一化坐标」（相对整张源图，x / y 都在 0..1），
 * 因此先裁剪再涂抹、先涂抹再调裁剪框，两种顺序都不会让笔迹偏离原图位置；
 * 渲染与保存时再按当前裁剪框把框外部分裁掉。
 */
data class BrushStroke(
    val colorArgb: Int,
    /** 相对整张源图宽度的比例（0..1），保存时按实际像素换算，保证不同分辨率下观感一致。 */
    val widthRatio: Float,
    /** 源图归一化坐标点，x/y 均在 0..1。 */
    val points: List<Offset>
)

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

    /**
     * 保存「裁剪 + 手写笔迹」的最终结果。
     *
     * 先按 [crop]（源图归一化坐标）在 2400px 以内的位图上裁出区域，
     * 再把 [strokes]（同样按源图归一化坐标存）映射到裁好的位图上绘制，
     * 最后 JPEG 90 写到 `filesDir/images/edit_<时间戳>.jpg`。
     *
     * 返回新文件绝对路径；任何失败返回 null。源图与输出位图都会在 finally 里回收。
     */
    fun saveEdited(
        context: Context,
        sourcePath: String,
        crop: Rect,
        strokes: List<BrushStroke>
    ): String? {
        val source = loadBitmap(sourcePath) ?: return null
        var output: Bitmap? = null
        return try {
            val safe = safeCropRect(crop)
            val left = (safe.left * source.width).roundToInt().coerceIn(0, source.width - 1)
            val top = (safe.top * source.height).roundToInt().coerceIn(0, source.height - 1)
            val right = (safe.right * source.width).roundToInt().coerceIn(left + 1, source.width)
            val bottom = (safe.bottom * source.height).roundToInt().coerceIn(top + 1, source.height)
            val outW = right - left
            val outH = bottom - top
            val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            output = out

            // 1:1 裁切后再画，避免额外缩放导致笔迹与像素错位。
            val canvas = Canvas(out)
            canvas.drawBitmap(
                source,
                android.graphics.Rect(left, top, right, bottom),
                android.graphics.Rect(0, 0, outW, outH),
                Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = true }
            )
            drawBrushStrokes(canvas, strokes, safe, outW, outH)

            val file = File(imagesDir(context), "edit_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { stream ->
                if (!out.compress(Bitmap.CompressFormat.JPEG, 90, stream)) return null
            }
            file.absolutePath
        } catch (t: Throwable) {
            null
        } finally {
            if (!source.isRecycled) source.recycle()
            output?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    /** 笔迹的归一化坐标 → 裁图上的像素坐标，框外线段由 canvas 自然裁掉。 */
    private fun drawBrushStrokes(
        canvas: Canvas,
        strokes: List<BrushStroke>,
        crop: Rect,
        outW: Int,
        outH: Int
    ) {
        if (strokes.isEmpty() || outW <= 0 || outH <= 0) return
        val cropW = crop.width.coerceAtLeast(0.0001f)
        val cropH = crop.height.coerceAtLeast(0.0001f)
        // 笔迹宽度是「相对源图宽度」的比例：源图宽 = outW / crop.width，故换算系数如下。
        val widthPerRatio = outW.toFloat() / cropW
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        strokes.forEach { stroke ->
            val points = stroke.points
            if (points.isEmpty()) return@forEach
            paint.color = stroke.colorArgb
            val strokeWidth = (stroke.widthRatio * widthPerRatio).coerceAtLeast(1f)
            paint.strokeWidth = strokeWidth
            if (points.size == 1) {
                val p = points.first()
                paint.style = Paint.Style.FILL
                canvas.drawCircle(
                    ((p.x - crop.left) / cropW) * outW,
                    ((p.y - crop.top) / cropH) * outH,
                    strokeWidth / 2f,
                    paint
                )
                paint.style = Paint.Style.STROKE
            } else {
                val path = Path()
                points.forEachIndexed { index, p ->
                    val x = ((p.x - crop.left) / cropW) * outW
                    val y = ((p.y - crop.top) / cropH) * outH
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, paint)
            }
        }
    }

    private fun safeCropRect(crop: Rect): Rect {
        val left = crop.left.coerceIn(0f, 0.999f)
        val top = crop.top.coerceIn(0f, 0.999f)
        val right = crop.right.coerceIn(left + 0.001f, 1f)
        val bottom = crop.bottom.coerceIn(top + 0.001f, 1f)
        return Rect(left, top, right, bottom)
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