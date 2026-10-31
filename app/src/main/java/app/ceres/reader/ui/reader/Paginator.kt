package app.ceres.reader.ui.reader

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ReplacementSpan
import app.ceres.reader.data.ReaderSettings

data class PageSpec(val width: Int, val height: Int, val density: Float, val fontScale: Float)

/** A run of lines of one block (title or paragraph) placed on a page. */
class PageItem(
    val layout: StaticLayout,
    val lineStart: Int,
    val lineEnd: Int,
    /** Top of the first line, relative to the content area. */
    val top: Float,
)

/** Tap target for a paragraph comment badge, in page pixels. paraIndex is 1-based. */
class BadgeHit(val paraIndex: Int, val rect: RectF)

class TextPage(
    val items: List<PageItem>,
    /** Character position in the chapter where this page starts. */
    val startPos: Int,
    /** First paragraph (0-based) whose text starts or continues on this page; -1 for title-only. */
    val firstParagraph: Int,
    val badges: List<BadgeHit>,
)

class PageMetrics(
    val paddingLeft: Float,
    val contentTop: Float,
    val contentWidth: Int,
    val contentHeight: Int,
    val headerBaseline: Float,
    val footerBaseline: Float,
    val infoPaint: TextPaint,
)

private const val INDENT = "\u3000\u3000"

/** Inline badge showing the comment count; drawn by StaticLayout itself. */
private class BadgeSpan(
    private val text: String,
    private val paint: TextPaint,
    private val density: Float,
    private val strokeColor: Int,
) : ReplacementSpan() {
    private val padH = 5 * density
    private val gap = 4 * density
    val width: Int = (paint.measureText(text) + padH * 2 + gap).toInt()

    override fun getSize(p: Paint, t: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int = width

    override fun draw(
        canvas: Canvas, t: CharSequence?, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, p: Paint,
    ) {
        val h = paint.textSize + 6 * density
        val cy = y + (p.ascent() + p.descent()) / 2f
        val rect = RectF(x + gap, cy - h / 2, x + width, cy + h / 2)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.2f * density; color = strokeColor
        }
        canvas.drawRoundRect(rect, 4 * density, 4 * density, stroke)
        val baseline = rect.centerY() - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(text, rect.left + padH, baseline, paint)
    }
}

object Paginator {

    private fun inkColor(s: ReaderSettings): Int =
        if (s.invertColors) Color.WHITE else Color.BLACK

