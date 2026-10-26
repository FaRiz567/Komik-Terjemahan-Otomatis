package id.komiktrans

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.min

/** Tampilan gambar dengan zoom/geser (dua jari) dan kuas penghapus. */
class ComicView(ctx: Context) : View(ctx) {
    var bitmap: Bitmap? = null
        private set
    var blocks: List<Block> = emptyList()
        set(v) { field = v; invalidate() }
    var brushMode = false
    var brushDp = 24f
    var showBoxes = true
        set(v) { field = v; invalidate() }

    private var mask: Bitmap? = null
    private var maskCanvas: Canvas? = null
    private val m = Matrix()
    private val inv = Matrix()
    private val density = ctx.resources.displayMetrics.density
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tint = Paint().apply { color = 0x99FF0000.toInt() }
    private val boxPaint = Paint().apply { style = Paint.Style.STROKE; color = Color.RED; strokeWidth = 3f }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private var lastX = 0f
    private var lastY = 0f
    private var lastN = 0
    private var brushDown = false
    private var lastIx = 0f
    private var lastIy = 0f

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

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF202020.toInt())
        val b = bitmap ?: return
        c.drawBitmap(b, m, bmpPaint)
        mask?.let { c.drawBitmap(it, m, tint) }
        if (showBoxes && blocks.isNotEmpty()) {
            c.save()
            c.concat(m)
            for (bl in blocks) c.drawRect(bl.rect, boxPaint)
            c.restore()
        }
    }

    private fun stroke(x: Float, y: Float) {
        val mc = maskCanvas ?: return
        m.invert(inv)
        val p = floatArrayOf(x, y)
        inv.mapPoints(p)
        val v = FloatArray(9)
        m.getValues(v)
        val width = brushDp * density / v[Matrix.MSCALE_X]
        maskPaint.strokeWidth = width
        if (!brushDown) {
            mc.drawCircle(p[0], p[1], width / 2f, dotPaint)
            brushDown = true
        } else {
            mc.drawLine(lastIx, lastIy, p[0], p[1], maskPaint)
        }
        lastIx = p[0]; lastIy = p[1]
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        val n = e.pointerCount
        var fx = 0f; var fy = 0f
        for (i in 0 until n) { fx += e.getX(i); fy += e.getY(i) }
        fx /= n; fy /= n
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = fx; lastY = fy; lastN = 1; brushDown = false
                if (brushMode) stroke(fx, fy)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                lastN = 0; brushDown = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (n >= 2 || !brushMode) {
                    if (lastN == n) { m.postTranslate(fx - lastX, fy - lastY); invalidate() }
                } else if (n == 1) {
                    stroke(fx, fy)
                }
                lastX = fx; lastY = fy; lastN = n
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { brushDown = false; lastN = 0 }
        }
        return true
    }
}
