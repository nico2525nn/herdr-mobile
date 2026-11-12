package io.github.sagernet.libghostty

/**
 * Turn finger travel into whole wheel notches without losing the sub-notch
 * remainder. A fling that exceeds [cap] deliberately drops the excess (the cap
 * is the flood guard), but ordinary deltas carry their fraction into the next
 * event, which matters for fractional agent strides.
 *
 * @return emitted notches (signed) and the accumulator left for next time.
 */
internal fun takeWheelNotches(
    accumulator: Float,
    rowsPerNotch: Float,
    cap: Int,
): Pair<Int, Float> {
    if (accumulator == 0f || !accumulator.isFinite()) return 0 to accumulator
    val stride = rowsPerNotch.coerceAtLeast(0.01f)
    val requested = (accumulator / stride).toInt()
    if (requested == 0) return 0 to accumulator
    val bound = cap.coerceAtLeast(1)
    val emitted = requested.coerceIn(-bound, bound)
    val remainder = if (emitted == requested) accumulator - requested * stride else 0f
    return emitted to remainder
}

/** Integer variant for the xterm mouse-tracking path (one notch = 3 rows). */
internal fun takeMouseWheelNotches(
    accumulator: Int,
    rowsPerNotch: Int,
    cap: Int,
): Pair<Int, Int> {
    val stride = rowsPerNotch.coerceAtLeast(1)
    val requested = accumulator / stride
    if (requested == 0) return 0 to accumulator
    val bound = cap.coerceAtLeast(1)
    val emitted = requested.coerceIn(-bound, bound)
    val remainder = if (emitted == requested) accumulator - requested * stride else 0
    return emitted to remainder
}
