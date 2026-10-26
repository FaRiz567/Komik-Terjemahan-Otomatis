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
import kotlin.math.min

enum class Tool { PAN, BRUSH, LASSO, WAND, MASK_ERASE }

/** Gambar dengan zoom/geser (2 jari) + alat penghapus: kuas, lasso, tongkat ajaib. */
class ComicView(ctx: Context) : View(ctx) {
    var bitmap: Bitmap? = null
        private set
    var blocks: List<Block> = emptyList()
        set(v) { field = v; invalidate() }
    var tool = Tool.PAN
    var brushDp = 24f
    var showBoxes = true
        set(v) { field = v; invalidate() }
    var locked = false
    var onGestureEnd: (() -> Unit)? = null
    var onWandTap: ((Int, Int) -> Unit)? = null

    private var mask: Bitmap? = null
    private var maskCanvas: Canvas? = null
    private val m = Matrix()
    private val inv = Matrix()
    private val density = ctx.resources.displayMetrics.density
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tint = Paint().apply { color = 0x99FF0000.toInt() }
    private val boxPaint = Paint().apply { style = Paint.Style.STROKE; color = Color.RED; strokeWidth = 3f }
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
        if (showBoxes && blocks.isNotEmpty()) {
            c.save(); c.concat(m)
            for (bl in blocks) c.drawRect(bl.rect, boxPaint)
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
                brushDown = false; drew = false
                when (tool) {
                    Tool.BRUSH, Tool.MASK_ERASE -> stroke(fx, fy)
                    Tool.LASSO -> {
                        val p = toImg(fx, fy)
                        lassoPath.reset(); lassoPath.moveTo(p[0], p[1]); lassoActive = true
                    }
                    else -> {}
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                lastN = 0; brushDown = false
                lassoPath.reset(); lassoActive = false
                cursorX = -1f
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(fx - downX, fy - downY) > 16f) moved = true
                if (n >= 2 || tool == Tool.PAN || tool == Tool.WAND) {
                    if (lastN == n) { m.postTranslate(fx - lastX, fy - lastY); invalidate() }
                } else if (n == 1) {
                    when (tool) {
                        Tool.BRUSH, Tool.MASK_ERASE -> stroke(fx, fy)
                        Tool.LASSO -> if (lassoActive) {
                            val p = toImg(fx, fy)
                            lassoPath.lineTo(p[0], p[1]); invalidate()
                        }
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
                    Tool.PAN -> {}
                }
                lastN = 0
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                brushDown = false; cursorX = -1f; lastN = 0
                lassoPath.reset(); lassoActive = false; invalidate()
            }
        }
        return true
    }
}