    fun metrics(spec: PageSpec, s: ReaderSettings): PageMetrics {
        val d = spec.density
        val infoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor(s); textSize = 12 * d * spec.fontScale
        }
        val padH = s.paddingHorizontalDp * d
        val padV = s.paddingVerticalDp * d
        val infoH = infoPaint.textSize + 10 * d
        val contentTop = padV + infoH
        val contentHeight = (spec.height - contentTop - padV - infoH).toInt().coerceAtLeast(100)
        return PageMetrics(
            paddingLeft = padH,
            contentTop = contentTop,
            contentWidth = (spec.width - padH * 2).toInt().coerceAtLeast(100),
            contentHeight = contentHeight,
            headerBaseline = padV + infoPaint.textSize,
            footerBaseline = spec.height - padV,
            infoPaint = infoPaint,
        )
    }

    /** Character offset of each paragraph start, matching [TextPage.startPos]. */
    fun paragraphOffsets(paragraphs: List<String>): IntArray {
        val out = IntArray(paragraphs.size)
        var pos = 0
        paragraphs.forEachIndexed { i, p -> out[i] = pos; pos += p.length + 1 }
        return out
    }

    fun paginate(
        title: String,
        paragraphs: List<String>,
        reviewCounts: Map<Int, Int>,
        spec: PageSpec,
        s: ReaderSettings,
    ): List<TextPage> {
        val d = spec.density
        val m = metrics(spec, s)
        val ink = inkColor(s)
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = s.fontSizeSp * d * spec.fontScale
            typeface = if (s.boldText) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val titlePaint = TextPaint(bodyPaint).apply {
            textSize = bodyPaint.textSize * 1.35f
            typeface = Typeface.DEFAULT_BOLD
        }
        val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; textSize = bodyPaint.textSize * 0.6f; typeface = Typeface.DEFAULT_BOLD
        }
        val width = m.contentWidth
        val paraGap = s.paragraphSpacingDp * d
        val offsets = paragraphOffsets(paragraphs)

        fun build(text: CharSequence, paint: TextPaint, spacing: Float): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, spacing)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .build()

        class Block(
            val layout: StaticLayout,
            /** 0-based content paragraph index; -1 for title. */
            val paraIndex: Int,
            val badgeWidth: Int,
            /** Review map key for the badge (0 = chapter total, else 1-based para). */
            val badgeTarget: Int = 0,
        )

        val chapterTotal = reviewCounts[0] ?: 0
        val blocks = ArrayList<Block>(paragraphs.size + 1)
        blocks.add(Block(build(title, titlePaint, 1.2f), -1, 0))
        paragraphs.forEachIndexed { i, p ->
            val paraIndex = i + 1
            // 章节汇总画在第一段末尾；该段若另有逐段计数则仍用汇总（番茄第一段通常无独立计数）。
            val (count, badgeTarget) = if (i == 0 && chapterTotal > 0) {
                chapterTotal to 0
            } else {
                (reviewCounts[paraIndex] ?: 0) to paraIndex
            }
            if (count > 0) {
                val label = if (count > 999) "999+" else count.toString()
                val span = BadgeSpan(label, badgePaint, d, ink)
                val text = SpannableString("$INDENT$p\uFFFC")
                text.setSpan(span, text.length - 1, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                blocks.add(Block(build(text, bodyPaint, s.lineSpacing), i, span.width, badgeTarget))
            } else {
                blocks.add(Block(build("$INDENT$p", bodyPaint, s.lineSpacing), i, 0))
            }
        }

        val pages = ArrayList<TextPage>()
        var items = ArrayList<PageItem>()
        var badges = ArrayList<BadgeHit>()
        var pageStart = 0
        var firstPara = -1
        var y = 0f
        val height = m.contentHeight.toFloat()

        fun flush() {
            if (items.isNotEmpty()) pages.add(TextPage(items, pageStart, firstPara, badges))
            items = ArrayList(); badges = ArrayList(); y = 0f
        }

        fun posOf(b: Block, line: Int): Int =
            if (b.paraIndex < 0) 0
            else offsets[b.paraIndex] + (b.layout.getLineStart(line) - INDENT.length).coerceAtLeast(0)

        for ((bi, b) in blocks.withIndex()) {
            val layout = b.layout
            if (items.isNotEmpty()) y += if (bi == 1) paraGap * 2 else paraGap
            var line = 0
            while (line < layout.lineCount) {
                if (items.isEmpty()) {
                    pageStart = posOf(b, line)
                    firstPara = b.paraIndex
                }
                val top = layout.getLineTop(line)
                var end = line
                while (end < layout.lineCount && y + (layout.getLineBottom(end) - top) <= height) end++
                if (end == line) {
                    if (items.isEmpty()) end = line + 1 else {
                        flush(); continue
                    }
                }
                items.add(PageItem(layout, line, end, y))
                if (b.badgeWidth > 0 && end == layout.lineCount) {
                    val last = layout.lineCount - 1
                    val x = layout.getPrimaryHorizontal(layout.text.length - 1)
                    val lineTop = y + (layout.getLineTop(last) - top)
                    val lineBottom = y + (layout.getLineBottom(last) - top)
                    badges.add(
                        BadgeHit(
                            b.badgeTarget,
                            RectF(
                                m.paddingLeft + x, m.contentTop + lineTop,
                                m.paddingLeft + x + b.badgeWidth, m.contentTop + lineBottom,
                            ),
                        )
                    )
                }
                y += layout.getLineBottom(end - 1) - top
                line = end
                if (line < layout.lineCount) flush()
            }
        }
        flush()
        if (pages.isEmpty()) pages.add(TextPage(emptyList(), 0, -1, emptyList()))
        return pages
    }

    fun draw(
        canvas: Canvas,
        page: TextPage,
        m: PageMetrics,
        header: String,
        footerLeft: String,
        footerRight: String,
        width: Int,
        clock: String? = null,
    ) {
        for (item in page.items) {
            val l = item.layout
            val lineTop = l.getLineTop(item.lineStart).toFloat()
            val lineBottom = l.getLineBottom(item.lineEnd - 1).toFloat()
            canvas.save()
            canvas.translate(m.paddingLeft, m.contentTop + item.top - lineTop)
            canvas.clipRect(0f, lineTop, l.width.toFloat() + 1, lineBottom)
            l.draw(canvas)
            canvas.restore()
        }
        val p = m.infoPaint
        val contentW = width - m.paddingLeft * 2
        val clockW = clock?.let { p.measureText(it) + 12f } ?: 0f
        canvas.drawText(ellipsize(header, p, contentW - clockW), m.paddingLeft, m.headerBaseline, p)
        if (clock != null) {
            canvas.drawText(clock, width - m.paddingLeft - p.measureText(clock), m.headerBaseline, p)
        }
        canvas.drawText(ellipsize(footerLeft, p, width * 0.6f), m.paddingLeft, m.footerBaseline, p)
        val rw = p.measureText(footerRight)
        canvas.drawText(footerRight, width - m.paddingLeft - rw, m.footerBaseline, p)
    }

    private fun ellipsize(text: String, paint: TextPaint, max: Float): String {
        if (paint.measureText(text) <= max) return text
        var end = text.length
        while (end > 0 && paint.measureText(text, 0, end) + paint.measureText("…") > max) end--
        return text.substring(0, end) + "…"
    }
}
