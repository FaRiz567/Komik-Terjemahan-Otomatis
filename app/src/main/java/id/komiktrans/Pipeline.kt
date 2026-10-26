package id.komiktrans

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Html
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
import org.json.JSONObject
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import org.opencv.core.Point as CvPoint
import org.opencv.core.Rect as CvRect

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

    // ================= 1. OCR =================
    suspend fun ocr(bmp: Bitmap, lang: Lang, rtl: Boolean): List<Block> {
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
        }.let { order(it, bmp.height, rtl) }
    }

    /** Urutan baca: baris atas->bawah; dalam baris kanan->kiri (manga) atau kiri->kanan. Heuristik. */
    fun order(blocks: List<Block>, imgH: Int, rtl: Boolean): List<Block> {
        val sorted = blocks.sortedBy { it.rect.centerY() }
        val rows = mutableListOf<MutableList<Block>>()
        val tol = imgH * 0.10f
        for (b in sorted) {
            val row = rows.lastOrNull()
            if (row != null && b.rect.centerY() - row.first().rect.centerY() < tol) row.add(b)
            else rows.add(mutableListOf(b))
        }
        return rows.flatMap { r ->
            if (rtl) r.sortedByDescending { it.rect.centerX() } else r.sortedBy { it.rect.centerX() }
        }
    }

    // ================= 2. Terjemahan =================
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

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun httpGet(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
        if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun gtx(s: String, src: Lang, client: String): String {
        val body = httpGet("https://translate.googleapis.com/translate_a/single" +
                "?client=$client&sl=${src.gtx}&tl=id&dt=t&q=${enc(s)}")
        val arr = JSONArray(body).getJSONArray(0)
        return buildString { for (i in 0 until arr.length()) append(arr.getJSONArray(i).getString(0)) }
    }

    private fun webMobile(s: String, src: Lang): String {
        val body = httpGet("https://translate.google.com/m?sl=${src.gtx}&tl=id&q=${enc(s)}")
        val mt = Regex("class=\"result-container\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL).find(body)
            ?: throw IOException("format halaman berubah")
        return Html.fromHtml(mt.groupValues[1], Html.FROM_HTML_MODE_LEGACY).toString()
    }

    private fun myMemory(s: String, src: Lang): String {
        val body = httpGet("https://api.mymemory.translated.net/get?q=${enc(s.take(450))}&langpair=${src.gtx}|id")
        val o = JSONObject(body)
        if (o.optInt("responseStatus", 200) != 200) throw IOException("status ${o.opt("responseStatus")}")
        return o.getJSONObject("responseData").getString("translatedText")
    }

    private val engines: List<Pair<String, (String, Lang) -> String>> = listOf(
        "gtx" to { s, l -> gtx(s, l, "gtx") },
        "web" to { s, l -> webMobile(s, l) },
        "dict" to { s, l -> gtx(s, l, "dict-chrome-ex") },
        "mymemory" to { s, l -> myMemory(s, l) }
    )
    @Volatile private var good = 0

    /** Online: mencoba beberapa server berurutan sampai ada yang berhasil. */
    suspend fun translateOnline(texts: List<String>, src: Lang): List<String> =
        withContext(Dispatchers.IO) {
            val errs = LinkedHashMap<String, String>()
            texts.map { s ->
                var result: String? = null
                for (k in engines.indices) {
                    val idx = (good + k) % engines.size
                    val (name, f) = engines[idx]
                    try {
                        result = f(s, src); good = idx; break
                    } catch (e: Exception) {
                        errs[name] = "${e.javaClass.simpleName}: ${e.message}"
                    }
                }
                result ?: throw IOException("Semua server gagal. " +
                        errs.entries.joinToString("; ") { "${it.key} -> ${it.value}" })
            }
        }

    // ================= 3. Penghapus (inpaint) =================
    private fun toRgb(src: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        return rgb
    }

    /**
     * Inpaint pintar: tiap gumpalan mask dicek warna sekelilingnya.
     * Kalau polos (balon putih, latar rata) -> diisi warna median sekitar (bersih, tanpa noda).
     * Kalau rumit -> OpenCV Telea.
     */
    private fun smartInpaint(src: Bitmap, mask: Mat): Bitmap {
        val rgb = toRgb(src)
        val rest = mask.clone()
        val grp = Mat()
        Imgproc.dilate(mask, grp, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(15.0, 15.0)))
        val labels = Mat(); val stats = Mat(); val cent = Mat()
        val n = Imgproc.connectedComponentsWithStats(grp, labels, stats, cent)
        val k9 = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0))
        for (i in 1 until n) {
            val sx = stats.get(i, Imgproc.CC_STAT_LEFT)[0].toInt()
            val sy = stats.get(i, Imgproc.CC_STAT_TOP)[0].toInt()
            val sw = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt()
            val sh = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
            val x0 = max(0, sx - 12); val y0 = max(0, sy - 12)
            val x1 = min(rgb.cols(), sx + sw + 12); val y1 = min(rgb.rows(), sy + sh + 12)
            if (x1 <= x0 || y1 <= y0) continue
            val roi = CvRect(x0, y0, x1 - x0, y1 - y0)
            val cm = Mat()
            Core.compare(labels.submat(roi), Scalar(i.toDouble()), cm, Core.CMP_EQ)
            val mroi = mask.submat(roi)
            Core.bitwise_and(cm, mroi, cm)
            if (Core.countNonZero(cm) == 0) continue
            val d = Mat()
            Imgproc.dilate(cm, d, k9)
            val notM = Mat()
            Core.bitwise_not(mroi, notM)
            val ring = Mat()
            Core.bitwise_and(d, notM, ring)
            if (Core.countNonZero(ring) < 20) continue
            val mean = MatOfDouble(); val std = MatOfDouble()
            val rgbRoi = rgb.submat(roi)
            Core.meanStdDev(rgbRoi, mean, std, ring)
            val s = std.toArray(); val mu = mean.toArray()
            if (s.max() < 16.0) {
                rgbRoi.setTo(Scalar(mu[0], mu[1], mu[2]), cm)
                rest.submat(roi).setTo(Scalar(0.0), cm)
            }
        }
        val out: Mat
        if (Core.countNonZero(rest) > 0) {
            out = Mat()
            Photo.inpaint(rgb, rest, out, 4.0, Photo.INPAINT_TELEA)
        } else out = rgb
        val rgba = Mat()
        Imgproc.cvtColor(out, rgba, Imgproc.COLOR_RGB2RGBA)
        val res = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, res)
        return res
    }

    /** Terapkan mask (seukuran gambar). Hanya area sekitar mask yang diproses, jadi cepat dan hemat RAM. */
    fun applyMask(src: Bitmap, mask: Mat): Bitmap {
        val pts = Mat()
        Core.findNonZero(mask, pts)
        if (pts.empty()) return src
        val b = Imgproc.boundingRect(pts)
        val pad = 40
        val x = max(0, b.x - pad); val y = max(0, b.y - pad)
        val w = min(src.width - x, b.width + 2 * pad)
        val h = min(src.height - y, b.height + 2 * pad)
        if (w <= 0 || h <= 0) return src
        val crop = Bitmap.createBitmap(src, x, y, w, h)
        val res = smartInpaint(crop, mask.submat(CvRect(x, y, w, h)).clone())
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawBitmap(res, x.toFloat(), y.toFloat(), null)
        return out
    }

    /** Hapus semua teks hasil OCR (mask huruf dicari dengan Otsu). */
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
            val roi = CvRect(x, y, w, h)
            val bin = Mat()
            Imgproc.threshold(gray.submat(roi), bin, 0.0, 255.0,
                Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            if (Core.countNonZero(bin) > w * h / 2) Core.bitwise_not(bin, bin)
            Imgproc.dilate(bin, bin, kernel)
            Core.bitwise_or(mask.submat(roi), bin, mask.submat(roi))
        }
        return applyMask(src, mask)
    }

    /** Hapus area coretan/lasso (mask ALPHA_8 seukuran gambar). */
    fun eraseMask(src: Bitmap, a8: Bitmap): Bitmap {
        val tmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(tmp).drawBitmap(a8, 0f, 0f, Paint().apply { color = Color.WHITE })
        val m = Mat()
        Utils.bitmapToMat(tmp, m)
        tmp.recycle()
        val a = Mat()
        Core.extractChannel(m, a, 3)
        Imgproc.threshold(a, a, 10.0, 255.0, Imgproc.THRESH_BINARY)
        return applyMask(src, a)
    }

    /** Tongkat ajaib: pilih area berwarna mirip dari titik tap, lalu hapus. */
    fun eraseWand(src: Bitmap, px: Int, py: Int, tol: Int): Bitmap {
        val rgb = toRgb(src)
        val h = rgb.rows(); val w = rgb.cols()
        val ff = Mat.zeros(h + 2, w + 2, CvType.CV_8UC1)
        val t = tol.toDouble()
        Imgproc.floodFill(
            rgb, ff, CvPoint(px.toDouble(), py.toDouble()), Scalar(0.0), CvRect(),
            Scalar(t, t, t), Scalar(t, t, t),
            4 or Imgproc.FLOODFILL_MASK_ONLY or Imgproc.FLOODFILL_FIXED_RANGE or (255 shl 8)
        )
        val region = ff.submat(1, h + 1, 1, w + 1).clone()
        Imgproc.dilate(region, region,
            Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0)))
        return applyMask(src, region)
    }

    // ================= 4. Tulis terjemahan =================
    fun typeset(src: Bitmap, blocks: List<Block>, face: Typeface): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face }
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
