package com.thorpad.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Which face she is wearing.
 *
 * Three, because three is what the art has and three is what the app needs: one
 * for resting, one for saying something, one for something having gone right.
 */
enum class Mood(val drawable: Int) {
    CALM(R.drawable.mascot_calm),
    TALKING(R.drawable.mascot_talk),
    PLEASED(R.drawable.mascot_happy),
}

/**
 * The bubble she speaks in.
 *
 * Drawn rather than a nine-patch so the tail can point at her from whichever
 * side she is standing on, and so it can size itself to the text instead of
 * the text being squeezed into a fixed box.
 */
class SpeechBubble @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var message: String = ""
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    /** Which side the tail comes out of, towards the mascot. */
    var tailOnRight: Boolean = true

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#1B2733")
    }
    private val ink = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1B2733")
        // Scaled, not a raw pixel count: 38px is comfortable on a 2x screen and
        // unreadable on a 4x one, and this text is the whole point of the view.
        textSize = 13f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private val density = resources.displayMetrics.density
    private val padding = 10f * density
    private val tail = 8f * density
    private val radius = 10f * density

    private var layout: StaticLayout? = null

    private fun layoutFor(width: Int): StaticLayout {
        val usable = (width - padding * 2 - tail).toInt().coerceAtLeast(1)
        return StaticLayout.Builder
            .obtain(message, 0, message.length, ink, usable)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(4f, 1f)
            .build()
            .also { layout = it }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        // A weighted child is measured once with nothing to go on before it is
        // measured with its real width. Laying the text out at one pixel wide
        // makes a column of single letters thousands of pixels tall, and the
        // row can keep that height — so the first pass gets a guess instead.
        val given = MeasureSpec.getSize(widthSpec)
        val width = if (given > tail + padding * 2) {
            given
        } else {
            (resources.displayMetrics.widthPixels * 0.6f).toInt()
        }
        val text = layoutFor(width)
        setMeasuredDimension(
            width,
            (text.height + padding * 2).toInt().coerceAtLeast(1),
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (message.isEmpty()) return
        val text = layout ?: layoutFor(width)

        val body = if (tailOnRight) {
            RectF(0f, 0f, width - tail, height.toFloat())
        } else {
            RectF(tail, 0f, width.toFloat(), height.toFloat())
        }

        val path = Path().apply {
            addRoundRect(body, radius, radius, Path.Direction.CW)
            // The tail, pointing at whoever is speaking. Set a third of the way
            // down rather than centred: a bubble is read top-first, and a tail
            // level with the first line reads as belonging to it.
            val y = height * 0.33f
            if (tailOnRight) {
                moveTo(body.right, y - tail * 0.55f)
                lineTo(body.right + tail, y)
                lineTo(body.right, y + tail * 0.55f)
            } else {
                moveTo(body.left, y - tail * 0.55f)
                lineTo(body.left - tail, y)
                lineTo(body.left, y + tail * 0.55f)
            }
            close()
        }

        canvas.drawPath(path, fill)
        canvas.drawPath(path, edge)

        canvas.save()
        canvas.translate(body.left + padding, padding)
        text.draw(canvas)
        canvas.restore()
    }
}

/**
 * The mascot and her bubble, side by side.
 *
 * She only ever says one thing at a time, and it clears itself. A mascot that
 * accumulates messages becomes a log with a face on it, and a log is the thing
 * this app already has.
 */
class MascotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val bubble = SpeechBubble(context)
    private val portrait = ImageView(context)

    /** Cleared after this long, unless something else is said first. */
    var lingerMs: Long = 3800

    private var clearAt: Runnable? = null
    private val restTo: Mood = Mood.CALM

    var onTapped: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        // Bottom-aligned so she stands on the corner and the bubble floats
        // beside her head rather than her being pinned to the bubble's top.
        gravity = android.view.Gravity.BOTTOM

        bubble.tailOnRight = true
        addView(
            bubble,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                bottomMargin = dp(40)
                marginEnd = dp(4)
            },
        )

        portrait.adjustViewBounds = true
        portrait.scaleType = ImageView.ScaleType.FIT_END
        portrait.setImageResource(Mood.CALM.drawable)
        // A quarter of the screen's shorter side. Big enough that the face is
        // doing the work an expression is for — at a thumbnail she is a smudge
        // with a hairstyle, and the three moods are indistinguishable.
        val short = minOf(
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels,
        )
        addView(portrait, LayoutParams((short * 0.25f).toInt(), LayoutParams.WRAP_CONTENT))

        portrait.setOnClickListener { onTapped?.invoke() }
        bubble.setOnClickListener { onTapped?.invoke() }
        visibility = GONE
    }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()

    val speaking: Boolean get() = visibility == VISIBLE && bubble.message.isNotEmpty()

    /**
     * Says something.
     *
     * @param sticky keeps it up until something replaces it — for a tutorial
     *   step, which the reader finishes rather than the clock.
     */
    fun say(message: String, mood: Mood = Mood.TALKING, sticky: Boolean = false) {
        clearAt?.let { removeCallbacks(it) }
        clearAt = null

        bubble.message = message
        portrait.setImageResource(mood.drawable)
        visibility = VISIBLE

        if (sticky) return
        val clear = Runnable {
            bubble.message = ""
            portrait.setImageResource(restTo.drawable)
            visibility = GONE
        }
        clearAt = clear
        postDelayed(clear, lingerMs)
    }

    fun hide() {
        clearAt?.let { removeCallbacks(it) }
        clearAt = null
        bubble.message = ""
        visibility = GONE
    }
}

/**
 * What she says while walking someone through it.
 *
 * Written as a sequence rather than one wall of text because each step is a
 * thing to go and do, and a tutorial you read all at once is a tutorial you
 * have finished reading before you have done any of it.
 */
data class TutorialStep(val mood: Mood, val text: String)

object Tutorial {

    val steps: List<TutorialStep> = listOf(
        TutorialStep(
            Mood.PLEASED,
            "Hi! I'm here to get your gamepad talking to a game that only " +
                "understands fingers. Tap me to go on.",
        ),
        TutorialStep(
            Mood.TALKING,
            "First, two permissions. Accessibility is what lets me actually " +
                "touch the screen — it's the only way an app can do that " +
                "without root.",
        ),
        TutorialStep(
            Mood.TALKING,
            "Then 'draw over other apps', so I can show the controls on top " +
                "of your game. I never take focus, so back and volume keep " +
                "working.",
        ),
        TutorialStep(
            Mood.TALKING,
            "Now press Start, then Add a button. Go into Edit, tap the " +
                "control, and press the gamepad button you want. That's the " +
                "binding done.",
        ),
        TutorialStep(
            Mood.PLEASED,
            "Careful here: while you're editing, I can't reach the game — the " +
                "overlay has to catch your finger so you can drag things. Tap " +
                "DONE when you're placed.",
        ),
        TutorialStep(
            Mood.TALKING,
            "If a tap lights up in the game but the button ignores it, make " +
                "the tap longer. Games read touches once a frame, and a short " +
                "one can start and finish inside the same frame.",
        ),
        TutorialStep(
            Mood.TALKING,
            "For shooting, set the control to Hold. Then the touch lasts " +
                "exactly as long as you hold the button — press to leave " +
                "cover, let go to drop back in.",
        ),
        TutorialStep(
            Mood.TALKING,
            "Sticks are fussier. Below Android 14 nothing can see them " +
                "without help, so use Shizuku — it reads the stick from the " +
                "kernel and costs your game nothing.",
        ),
        TutorialStep(
            Mood.PLEASED,
            "That's everything. I'll pipe up whenever you change something, " +
                "so you can tell what actually took. Good hunting!",
        ),
    )
}
