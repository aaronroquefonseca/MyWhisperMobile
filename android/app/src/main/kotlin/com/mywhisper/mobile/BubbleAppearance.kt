package com.mywhisper.mobile

/** Idle appearance is independent of dictation mode and visibility. */
object BubbleAppearance {
    val sizes = listOf(38, 44, 50, 58, 66)
    const val IDLE_DELAY_MS = 2000L
    val gripColor = 0xFF9E9E9E.toInt()
    fun size(index: Int) = sizes.getOrElse(index) { sizes[2] }
    enum class Shape { FULL, SMALL, DOT }
    enum class IdleBehavior(val key: String) {
        FULL("full"), SMALL_AFTER_DELAY("small"), DOT_AFTER_DELAY("dot"), ALWAYS_DOT("always_dot");
        val usesDot get() = this == DOT_AFTER_DELAY || this == ALWAYS_DOT
        val usesDelay get() = this == SMALL_AFTER_DELAY || this == DOT_AFTER_DELAY
    }
    fun idleBehavior(stored: String?): IdleBehavior =
        IdleBehavior.entries.firstOrNull { it.key == stored } ?: IdleBehavior.FULL

    fun withGrip(behavior: IdleBehavior, grip: Boolean): IdleBehavior =
        if (grip && behavior.usesDot) IdleBehavior.FULL else behavior

    fun shape(behavior: IdleBehavior, elapsed: Boolean, search: Boolean,
              shrinkSearch: Boolean, grip: Boolean, busy: Boolean): Shape {
        if (busy || behavior == IdleBehavior.FULL || (grip && behavior.usesDot)) return Shape.FULL
        if (behavior == IdleBehavior.ALWAYS_DOT) return Shape.DOT
        if (!elapsed && !(search && shrinkSearch)) return Shape.FULL
        return if (behavior == IdleBehavior.DOT_AFTER_DELAY) Shape.DOT else Shape.SMALL
    }
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
