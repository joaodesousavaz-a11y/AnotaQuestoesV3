package br.anotaquestoes

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/** Camada transparente por cima do WebView. Só a caneta (ou o dedo, se ativado) desenha. */
class DrawingView(ctx: Context) : View(ctx) {

    enum class Tool { PEN, HIGHLIGHTER, LINE, ARROW, RECT, OVAL, TEXT, LASER, ERASER, ERASER_PART, MOVE }
    private enum class Kind { PEN, HL, LINE, ARROW, RECT, OVAL, TEXT, ERASE }

    private class Item(
        val kind: Kind, val color: Int, val width: Float,
        val pts: MutableList<PointF>, val text: String? = null,
        val pr: MutableList<Float>? = null
    ) {
        var path: Path? = null
        var layout: StaticLayout? = null
    }

    private class LaserPt(val x: Float, val y: Float, val t: Long)

    // ---------------- configuração (mexida pela barra) ----------------
    var scrollSource: View? = null
    var drawingEnabled = true
    var fingerDraws = false
    var palmRejection = true
    var pressureOn = true
    var ruler = false
    var penColor = Color.parseColor("#E53935")
    var hlColor = Color.parseColor("#FFEB3B")
    var penW = 3f
    var hlW = 16f
    var eraserW = 20f
    var laserW = 6f
    var textW = 20f
    var onTextRequest: ((Float, Float, String?) -> Unit)? = null
    var tool = Tool.PEN
        private set

    var widthDp: Float
        get() = when (tool) {
            Tool.HIGHLIGHTER -> hlW
            Tool.ERASER, Tool.ERASER_PART -> eraserW
            Tool.LASER -> laserW
            Tool.TEXT -> textW
            else -> penW
        }
        set(v) {
            when (tool) {
                Tool.HIGHLIGHTER -> hlW = v
                Tool.ERASER, Tool.ERASER_PART -> eraserW = v
                Tool.LASER -> laserW = v
                Tool.TEXT -> textW = v
                else -> penW = v
            }
        }

    val widthMin: Float
        get() = when (tool) {
            Tool.HIGHLIGHTER -> 6f
            Tool.ERASER, Tool.ERASER_PART -> 8f
            Tool.LASER -> 2f
            Tool.TEXT -> 12f
            else -> 1f
        }
    val widthMax: Float
        get() = when (tool) {
            Tool.HIGHLIGHTER -> 60f
            Tool.ERASER, Tool.ERASER_PART -> 80f
            Tool.LASER -> 20f
            Tool.TEXT -> 48f
            Tool.MOVE -> 1f
            else -> 30f
        }
    val widthStep: Float
        get() = when (tool) {
            Tool.HIGHLIGHTER, Tool.ERASER, Tool.ERASER_PART, Tool.TEXT -> 2f
            else -> 1f
        }

    fun selectTool(t: Tool) {
        tool = t
        if (t == Tool.ERASER || t == Tool.ERASER_PART) eraserTool = t
        invalidate()
    }

    // ---------------- estado ----------------
    private val dp = ctx.resources.displayMetrics.density
    private var eraserTool = Tool.ERASER
    private var items = ArrayList<Item>()
    private val undoStack = ArrayList<List<Item>>()
    private val redoStack = ArrayList<List<Item>>()
    private var current: Item? = null
    private var activeTool = Tool.PEN
    private var rulerActive = false
    private var stylusTouch = false
    private var changed = false
    private var downPx = 0f
    private var downPy = 0f
    private var downSx = 0f
    private var downSy = 0f
    private var lastPx = 0f
    private var lastPy = 0f
    private var prSmooth = 0.5f
    private var candidate: Item? = null
    private var moving: Item? = null
    private var editTarget: Item? = null
    private var eraserPos: PointF? = null
    private var penNearUntil = 0L
    private var ignoring = false
    private val lasers = ArrayList<MutableList<LaserPt>>()
    private var laserCur: MutableList<LaserPt>? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clearMode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

    private val sx get() = (scrollSource?.scrollX ?: 0).toFloat()
    private val sy get() = (scrollSource?.scrollY ?: 0).toFloat()
    private val eraserR get() = eraserW * dp / 2f

