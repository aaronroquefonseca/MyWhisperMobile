package com.mywhisper.mobile

/** Idle appearance is independent of dictation mode and visibility. */
object BubbleAppearance {
    val sizes = listOf(38, 44, 50, 58, 66)
    val idleDelayOptionsMs = (2..10).map { it * 1000 }
    const val IDLE_DELAY_DEFAULT_MS = 5000
    fun idleDelayMs(stored: Int) = if (stored in idleDelayOptionsMs) stored else IDLE_DELAY_DEFAULT_MS
    fun seconds(ms: Int): String = java.text.NumberFormat.getInstance().apply { maximumFractionDigits = 1 }.format(ms / 1000.0)
    val gripColor = 0xFF9E9E9E.toInt()
    fun size(index: Int) = sizes.getOrElse(index) { sizes[2] }
    /** Extra small rests at the XS button's reduced size; the stored size index is kept for when it turns off. */
    fun buttonSize(index: Int, extraSmall: Boolean) = if (extraSmall) sizes.first() else size(index)
    enum class Shape { FULL, SMALL, DOT }
    enum class IdleBehavior(val key: String) {
        FULL("full"), SMALL_AFTER_DELAY("small"), DOT_AFTER_DELAY("dot"), ALWAYS_DOT("always_dot");
        val usesDot get() = this == DOT_AFTER_DELAY || this == ALWAYS_DOT
        val usesDelay get() = this == SMALL_AFTER_DELAY || this == DOT_AFTER_DELAY
    }
    fun idleBehavior(stored: String?): IdleBehavior =
        IdleBehavior.entries.firstOrNull { it.key == stored } ?: IdleBehavior.FULL

    /** Grip pauses dot choices, Extra small pauses Shrink smaller; the stored choice returns when they turn off. */
    fun effective(behavior: IdleBehavior, grip: Boolean, extraSmall: Boolean): IdleBehavior =
        if ((grip && behavior.usesDot) || (extraSmall && behavior == IdleBehavior.SMALL_AFTER_DELAY)) IdleBehavior.FULL else behavior

    fun shape(behavior: IdleBehavior, elapsed: Boolean, search: Boolean,
              shrinkSearch: Boolean, grip: Boolean, extraSmall: Boolean, busy: Boolean): Shape {
        if (busy) return Shape.FULL
        val rest = if (extraSmall) Shape.SMALL else Shape.FULL
        val active = effective(behavior, grip, extraSmall)
        if (active == IdleBehavior.FULL) return rest
        if (active == IdleBehavior.ALWAYS_DOT) return Shape.DOT
        if (!elapsed && !(search && shrinkSearch)) return rest
        return if (active == IdleBehavior.DOT_AFTER_DELAY) Shape.DOT else Shape.SMALL
    }
    /**
     * Full-shape corner radius, in the same unit as [diameter] ([unit] = 1dp in that unit).
     * Adaptive: fixed 16dp, so smaller sizes look rounder. Uniform: 32% of the size, the same shape at every size.
     */
    fun cornerRadius(diameter: Float, unit: Float, uniform: Boolean): Float =
        minOf(diameter / 2, if (uniform) maxOf(diameter * 0.32f, 7 * unit) else 16 * unit)
    fun uniformShape(stored: String?) = stored == "uniform"
    /** Only the full button keeps its corners; shrunk shapes are circles. */
    fun roundness(shape: Shape): Float = if (shape == Shape.FULL) 0f else 1f
    fun diameter(size: Int, shape: Shape): Float = when (shape) {
        Shape.FULL -> size.toFloat()
        Shape.SMALL -> size * 0.64f
        Shape.DOT -> 14f
    }
    private val searchPattern = Regex("(^|[^a-z])(search|query|buscar|recherche|suchen)([^a-z]|$)", RegexOption.IGNORE_CASE)

    // Editor metadata only; never inspect the user's entered text.
    fun isSearch(inputType: Int, viewId: String?, hint: String?): Boolean =
        inputType and 0xfff == 0xb1 ||
            searchPattern.containsMatchIn(listOfNotNull(viewId, hint).joinToString(" "))
}
