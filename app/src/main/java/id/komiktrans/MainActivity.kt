package id.komiktrans

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import kotlin.math.max

class MainActivity : ComponentActivity() {

    private lateinit var view: ComicView
    private lateinit var status: TextView
    private lateinit var btnLang: Button
    private lateinit var btnMode: Button
    private lateinit var btnBrush: Button

    private var original: Bitmap? = null   // gambar asli (untuk OCR)
    private var cleaned: Bitmap? = null    // gambar setelah teks dihapus
    private var blocks: MutableList<Block> = mutableListOf()
    private var lang = Lang.JA
    private var online = true

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { load(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpenCVLoader.initLocal()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        view = ComicView(this)
        root.addView(view, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        status = TextView(this).apply {
            setPadding(24, 12, 24, 12)
            text = "Buka gambar komik untuk mulai."
        }
        root.addView(status)

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, f: () -> Unit): Button =
            Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } }
                .also { bar.addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)) }

        btn("1. Buka") { picker.launch("image/*") }
        btnLang = btn("Bahasa: ${lang.label}") { pickLang() }
        btnMode = btn("Terjemah: Online") { toggleMode() }
        btn("2. Scan+Terjemah") { scan() }
        btn("3. Edit teks") { editDialog() }
        btn("4. Hapus teks otomatis") { autoErase() }
        btnBrush = btn("Kuas: mati") { toggleBrush() }
        btn("Terapkan kuas") { applyBrush() }
        btn("Kotak: on/off") { view.showBoxes = !view.showBoxes }
        btn("5. Tulis terjemahan") { typeset() }
        btn("6. Simpan") { save() }

        root.addView(HorizontalScrollView(this).apply { addView(bar) })
        setContentView(root)
    }

    private fun say(s: String) { status.text = s }

    // ---------- buka gambar ----------
    private fun load(uri: Uri) {
        lifecycleScope.launch {
            say("Memuat gambar...")
            try {
                val bmp = withContext(Dispatchers.IO) { decode(uri) }
                original = bmp; cleaned = bmp; blocks = mutableListOf()
                view.blocks = blocks
                view.setBitmap(bmp)
                say("Gambar dimuat (${bmp.width}x${bmp.height}). Lanjut: Scan+Terjemah.")
            } catch (e: Exception) { say("Gagal memuat: ${e.message}") }
        }
    }

    private fun decode(uri: Uri): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (max(o.outWidth, o.outHeight) / s > 2600) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        return contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o2) }!!
    }

    // ---------- pengaturan ----------
    private fun pickLang() {
        val all = Lang.values()
        AlertDialog.Builder(this).setTitle("Bahasa komik")
            .setItems(all.map { it.label }.toTypedArray()) { _, i ->
                lang = all[i]; btnLang.text = "Bahasa: ${lang.label}"
            }.show()
    }

    private fun toggleMode() {
        online = !online
        btnMode.text = "Terjemah: " + if (online) "Online" else "Offline"
    }

    private fun toggleBrush() {
        view.brushMode = !view.brushMode
        btnBrush.text = "Kuas: " + if (view.brushMode) "HIDUP" else "mati"
        say(if (view.brushMode) "Coret area yang mau dihapus (1 jari). Zoom/geser: 2 jari." else "Mode geser/zoom.")
    }

    // ---------- scan + terjemah ----------
    private fun scan() {
        val src = original ?: return say("Buka gambar dulu.")
        lifecycleScope.launch {
            try {
                say("Membaca teks...")
                val found = Pipeline.ocr(src, lang)
                if (found.isEmpty()) return@launch say("Tidak ada teks terdeteksi. Coba ganti bahasa.")
                say("Menerjemahkan ${found.size} blok (${if (online) "online" else "offline"})...")
                val tr = if (online) Pipeline.translateOnline(found.map { it.original }, lang)
                else Pipeline.translateOffline(found.map { it.original }, lang)
                found.forEachIndexed { i, b -> b.translated = tr[i] }
                blocks = found.toMutableList()
                view.blocks = blocks
                say("${found.size} blok ditemukan. Cek/ubah lewat 'Edit teks'.")
            } catch (e: Exception) {
                say("Gagal: ${e.message}" + if (!online) " (coba mode Online)" else "")
            }
        }
    }

    // ---------- edit teks (alur notepad) ----------
    private fun editDialog() {
        if (blocks.isEmpty()) return say("Belum ada teks. Jalankan Scan+Terjemah dulu.")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 16) }
        val edits = ArrayList<EditText>()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Salin teks asli"; isAllCaps = false
            setOnClickListener {
                val s = blocks.mapIndexed { i, b -> "${i + 1}. ${b.original}" }.joinToString("\n")
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("komik", s))
                say("Teks asli disalin. Terjemahkan di luar, lalu 'Tempel dari notepad'.")
            }
        })
        row.addView(Button(this).apply {
            text = "Tempel dari notepad"; isAllCaps = false
            setOnClickListener {
                val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: return@setOnClickListener
                val re = Regex("^\\s*(\\d+)\\s*[.):|\\-]\\s*(.*)$")
                var cur = -1
                for (line in clip.lines()) {
                    val mt = re.find(line)
                    if (mt != null) {
                        cur = mt.groupValues[1].toInt() - 1
                        if (cur in edits.indices) edits[cur].setText(mt.groupValues[2])
                    } else if (cur in edits.indices && line.isNotBlank()) {
                        edits[cur].setText(edits[cur].text.toString() + " " + line.trim())
                    }
                }
            }
        })
        box.addView(row)
        blocks.forEachIndexed { i, b ->
            box.addView(TextView(this).apply { text = "${i + 1}. ${b.original}"; setTextColor(Color.GRAY) })
            val et = EditText(this).apply { setText(b.translated) }
            edits.add(et); box.addView(et)
        }
        AlertDialog.Builder(this).setTitle("Edit terjemahan")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Simpan") { _, _ ->
                edits.forEachIndexed { i, e -> blocks[i].translated = e.text.toString() }
            }
            .setNegativeButton("Batal", null).show()
    }

    // ---------- hapus teks ----------
    private fun autoErase() {
        val src = cleaned ?: return say("Buka gambar dulu.")
        if (blocks.isEmpty()) return say("Scan dulu, atau pakai kuas.")
        lifecycleScope.launch {
            say("Menghapus teks...")
            try {
                val out = withContext(Dispatchers.Default) { Pipeline.eraseRects(src, blocks.map { it.rect }) }
                cleaned = out; view.replaceBitmap(out)
                say("Teks terhapus. Sisa teks bisa dicoret dengan kuas.")
            } catch (e: Throwable) { say("Gagal hapus: ${e.message}") }
        }
    }

    private fun applyBrush() {
        val src = cleaned ?: return say("Buka gambar dulu.")
        val mk = view.maskBitmap() ?: return
        lifecycleScope.launch {
            say("Menghapus area coretan...")
            try {
                val out = withContext(Dispatchers.Default) { Pipeline.eraseMask(src, mk) }
                cleaned = out; view.replaceBitmap(out)
                say("Selesai.")
            } catch (e: Throwable) { say("Gagal: ${e.message}") }
        }
    }

    // ---------- tulis terjemahan + simpan ----------
    private fun typeset() {
        val src = cleaned ?: return say("Buka gambar dulu.")
        if (blocks.isEmpty()) return say("Belum ada teks terjemahan.")
        lifecycleScope.launch {
            say("Menulis terjemahan...")
            val out = withContext(Dispatchers.Default) { Pipeline.typeset(src, blocks) }
            view.showBoxes = false
            view.replaceBitmap(out)
            say("Selesai. Kalau kurang pas, edit teks lalu tulis ulang, atau langsung Simpan.")
        }
    }

    private fun save() {
        val bmp = view.bitmap ?: return say("Belum ada gambar.")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val cv = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "komik_${System.currentTimeMillis()}.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KomikTrans")
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
                    contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                }
                say("Tersimpan di Pictures/KomikTrans.")
            } catch (e: Exception) { say("Gagal simpan: ${e.message}") }
        }
    }
}
