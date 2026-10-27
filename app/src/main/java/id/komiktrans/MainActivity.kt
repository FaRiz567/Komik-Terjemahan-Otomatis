package id.komiktrans

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.io.File
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {

    private lateinit var view: ComicView
    private lateinit var status: TextView
    private lateinit var btnLang: Button
    private lateinit var btnMode: Button
    private lateinit var btnRtl: Button
    private lateinit var btnFont: Button
    private lateinit var btnTool: Button
    private lateinit var btnAuto: Button

    private var original: Bitmap? = null   // gambar asli (untuk OCR)
    private var cleaned: Bitmap? = null    // gambar setelah teks dihapus
    private var blocks: MutableList<Block> = mutableListOf()
    private var lang = Lang.JA
    private var online = true
    private var rtl = true
    private var busy = false
    private var autoApply = true
    private var wandTol = 20
    private var textScale = 100

    private val undoStack = ArrayDeque<Bitmap>()
    private val redoStack = ArrayDeque<Bitmap>()

    private val fonts: List<Pair<String, Typeface>> = listOf(
        "Sans tebal" to Typeface.create("sans-serif-medium", Typeface.BOLD),
        "Sans padat (condensed)" to Typeface.create("sans-serif-condensed", Typeface.BOLD),
        "Serif tebal" to Typeface.create(Typeface.SERIF, Typeface.BOLD),
        "Casual (santai)" to Typeface.create("casual", Typeface.NORMAL),
        "Tulisan tangan (cursive)" to Typeface.create("cursive", Typeface.NORMAL),
        "Monospace" to Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    )
    private var face: Typeface = fonts[0].second
    private var faceName: String = fonts[0].first

    private val tools = listOf(
        "Geser / Zoom" to Tool.PAN,
        "Kuas" to Tool.BRUSH,
        "Lasso (lingkari area)" to Tool.LASSO,
        "Tongkat ajaib (tap area)" to Tool.WAND,
        "Penghapus coretan" to Tool.MASK_ERASE,
        "Edit kotak teks (geser / ubah ukuran)" to Tool.BOX
    )

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { load(it) }
    }
    private val fontPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadFont(it) }
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { batch(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpenCVLoader.initLocal()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        view = ComicView(this)
        view.onGestureEnd = { if (autoApply) applyMask() }
        view.onWandTap = { x, y -> wand(x, y) }
        view.onBoxChanged = { retypeset() }
        view.onBoxSelected = { i ->
            say(if (i in blocks.indices)
                "Kotak ${i + 1}: ${blocks[i].translated.take(50)} | geser untuk pindah, tarik titik hijau di pojok untuk ubah ukuran."
            else "Tidak ada kotak dipilih.")
        }
        root.addView(view, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        status = TextView(this).apply {
            setPadding(24, 8, 24, 8)
            text = "Buka gambar komik (atau proses satu folder) untuk mulai."
        }
        root.addView(status)

        root.addView(sliderRow("Kuas", 8, 80, 24) { view.brushDp = it.toFloat() })
        root.addView(sliderRow("Toleransi tongkat", 2, 60, wandTol) { wandTol = it })
        root.addView(sliderRow("Ukuran teks %", 50, 150, textScale) { textScale = it })

        fun addRow(builder: (LinearLayout, (String, () -> Unit) -> Button) -> Unit) {
            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val mk: (String, () -> Unit) -> Button = { t, f ->
                Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } }
                    .also { bar.addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)) }
            }
            builder(bar, mk)
            root.addView(HorizontalScrollView(this).apply { addView(bar) })
        }

        // Baris 1: alur terjemahan
        addRow { _, b ->
            b("1. Buka") { picker.launch("image/*") }
            btnLang = b("Bahasa: ${lang.label}") { pickLang() }
            btnRtl = b(rtlText()) { toggleRtl() }
            btnMode = b("Terjemah: Online") { toggleMode() }
            b("2. Scan+Terjemah") { scan() }
            b("3. Edit teks") { editDialog() }
            btnFont = b("Font: $faceName") { fontDialog() }
            b("5. Tulis terjemahan") { typeset() }
            b("6. Simpan") { save() }
            b("Batch folder") { folderPicker.launch(null) }
        }
        // Baris 2: penghapus ala magic eraser
        addRow { _, b ->
            btnTool = b("Alat: Geser / Zoom") { toolDialog() }
            btnAuto = b("Auto-hapus: ON") { toggleAuto() }
            b("↶ Undo") { undo() }
            b("↷ Redo") { redo() }
            b("4. Hapus semua teks") { autoErase() }
            b("Terapkan coretan") { applyMask() }
            b("Kotak: on/off") { view.showBoxes = !view.showBoxes }
            b("✎ Edit kotak terpilih") { editBoxDialog() }
        }
        setContentView(root)
    }

    private fun say(s: String) { status.text = s }
    private fun rtlText() = if (rtl) "Baca: kanan→kiri" else "Baca: kiri→kanan"

    private fun sliderRow(label: String, lo: Int, hi: Int, init: Int, onChange: (Int) -> Unit): LinearLayout {
        val tv = TextView(this).apply { text = "$label: $init"; setPadding(24, 0, 12, 0) }
        val sb = SeekBar(this)
        sb.max = hi - lo
        sb.progress = init - lo
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p + lo
                tv.text = "$label: $v"
                onChange(v)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(tv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(sb, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
    }

    // ---------- buka gambar ----------
    private fun load(uri: Uri) {
        lifecycleScope.launch {
            say("Memuat gambar...")
            try {
                val bmp = withContext(Dispatchers.IO) { decode(uri) }
                original = bmp; cleaned = bmp; blocks = mutableListOf()
                undoStack.clear(); redoStack.clear()
                view.blocks = blocks
                view.setBitmap(bmp)
                say("Gambar dimuat (${bmp.width}x${bmp.height}). Lanjut: Scan+Terjemah, atau pilih Alat untuk menghapus.")
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
                rtl = (lang == Lang.JA)
                btnRtl.text = rtlText()
            }.show()
    }

    private fun toggleRtl() { rtl = !rtl; btnRtl.text = rtlText() }

    private fun toggleMode() {
        online = !online
        btnMode.text = "Terjemah: " + if (online) "Online" else "Offline"
    }

    private fun toolDialog() {
        AlertDialog.Builder(this).setTitle("Alat")
            .setItems(tools.map { it.first }.toTypedArray()) { _, i ->
                view.tool = tools[i].second
                btnTool.text = "Alat: ${tools[i].first.substringBefore(" (")}"
                if (view.tool == Tool.BOX && blocks.isNotEmpty() && cleaned != null) retypeset()
                say(when (view.tool) {
                    Tool.PAN -> "Geser dengan 1 jari, zoom dengan 2 jari."
                    Tool.BRUSH -> "Coret area yang mau dihapus. Zoom/geser: 2 jari."
                    Tool.LASSO -> "Lingkari area yang mau dihapus, lepas jari untuk menghapus."
                    Tool.WAND -> "Tap area berwarna sama untuk dihapus. Atur Toleransi di slider."
                    Tool.MASK_ERASE -> "Hapus coretan merah yang belum diterapkan (mode Auto-hapus OFF)."
                    Tool.BOX -> "Tap kotak untuk memilih, geser untuk memindah, tarik titik hijau untuk mengubah ukuran. Teks langsung menyesuaikan kotak."
                })
            }.show()
    }

    private fun toggleAuto() {
        autoApply = !autoApply
        btnAuto.text = "Auto-hapus: " + if (autoApply) "ON" else "OFF"
        say(if (autoApply) "Area langsung terhapus saat jari diangkat." else
            "Coretan dikumpulkan dulu; tekan 'Terapkan coretan' untuk menghapus.")
    }

    private fun fontDialog() {
        val names = fonts.map { it.first } + "Pilih file font (.ttf/.otf)..."
        AlertDialog.Builder(this).setTitle("Pilih font").setItems(names.toTypedArray()) { _, i ->
            if (i < fonts.size) {
                face = fonts[i].second; faceName = fonts[i].first
                btnFont.text = "Font: $faceName"
            } else fontPicker.launch("*/*")
        }.show()
    }

    private fun loadFont(uri: Uri) {
        try {
            val f = File(cacheDir, "custom_font.ttf")
            contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            face = Typeface.createFromFile(f)
            faceName = "File font"
            btnFont.text = "Font: $faceName"
            say("Font dimuat. Tekan 'Tulis terjemahan' untuk menerapkan.")
        } catch (e: Exception) { say("File font tidak valid: ${e.message}") }
    }

    // ---------- scan + terjemah ----------
    private suspend fun translate(texts: List<String>): List<String> =
        if (online) Pipeline.translateOnline(texts, lang) else Pipeline.translateOffline(texts, lang)

    private fun scan() {
        val src = original ?: return say("Buka gambar dulu.")
        lifecycleScope.launch {
            try {
                say("Membaca teks...")
                val found = Pipeline.ocr(src, lang, rtl)
                if (found.isEmpty()) return@launch say("Tidak ada teks terdeteksi. Coba ganti bahasa.")
                say("Menerjemahkan ${found.size} blok (${if (online) "online" else "offline"})...")
                val tr = translate(found.map { it.original })
                found.forEachIndexed { i, b -> b.translated = tr[i] }
                blocks = found.toMutableList()
                view.blocks = blocks
                say("${found.size} blok ditemukan. Cek/ubah lewat 'Edit teks'.")
            } catch (e: Exception) {
                // teks asli tetap disimpan agar bisa diterjemahkan manual lewat notepad
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

    // ---------- penghapus + undo/redo ----------
    private fun pushHistory(b: Bitmap) {
        undoStack.addLast(b)
        while (undoStack.size > 3) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return say("Tidak ada yang bisa di-undo.")
        cleaned?.let { redoStack.addLast(it) }
        cleaned = prev
        view.replaceBitmap(prev)
        say("Undo.")
    }

    private fun redo() {
        val nxt = redoStack.removeLastOrNull() ?: return say("Tidak ada yang bisa di-redo.")
        cleaned?.let { undoStack.addLast(it) }
        cleaned = nxt
        view.replaceBitmap(nxt)
        say("Redo.")
    }

    /** Jalankan satu operasi edit pada gambar bersih (di thread latar), dengan riwayat undo. */
    private fun runEdit(label: String, op: (Bitmap) -> Bitmap) {
        val src = cleaned ?: return say("Buka gambar dulu.")
        if (view.locked) return
        view.locked = true
        say(label)
        lifecycleScope.launch {
            try {
                val out = withContext(Dispatchers.Default) { op(src) }
                if (out === src) {
                    say("Tidak ada area yang dihapus.")
                    view.clearMask()
                } else {
                    pushHistory(src)
                    cleaned = out
                    view.replaceBitmap(out)
                    say("Selesai. ↶ Undo tersedia. (Kalau tadi sudah 'Tulis terjemahan', tulis ulang setelah selesai menghapus.)")
                }
            } catch (e: Throwable) {
                say("Gagal: ${e.message}")
                view.clearMask()
            } finally {
                view.locked = false
            }
        }
    }

    private fun autoErase() {
        if (blocks.isEmpty()) return say("Scan dulu, atau pakai Alat Kuas/Lasso/Tongkat.")
        val rects = blocks.map { it.rect }
        runEdit("Menghapus semua teks...") { Pipeline.eraseRects(it, rects) }
    }

    private fun applyMask() {
        val mk = view.maskBitmap() ?: return
        runEdit("Menghapus area terpilih...") { Pipeline.eraseMask(it, mk) }
    }

    private fun wand(x: Int, y: Int) {
        val b = cleaned ?: return
        if (x !in 0 until b.width || y !in 0 until b.height) return
        val tol = wandTol
        runEdit("Menghapus area serupa...") { Pipeline.eraseWand(it, x, y, tol) }
    }

    // ---------- tulis terjemahan + simpan ----------
    private fun typeset() {
        if (cleaned == null) return say("Buka gambar dulu.")
        if (blocks.isEmpty()) return say("Belum ada teks terjemahan.")
        view.showBoxes = false
        retypeset()
    }

    /** Tulis ulang semua teks (balon dicari otomatis; kotak manual dipakai apa adanya). */
    private fun retypeset() {
        val src = cleaned ?: return
        if (blocks.isEmpty() || view.locked) return
        view.locked = true
        say("Menulis terjemahan...")
        val scale = textScale / 100f
        lifecycleScope.launch {
            try {
                val out = withContext(Dispatchers.Default) { Pipeline.typeset(src, blocks, face, scale) }
                view.replaceBitmap(out)
                say("Selesai. Teks keluar balon? Pakai Alat → Edit kotak teks, atau atur slider Ukuran teks. Tips: hapus teks asli dulu agar balon terdeteksi.")
            } catch (e: Throwable) {
                say("Gagal menulis: ${e.message}")
            } finally {
                view.locked = false
            }
        }
    }

    private fun editBoxDialog() {
        val i = view.selected
        if (i !in blocks.indices) return say("Pilih kotak dulu: Alat → Edit kotak teks, lalu tap kotak di gambar.")
        val b = blocks[i]
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 16) }
        box.addView(TextView(this).apply { text = "Asli: ${b.original}"; setTextColor(Color.GRAY) })
        val et = EditText(this).apply { setText(b.translated) }
        box.addView(et)
        val scaleTv = TextView(this)
        fun refresh() {
            scaleTv.text = "Ukuran maks teks: ${(b.sizeScale * 100).toInt()}% | mode: " +
                    if (b.manual) "kotak manual" else "ikuti balon (otomatis)"
        }
        refresh()
        box.addView(scaleTv)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Teks −"; isAllCaps = false
            setOnClickListener { b.sizeScale = max(0.3f, b.sizeScale - 0.15f); refresh() }
        })
        row.addView(Button(this).apply {
            text = "Teks +"; isAllCaps = false
            setOnClickListener { b.sizeScale = min(2.5f, b.sizeScale + 0.15f); refresh() }
        })
        row.addView(Button(this).apply {
            text = "Ikuti balon"; isAllCaps = false
            setOnClickListener { b.manual = false; b.sizeScale = 1f; refresh() }
        })
        box.addView(row)
        AlertDialog.Builder(this).setTitle("Kotak ${i + 1}").setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                b.translated = et.text.toString()
                retypeset()
            }
            .setNegativeButton("Batal", null).show()
    }

    private fun saveBitmap(bmp: Bitmap, name: String, sub: String?) {
        val path = "Pictures/KomikTrans" + if (sub != null) "/$sub" else ""
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, path)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
        contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    }

    private fun save() {
        val bmp = view.bitmap ?: return say("Belum ada gambar.")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { saveBitmap(bmp, "komik_${System.currentTimeMillis()}", null) }
                say("Tersimpan di Pictures/KomikTrans.")
            } catch (e: Exception) { say("Gagal simpan: ${e.message}") }
        }
    }

    // ---------- batch: satu folder otomatis ----------
    private fun batch(tree: Uri) {
        if (busy) return say("Batch masih berjalan.")
        val dir = DocumentFile.fromTreeUri(this, tree) ?: return say("Folder tidak bisa dibuka.")
        val files = dir.listFiles()
            .filter { it.isFile && it.type?.startsWith("image/") == true }
            .sortedBy { it.name?.lowercase() }
        if (files.isEmpty()) return say("Tidak ada gambar di folder itu.")
        val sub = (dir.name ?: "batch").replace(Regex("[^A-Za-z0-9_\\- ]"), "_")
        busy = true
        lifecycleScope.launch {
            var ok = 0
            var fail = 0
            var lastErr = ""
            files.forEachIndexed { i, f ->
                say("Batch ${i + 1}/${files.size}: ${f.name}")
                try {
                    val bmp = withContext(Dispatchers.IO) { decode(f.uri) }
                    val found = Pipeline.ocr(bmp, lang, rtl)
                    var page = bmp
                    if (found.isNotEmpty()) {
                        val tr = translate(found.map { it.original })
                        found.forEachIndexed { k, b -> b.translated = tr[k] }
                        val cl = withContext(Dispatchers.Default) { Pipeline.eraseRects(bmp, found.map { it.rect }) }
                        page = withContext(Dispatchers.Default) { Pipeline.typeset(cl, found, face, textScale / 100f) }
                    }
                    val base = (f.name ?: "halaman_$i").substringBeforeLast('.')
                    withContext(Dispatchers.IO) { saveBitmap(page, base, sub) }
                    ok++
                } catch (e: Throwable) {
                    fail++
                    lastErr = e.message ?: e.javaClass.simpleName
                }
            }
            busy = false
            say("Batch selesai: $ok berhasil, $fail gagal." +
                    (if (fail > 0) " Error terakhir: $lastErr." else "") +
                    " Hasil di Pictures/KomikTrans/$sub")
        }
    }
}
