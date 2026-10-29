package vip.wangxiaoming.music.ui.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatTextView

/**
 * 同步跑马灯 TextView（P2 从原版反编译结果还原后重写为 Kotlin）。
 *
 * 原版行为要点：
 *  - 速度固定 42 px/s；
 *  - 起播延迟 1200 ms，最短时长 2500 ms；
 *  - 尾部额外留 60 px，滚完停 1200 ms 再从头开始；
 *  - setPeerView 绑定另一行（如歌手名）：两行中任意一行溢出就一起滚，速度与相位一致。
 *
 * P2 只保证编译通过 + 单行截断 + 联动接口存在；完整视觉验收在 P6 播放器阶段。
 */
class SynchronizedMarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    companion object {
        private const val SPEED_PX_PER_SEC = 42f
        private const val TAIL_PADDING_PX = 60f
        private const val START_DELAY_MS = 1200L
        private const val MIN_DURATION_MS = 2500L
    }

    private var animator: ValueAnimator? = null
    private var isRolling = false
    private var scrollDistance = 0f
    private var peerView: SynchronizedMarqueeTextView? = null

    init {
        isSingleLine = true
        ellipsize = null
    }

    fun setPeerView(peer: SynchronizedMarqueeTextView?) {
        peerView = peer
    }

    private fun measuredTextWidth(): Float {
        val t = text ?: return 0f
        if (t.isEmpty()) return 0f
        return paint.measureText(t.toString())
    }

    private fun contentWidth(): Int = (width - paddingLeft - paddingRight).coerceAtLeast(0)

    fun isOverflowing(): Boolean {
        val w = contentWidth()
        return w > 0 && measuredTextWidth() > w
    }

    fun startCoordinatedMarquee() {
        post { startInternal() }
    }

    private fun startInternal() {
        stopMarquee()
        peerView?.stopMarquee()

        val w1 = contentWidth()
        val t1 = measuredTextWidth()
        val peer = peerView
        val w2 = peer?.contentWidth() ?: 0
        val t2 = peer?.measuredTextWidth() ?: 0f

        val selfOverflow = w1 > 0 && t1 > w1
        val peerOverflow = w2 > 0 && t2 > w2

        when {
            selfOverflow && !peerOverflow -> startSingle(this, t1)
            !selfOverflow && peerOverflow && peer != null -> peer.startSingle(peer, t2)
            selfOverflow && peerOverflow -> startDual(this, peer!!, t1, t2)
            else -> Unit
        }
    }

    private fun startSingle(target: SynchronizedMarqueeTextView, textWidth: Float) {
        val travel = textWidth + TAIL_PADDING_PX
        val duration = ((travel / SPEED_PX_PER_SEC) * 1000f).toLong().coerceAtLeast(MIN_DURATION_MS)

        val anim = ValueAnimator.ofFloat(0f, travel).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            startDelay = START_DELAY_MS
            addUpdateListener {
                target.scrollDistance = it.animatedValue as Float
                target.invalidate()
            }
            addListener(loopListener(target))
        }
        target.animator = anim
        target.isRolling = true
        anim.start()
    }

    private fun startDual(
        a: SynchronizedMarqueeTextView,
        b: SynchronizedMarqueeTextView,
        widthA: Float,
        widthB: Float,
    ) {
        val travel = maxOf(widthA, widthB) + TAIL_PADDING_PX
        val duration = ((travel / SPEED_PX_PER_SEC) * 1000f).toLong().coerceAtLeast(MIN_DURATION_MS)

        val anim = ValueAnimator.ofFloat(0f, travel).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            startDelay = START_DELAY_MS
            addUpdateListener {
                val d = it.animatedValue as Float
                a.scrollDistance = d
                a.invalidate()
                b.scrollDistance = d
                b.invalidate()
            }
            addListener(loopListener(a, b))
        }
        a.animator = anim
        a.isRolling = true
        b.isRolling = true
        anim.start()
    }

    private fun loopListener(vararg views: SynchronizedMarqueeTextView) = object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            val first = views.first()
            if (!first.isRolling) return
            views.forEach {
                it.scrollDistance = 0f
                it.invalidate()
            }
            first.postDelayed({
                if (first.isRolling) first.animator?.start()
            }, START_DELAY_MS)
        }
    }

    fun stopMarquee() {
        animator?.cancel()
        animator = null
        isRolling = false
        scrollDistance = 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopMarquee()
    }

    override fun onDraw(canvas: Canvas) {
        val t = text
        if (!isRolling || scrollDistance == 0f || t.isNullOrEmpty()) {
            super.onDraw(canvas)
            return
        }
        val p = paint
        p.color = currentTextColor
        val baseline = height / 2f - (p.descent() + p.ascent()) / 2f
        val x = paddingLeft - scrollDistance
        val textWidth = measuredTextWidth()
        if (textWidth + x <= 0f || x >= width) return
        canvas.drawText(t.toString(), x, baseline, p)
    }
}