    // ---------------- histórico ----------------
    private fun pushUndo() {
        undoStack.add(ArrayList(items))
        if (undoStack.size > 100) undoStack.removeAt(0)
        redoStack.clear()
    }

    private fun commit(i: Item) { pushUndo(); items.add(i); invalidate() }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.add(ArrayList(items))
        items = ArrayList(undoStack.removeAt(undoStack.lastIndex))
        invalidate()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.add(ArrayList(items))
        items = ArrayList(redoStack.removeAt(redoStack.lastIndex))
        invalidate()
    }

    fun clearAll() {
        if (items.isEmpty()) return
        pushUndo(); items = ArrayList(); invalidate()
    }

    /** Cria um post-it novo ou, se houver um alvo de edição, troca o texto dele. */
    fun applyText(px: Float, py: Float, text: String) {
        val t = editTarget
        editTarget = null
        if (t != null) {
            val idx = items.indexOf(t)
            if (idx < 0) return
            if (text.isBlank()) { pushUndo(); items.removeAt(idx); invalidate(); return }
            if (text == t.text) return
            pushUndo()
            items[idx] = Item(Kind.TEXT, t.color, t.width, t.pts.map { PointF(it.x, it.y) }.toMutableList(), text)
            invalidate()
            return
        }
        if (text.isBlank()) return
        commit(Item(Kind.TEXT, Color.BLACK, textW * dp, mutableListOf(PointF(px, py)), text))
    }

    // ---------------- toque ----------------
    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        val tt = e.getToolType(0)
        if (tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER)
            penNearUntil = SystemClock.uptimeMillis() + 700
        return super.onGenericMotionEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!drawingEnabled) return false
        if (ignoring) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) ignoring = false
            return true
        }
        val now = SystemClock.uptimeMillis()
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val tt = e.getToolType(0)
            val stylus = tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER
            if (stylus) {
                penNearUntil = now + 700
            } else {
                if (palmRejection && now < penNearUntil) { ignoring = true; return true }  // palma: ignora
                if (!fingerDraws) return false                                              // dedo rola a página
            }
            stylusTouch = stylus
            val eraserPen = tt == MotionEvent.TOOL_TYPE_ERASER ||
                (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
            activeTool = if (eraserPen) eraserTool else tool
            rulerActive = ruler
            changed = false
            downSx = e.x; downSy = e.y
            downPx = e.x + sx; downPy = e.y + sy
            prSmooth = e.pressure
            begin(downPx, downPy, e.x, e.y, e.pressure)
            return true
        }
        if (stylusTouch) penNearUntil = now + 700
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize)
                    move(e.getHistoricalX(h) + sx, e.getHistoricalY(h) + sy,
                        e.getHistoricalX(h), e.getHistoricalY(h), e.getHistoricalPressure(h))
                move(e.x + sx, e.y + sy, e.x, e.y, e.pressure)
            }
            MotionEvent.ACTION_UP -> {
                move(e.x + sx, e.y + sy, e.x, e.y, e.pressure)
                end(false, e.x, e.y)
            }
            MotionEvent.ACTION_CANCEL -> end(true, e.x, e.y)
        }
        return true
    }

    private fun begin(px: Float, py: Float, scrX: Float, scrY: Float, p: Float) {
        when (activeTool) {
            Tool.PEN -> current = Item(
                Kind.PEN, penColor, penW * dp, mutableListOf(PointF(px, py)),
                pr = if (pressureOn && stylusTouch) mutableListOf(p) else null
            )
            Tool.HIGHLIGHTER -> current = Item(Kind.HL, hlColor, hlW * dp, mutableListOf(PointF(px, py)))
            Tool.LINE -> current = Item(Kind.LINE, penColor, penW * dp, mutableListOf(PointF(px, py), PointF(px, py)))
            Tool.ARROW -> current = Item(Kind.ARROW, penColor, penW * dp, mutableListOf(PointF(px, py), PointF(px, py)))
            Tool.RECT -> current = Item(Kind.RECT, penColor, penW * dp, mutableListOf(PointF(px, py), PointF(px, py)))
            Tool.OVAL -> current = Item(Kind.OVAL, penColor, penW * dp, mutableListOf(PointF(px, py), PointF(px, py)))
            Tool.TEXT -> {}
            Tool.LASER -> {
                val s = mutableListOf(LaserPt(scrX, scrY, SystemClock.uptimeMillis()))
                lasers.add(s); laserCur = s
            }
            Tool.ERASER -> { eraserPos = PointF(scrX, scrY); eraseAt(px, py) }
            Tool.ERASER_PART -> {
                eraserPos = PointF(scrX, scrY)
                current = Item(Kind.ERASE, 0, eraserW * dp, mutableListOf(PointF(px, py)))
            }
            Tool.MOVE -> { candidate = topHit(px, py); moving = null; lastPx = px; lastPy = py }
        }
        invalidate()
    }

    private fun move(px: Float, py: Float, scrX: Float, scrY: Float, p: Float) {
        val c = current
        prSmooth = prSmooth * 0.6f + p * 0.4f
        when (activeTool) {
            Tool.PEN, Tool.ERASER_PART -> {
                if (activeTool == Tool.ERASER_PART) eraserPos?.set(scrX, scrY)
                if (c != null) {
                    val l = c.pts.last()
                    if (hypot(px - l.x, py - l.y) > 0.6f * dp) {
                        c.pts.add(PointF(px, py)); c.pr?.add(prSmooth); c.path = null
                    }
                }
            }
            Tool.HIGHLIGHTER -> if (c != null) {
                if (rulerActive) {
                    if (c.pts.size < 2) c.pts.add(PointF(px, py)) else c.pts[1].set(px, py)
                    c.path = null
                } else {
                    val l = c.pts.last()
                    if (hypot(px - l.x, py - l.y) > 0.6f * dp) { c.pts.add(PointF(px, py)); c.path = null }
                }
            }
            Tool.LINE, Tool.ARROW, Tool.RECT, Tool.OVAL -> if (c != null) { c.pts[1].set(px, py); c.path = null }
            Tool.ERASER -> { eraserPos?.set(scrX, scrY); eraseAt(px, py) }
            Tool.LASER -> laserCur?.let { s ->
                if (!lasers.contains(s)) lasers.add(s)
                s.add(LaserPt(scrX, scrY, SystemClock.uptimeMillis()))
            }
            Tool.MOVE -> {
                val cand = candidate
                if (moving == null && cand != null && hypot(px - downPx, py - downPy) > 4f * dp) {
                    val idx = items.indexOf(cand)
                    if (idx >= 0) {
                        pushUndo()
                        val cp = Item(cand.kind, cand.color, cand.width,
                            cand.pts.map { PointF(it.x, it.y) }.toMutableList(), cand.text, cand.pr?.toMutableList())
                        items[idx] = cp
                        moving = cp
                        lastPx = downPx; lastPy = downPy
                    }
                }
                moving?.let { m ->
                    val dx = px - lastPx; val dy = py - lastPy
                    for (pt in m.pts) { pt.x += dx; pt.y += dy }
                    m.path = null
                    lastPx = px; lastPy = py
                }
            }
            Tool.TEXT -> {}
        }
        invalidate()
    }

    private fun end(cancelled: Boolean, scrX: Float, scrY: Float) {
        val c = current
        if (c != null && !cancelled) {
            val keep = when (c.kind) {
                Kind.LINE, Kind.ARROW, Kind.RECT, Kind.OVAL ->
                    hypot(c.pts[1].x - c.pts[0].x, c.pts[1].y - c.pts[0].y) > 3f * dp
                else -> true
            }
            if (keep) commit(c)
        }
        current = null
        laserCur = null
        eraserPos = null
        if (activeTool == Tool.TEXT && !cancelled && hypot(scrX - downSx, scrY - downSy) < 12f * dp) {
            editTarget = items.lastOrNull { it.kind == Kind.TEXT && boundsOf(it).contains(downPx, downPy) }
            onTextRequest?.invoke(downPx, downPy, editTarget?.text)
        }
        if (activeTool == Tool.MOVE && !cancelled && moving == null) {
            val cand = candidate
            if (cand != null && cand.kind == Kind.TEXT) {
                editTarget = cand
                onTextRequest?.invoke(cand.pts[0].x, cand.pts[0].y, cand.text)
            }
        }
        moving = null
        candidate = null
        invalidate()
    }

    private fun topHit(px: Float, py: Float): Item? = items.lastOrNull { hit(it, px, py, 8f * dp) }

    private fun eraseAt(px: Float, py: Float) {
        val r = eraserR
        val del = items.filter { hit(it, px, py, r) }
        if (del.isNotEmpty()) {
            if (!changed) { pushUndo(); changed = true }
            items.removeAll(del.toSet())
            invalidate()
        }
    }

    // ---------------- geometria ----------------
    private fun segDist(x: Float, y: Float, a: PointF, b: PointF): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0f) 0f else (((x - a.x) * dx + (y - a.y) * dy) / l2).coerceIn(0f, 1f)
        return hypot(x - (a.x + t * dx), y - (a.y + t * dy))
    }

    private fun polyHit(p: List<PointF>, x: Float, y: Float, tol: Float): Boolean {
        if (p.size == 1) return hypot(x - p[0].x, y - p[0].y) <= tol
        for (k in 1 until p.size) if (segDist(x, y, p[k - 1], p[k]) <= tol) return true
        return false
    }

    private fun hit(i: Item, x: Float, y: Float, r: Float): Boolean {
        val tol = r + i.width / 2f
        return when (i.kind) {
            Kind.ERASE -> false
            Kind.TEXT -> RectF(boundsOf(i)).apply { inset(-r, -r) }.contains(x, y)
            Kind.PEN, Kind.HL, Kind.LINE, Kind.ARROW -> polyHit(i.pts, x, y, tol)
            Kind.RECT -> {
                val a = i.pts[0]; val b = i.pts[1]
                polyHit(listOf(a, PointF(b.x, a.y), b, PointF(a.x, b.y), a), x, y, tol)
            }
            Kind.OVAL -> {
                val a = i.pts[0]; val b = i.pts[1]
                val cx = (a.x + b.x) / 2; val cy = (a.y + b.y) / 2
                val rx = abs(b.x - a.x) / 2; val ry = abs(b.y - a.y) / 2
                val poly = (0..48).map { k ->
                    val t = (k / 48.0 * 2 * PI).toFloat()
                    PointF(cx + rx * cos(t), cy + ry * sin(t))
                }
                polyHit(poly, x, y, tol)
            }
        }
    }

    private fun layoutOf(i: Item): StaticLayout {
        i.layout?.let { return it }
        val t = i.text ?: ""
        val tp = TextPaint(textPaint).apply { textSize = i.width }
        val w = min(ceil(Layout.getDesiredWidth(t, tp)).toInt() + 2, (260 * dp).toInt()).coerceAtLeast(10)
        val l = StaticLayout.Builder.obtain(t, 0, t.length, tp, w).build()
        i.layout = l
        return l
    }

    private fun boundsOf(i: Item): RectF {
        val l = layoutOf(i); val pad = 8 * dp
        val p = i.pts[0]
        return RectF(p.x, p.y, p.x + l.width + 2 * pad, p.y + l.height + 2 * pad)
    }

    private fun pathOf(i: Item): Path {
        i.path?.let { return it }
        val p = Path()
        when (i.kind) {
            Kind.PEN, Kind.HL, Kind.ERASE -> {
                val pts = i.pts
                p.moveTo(pts[0].x, pts[0].y)
                if (pts.size == 1) p.lineTo(pts[0].x + 0.01f, pts[0].y)
                else {
                    for (k in 1 until pts.size) {
                        val a = pts[k - 1]; val b = pts[k]
                        p.quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2)
                    }
                    p.lineTo(pts.last().x, pts.last().y)
                }
            }
            Kind.LINE -> { p.moveTo(i.pts[0].x, i.pts[0].y); p.lineTo(i.pts[1].x, i.pts[1].y) }
            Kind.ARROW -> {
                val a = i.pts[0]; val b = i.pts[1]
                p.moveTo(a.x, a.y); p.lineTo(b.x, b.y)
                val ang = atan2(b.y - a.y, b.x - a.x)
                val len = max(12f * dp, i.width * 4f)
                for (s in listOf(-1f, 1f)) {
                    val t = ang + PI.toFloat() + s * 0.5f
                    p.moveTo(b.x, b.y)
                    p.lineTo(b.x + len * cos(t), b.y + len * sin(t))
                }
            }
            Kind.RECT -> {
                val a = i.pts[0]; val b = i.pts[1]
                p.addRect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y), Path.Direction.CW)
            }
            Kind.OVAL -> {
                val a = i.pts[0]; val b = i.pts[1]
                p.addOval(RectF(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y)), Path.Direction.CW)
            }
            Kind.TEXT -> {}
        }
        i.path = p
        return p
    }

    // ---------------- desenho ----------------
    override fun onDraw(canvas: Canvas) {
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.translate(-sx, -sy)
        for (i in items) drawItem(canvas, i)
        current?.let { drawItem(canvas, it) }
        canvas.restoreToCount(layer)
        drawLasers(canvas)
        drawEraserRing(canvas)
    }

    private fun pf(p: Float) = 0.5f + 0.9f * p.coerceIn(0f, 1f)

    private fun drawItem(c: Canvas, i: Item) {
        if (i.kind == Kind.TEXT) { drawNote(c, i); return }
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = i.width
        val pr = i.pr
        if (i.kind == Kind.PEN && pr != null && pr.size == i.pts.size) {   // traço sensível à pressão
            paint.color = i.color
            if (i.pts.size == 1) {
                paint.strokeWidth = i.width * pf(pr[0])
                c.drawPoint(i.pts[0].x, i.pts[0].y, paint)
            } else {
                for (k in 1 until i.pts.size) {
                    paint.strokeWidth = i.width * pf((pr[k - 1] + pr[k]) / 2f)
                    c.drawLine(i.pts[k - 1].x, i.pts[k - 1].y, i.pts[k].x, i.pts[k].y, paint)
                }
            }
            return
        }
        when (i.kind) {
            Kind.HL -> { paint.color = i.color; paint.alpha = 100; paint.strokeCap = Paint.Cap.SQUARE }
            Kind.ERASE -> paint.xfermode = clearMode
            else -> paint.color = i.color
        }
        c.drawPath(pathOf(i), paint)
    }

    private fun drawNote(c: Canvas, i: Item) {
        val l = layoutOf(i); val pad = 8 * dp
        val b = boundsOf(i)
        paint.reset(); paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#FFF59D")
        c.drawRoundRect(b, 8 * dp, 8 * dp, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1 * dp
        paint.color = Color.parseColor("#FBC02D")
        c.drawRoundRect(b, 8 * dp, 8 * dp, paint)
        c.save(); c.translate(b.left + pad, b.top + pad); l.draw(c); c.restore()
    }

    private fun drawEraserRing(c: Canvas) {
        val pos = eraserPos ?: return
        paint.reset(); paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f * dp
        paint.color = Color.parseColor("#99000000")
        c.drawCircle(pos.x, pos.y, eraserR, paint)
        paint.strokeWidth = 1.2f * dp
        paint.color = Color.WHITE
        c.drawCircle(pos.x, pos.y, eraserR, paint)
    }

    private fun drawLasers(c: Canvas) {
        if (lasers.isEmpty()) return
        val life = 1500L
        val now = SystemClock.uptimeMillis()
        lasers.forEach { s -> s.removeAll { p -> now - p.t > life } }
        lasers.removeAll { it.isEmpty() && it !== laserCur }
        for (s in lasers) {
            for (pass in 0..1) {
                paint.reset(); paint.isAntiAlias = true
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = (if (pass == 0) laserW * 2.6f else laserW) * dp
                if (s.size == 1) {
                    paint.color = Color.argb(alphaFor(now - s[0].t, life, pass), 255, 23, 68)
                    c.drawPoint(s[0].x, s[0].y, paint)
                }
                for (k in 1 until s.size) {
                    paint.color = Color.argb(alphaFor(now - s[k].t, life, pass), 255, 23, 68)
                    c.drawLine(s[k - 1].x, s[k - 1].y, s[k].x, s[k].y, paint)
                }
            }
        }
        if (lasers.isNotEmpty()) postInvalidateOnAnimation()
    }

    private fun alphaFor(age: Long, life: Long, pass: Int): Int {
        val f = (1f - age.toFloat() / life).coerceIn(0f, 1f)
        return (f * (if (pass == 0) 70 else 255)).toInt()
    }
}
