package id.komiktrans

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

data class Block(var rect: Rect, var original: String, var translated: String = "")

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
}

enum class Lang(val label: String, val mlkit: String, val gtx: String) {
    JA("Jepang", "ja", "ja"),
    KO("Korea", "ko", "ko"),
    ZH("Mandarin", "zh", "zh-CN"),
    EN("Inggris", "en", "en")
}

object Pipeline {

    // ---------- 1. OCR ----------
    suspend fun ocr(bmp: Bitmap, lang: Lang): List<Block> {
        val rec: TextRecognizer = when (lang) {
            Lang.JA -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            Lang.KO -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            Lang.ZH -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            Lang.EN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
        val joiner = if (lang == Lang.JA || lang == Lang.ZH) "" else " "
        val res = rec.process(InputImage.fromBitmap(bmp, 0)).await()
        rec.close()
        return res.textBlocks.mapNotNull { b ->
            val r = b.boundingBox ?: return@mapNotNull null
            val t = b.text.split("\n").joinToString(joiner).trim()
            if (t.isBlank()) null else Block(Rect(r), t)
        }.sortedBy { it.rect.top }
    }

    // ---------- 2. Terjemahan ----------
    suspend fun translateOffline(texts: List<String>, src: Lang): List<String> {
        val opts = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.fromLanguageTag(src.mlkit)!!)
            .setTargetLanguage(TranslateLanguage.INDONESIAN)
            .build()
        val t = Translation.getClient(opts)
        try {
            t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            return texts.map { t.translate(it).await() }
        } finally {
            t.close()
        }
    }

    // Endpoint gratis tanpa API key (tidak resmi, bisa berubah/dibatasi sewaktu-waktu)
    suspend fun translateOnline(texts: List<String>, src: Lang): List<String> =
        withContext(Dispatchers.IO) {
            texts.map { s ->
                val q = URLEncoder.encode(s, "UTF-8")
                val url = URL("https://translate.googleapis.com/translate_a/single" +
                        "?client=gtx&sl=${src.gtx}&tl=id&dt=t&q=$q")
                val c = url.openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 15000
                val body = c.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONArray(body).getJSONArray(0)
                buildString { for (i in 0 until arr.length()) append(arr.getJSONArray(i).getString(0)) }
            }
        }

    // ---------- 3. Hapus teks (magic eraser) ----------
    private fun toRgb(src: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        return rgb
    }

    private fun inpaint(src: Bitmap, mask: Mat): Bitmap {
        val rgb = toRgb(src)
        val out = Mat()
        Photo.inpaint(rgb, mask, out, 3.0, Photo.INPAINT_TELEA)
        Imgproc.cvtColor(out, out, Imgproc.COLOR_RGB2RGBA)
        val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(out, res)
        return res
    }

    /** Hapus semua teks di dalam kotak-kotak hasil OCR: mask huruf dicari dengan Otsu, lalu di-inpaint. */
    fun eraseRects(src: Bitmap, rects: List<Rect>): Bitmap {
        val gray = Mat()
        Imgproc.cvtColor(toRgb(src), gray, Imgproc.COLOR_RGB2GRAY)
        val mask = Mat.zeros(gray.size(), CvType.CV_8UC1)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        for (r in rects) {
            val x = max(0, r.left - 6)
            val y = max(0, r.top - 6)
            val w = min(gray.cols() - x, r.width() + 12)
            val h = min(gray.rows() - y, r.height() + 12)
            if (w <= 0 || h <= 0) continue
            val roi = org.opencv.core.Rect(x, y, w, h)
            val bin = Mat()
            Imgproc.threshold(gray.submat(roi), bin, 0.0, 255.0,
                Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            // huruf = kelompok piksel yang lebih sedikit
            if (Core.countNonZero(bin) > w * h / 2) Core.bitwise_not(bin, bin)
            Imgproc.dilate(bin, bin, kernel)
            Core.bitwise_or(mask.submat(roi), bin, mask.submat(roi))
        }
        return inpaint(src, mask)
    }

    /** Hapus area yang dicoret dengan kuas (mask ALPHA_8 seukuran gambar). */
    fun eraseMask(src: Bitmap, a8: Bitmap): Bitmap {
        val tmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(tmp).drawBitmap(a8, 0f, 0f, Paint().apply { color = Color.WHITE })
        val m = Mat()
        Utils.bitmapToMat(tmp, m)
        tmp.recycle()
        val a = Mat()
        Core.extractChannel(m, a, 3)
        Imgproc.threshold(a, a, 10.0, 255.0, Imgproc.THRESH_BINARY)
        return inpaint(src, a)
    }

    // ---------- 4. Tulis terjemahan ----------
    fun typeset(src: Bitmap, blocks: List<Block>): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        for (b in blocks) {
            val text = b.translated.trim()
            if (text.isEmpty()) continue
            val w = max(40, (b.rect.width() * 1.2f).toInt())
            val h = max(40, (b.rect.height() * 1.2f).toInt())
            var size = 72f
            var layout: StaticLayout
            while (true) {
                paint.textSize = size
                layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
                if (layout.height <= h || size <= 9f) break
                size -= 2f
            }
            canvas.save()
            canvas.translate(b.rect.centerX() - w / 2f, b.rect.centerY() - layout.height / 2f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size / 6f
            paint.color = Color.WHITE
            layout.draw(canvas)
            paint.style = Paint.Style.FILL
            paint.color = Color.BLACK
            layout.draw(canvas)
            canvas.restore()
        }
        return out
    }
}
