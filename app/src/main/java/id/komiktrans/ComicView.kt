package id.komiktrans

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class Tool { PAN, BRUSH, LASSO, WAND, MASK_ERASE, BOX }

/** Gambar dengan zoom/geser (2 jari) + alat penghapus + editor kotak teks. */
class ComicView(ctx: Context) : View(ctx) {
    var bitmap: Bitmap? = null
        private set
    var blocks: List<Block> = emptyList()
        set(v) { field = v; selected = -1; invalidate() }
    var tool = Tool.PAN
    var brushDp = 24f
    var showBoxes = true
        set(v) { field = v; invalidate() }
    var locked = false
    var selected = -1
    var onGestureEnd: (() -> Unit)? = null
    var onWandTap: ((Int, Int) -> Unit)? = null
    var onBoxChanged: (() -> Unit)? = null
    var onBoxSelected: ((Int) -> Unit)? = null

    private var mask: Bitmap? = null
    private var maskCanvas: Canvas? = null
    private val m = Matrix()
    private val inv = Matrix()
    private val density = ctx.resources.displayMetrics.density
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tint = Paint().apply { color = 0x99FF0000.toInt() }
    private val boxPaint = Paint().apply { style = Paint.Style.STROKE; color = Color.RED; strokeWidth = 3f }
    private val selPaint = Paint().apply { style = Paint.Style.STROKE; color = Color.GREEN }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.GREEN }
    private val maskStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val maskFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val clearMode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    private val clearStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; xfermode = clearMode
    }
    private val clearFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; xfermode = clearMode }
    private val lassoPreview = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.YELLOW }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 3f
    }

    private val lassoPath = Path()
    private var lassoActive = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastN = 0
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var brushDown = false
    private var drew = false
    private var lastIx = 0f
    private var lastIy = 0f
    private var cursorX = -1f
    private var cursorY = -1f
    private var boxMode = 0          // 0 = tidak ada, 1 = pindah, 2 = ubah ukuran
    private var boxChanged = false

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            m.postScale(d.scaleFactor, d.scaleFactor, d.focusX, d.focusY)
            invalidate()
            return true
        }
    })

    fun setBitmap(b: Bitmap) { bitmap = b; newMask(b); fit(); invalidate() }
    fun replaceBitmap(b: Bitmap) { bitmap = b; newMask(b); invalidate() }
    fun clearMask() { bitmap?.let { newMask(it) }; invalidate() }
    fun maskBitmap(): Bitmap? = mask

    private fun newMask(b: Bitmap) {
        mask = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ALPHA_8)
        maskCanvas = Canvas(mask!!)
        lassoPath.reset(); lassoActive = false
    }

    private fun fit() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        val s = min(width / b.width.toFloat(), height / b.height.toFloat())
        m.reset()
        m.postScale(s, s)
        m.postTranslate((width - b.width * s) / 2f, (height - b.height * s) / 2f)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { fit() }

    private fun scaleNow(): Float { val v = FloatArray(9); m.getValues(v); return v[Matrix.MSCALE_X] }

    private fun toImg(x: Float, y: Float): FloatArray {
        m.invert(inv)
        val p = floatArrayOf(x, y)
        inv.mapPoints(p)
        return p
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF202020.toInt())
        val b = bitmap ?: return
        c.drawBitmap(b, m, bmpPaint)
        mask?.let { c.drawBitmap(it, m, tint) }
        if ((showBoxes || tool == Tool.BOX) && blocks.isNotEmpty()) {
            val s = scaleNow()
            c.save(); c.concat(m)
            boxPaint.strokeWidth = 3f / s
            selPaint.strokeWidth = 5f / s
            for ((i, bl) in blocks.withIndex()) {
                if (i != selected) c.drawRect(bl.rect, boxPaint)
            }
            if (selected in blocks.indices) {
                val r = blocks[selected].rect
                c.drawRect(r, selPaint)
                if (tool == Tool.BOX) c.drawCircle(r.right.toFloat(), r.bottom.toFloat(), 14f * density / s, handlePaint)
            }
            c.restore()
        }
        if (!lassoPath.isEmpty) {
            c.save(); c.concat(m)
            lassoPreview.strokeWidth = 3f / scaleNow()
            c.drawPath(lassoPath, lassoPreview)
            c.restore()
        }
        if (cursorX >= 0f) c.drawCircle(cursorX, cursorY, brushDp * density / 2f, cursorPaint)
    }

    private fun stroke(x: Float, y: Float) {
        val mc = maskCanvas ?: return
        val p = toImg(x, y)
        val w = brushDp * density / scaleNow()
        val erase = tool == Tool.MASK_ERASE
        val sp = if (erase) clearStroke else maskStroke
        val fp = if (erase) clearFill else maskFill
        sp.strokeWidth = w
        if (!brushDown) {
            mc.drawCircle(p[0], p[1], w / 2f, fp)
            brushDown = true
        } else {
            mc.drawLine(lastIx, lastIy, p[0], p[1], sp)
        }
        lastIx = p[0]; lastIy = p[1]
        drew = true
        cursorX = x; cursorY = y
        invalidate()
    }

    private fun finishLasso() {
        if (lassoActive) {
            lassoPath.close()
            maskCanvas?.drawPath(lassoPath, maskFill)
            lassoPath.reset()
            lassoActive = false
            invalidate()
            onGestureEnd?.invoke()
        }
    }

    private fun boxDown(fx: Float, fy: Float) {
        val p = toImg(fx, fy)
        boxMode = 0
        if (selected in blocks.indices) {
            val r = blocks[selected].rect
            if (hypot(p[0] - r.right, p[1] - r.bottom) * scaleNow() < 28f * density) boxMode = 2
        }
        if (boxMode == 0) {
            var hit = -1
            var hitArea = Int.MAX_VALUE
            for (i in blocks.indices) {
                val r = blocks[i].rect
                if (r.contains(p[0].toInt(), p[1].toInt()) && r.width() * r.height() < hitArea) {
                    hit = i; hitArea = r.width() * r.height()
                }
            }
            selected = hit
            if (hit >= 0) boxMode = 1
            onBoxSelected?.invoke(hit)
        }
        lastIx = p[0]; lastIy = p[1]
        boxChanged = false
        invalidate()
    }

    private fun boxMove(fx: Float, fy: Float) {
        if (selected !in blocks.indices) return
        val p = toImg(fx, fy)
        val b = blocks[selected]
        if (boxMode == 1) {
            b.rect.offset((p[0] - lastIx).toInt(), (p[1] - lastIy).toInt())
        } else if (boxMode == 2) {
            b.rect.right = max(b.rect.left + 30, p[0].toInt())
            b.rect.bottom = max(b.rect.top + 30, p[1].toInt())
        }
        b.manual = true
        lastIx = p[0]; lastIy = p[1]
        boxChanged = true
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (locked) return true
        scaler.onTouchEvent(e)
        val n = e.pointerCount
        var fx = 0f; var fy = 0f
        for (i in 0 until n) { fx += e.getX(i); fy += e.getY(i) }
        fx /= n; fy /= n
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = fx; lastY = fy; lastN = 1
                downX = fx; downY = fy; moved = false
                brushDown = false; drew = false; boxMode = 0
                when (tool) {
                    Tool.BRUSH, Tool.MASK_ERASE -> stroke(fx, fy)
                    Tool.LASSO -> {
                        val p = toImg(fx, fy)
                        lassoPath.reset(); lassoPath.moveTo(p[0], p[1]); lassoActive = true
                    }
                    Tool.BOX -> boxDown(fx, fy)
                    else -> {}
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                lastN = 0; brushDown = false; boxMode = 0
                lassoPath.reset(); lassoActive = false
                cursorX = -1f
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(fx - downX, fy - downY) > 16f) moved = true
                val pan = n >= 2 || tool == Tool.PAN || tool == Tool.WAND || (tool == Tool.BOX && boxMode == 0)
                if (pan) {
                    if (lastN == n) { m.postTranslate(fx - lastX, fy - lastY); invalidate() }
                } else if (n == 1) {
                    when (tool) {
                        Tool.BRUSH, Tool.MASK_ERASE -> stroke(fx, fy)
                        Tool.LASSO -> if (lassoActive) {
                            val p = toImg(fx, fy)
                            lassoPath.lineTo(p[0], p[1]); invalidate()
                        }
                        Tool.BOX -> boxMove(fx, fy)
                        else -> {}
                    }
                }
                lastX = fx; lastY = fy; lastN = n
            }
            MotionEvent.ACTION_UP -> {
                val wasTap = !moved && lastN == 1
                brushDown = false; cursorX = -1f
                when (tool) {
                    Tool.BRUSH -> if (drew) { drew = false; invalidate(); onGestureEnd?.invoke() }
                    Tool.MASK_ERASE -> drew = false
                    Tool.LASSO -> finishLasso()
                    Tool.WAND -> if (wasTap) {
                        val p = toImg(e.x, e.y)
                        onWandTap?.invoke(p[0].toInt(), p[1].toInt())
                    }
                    Tool.BOX -> if (boxChanged) { boxChanged = false; onBoxChanged?.invoke() }
                    Tool.PAN -> {}
                }
                boxMode = 0
                lastN = 0
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                brushDown = false; cursorX = -1f; lastN = 0; boxMode = 0
                lassoPath.reset(); lassoActive = false; invalidate()
            }
        }
        return true
    }
}
