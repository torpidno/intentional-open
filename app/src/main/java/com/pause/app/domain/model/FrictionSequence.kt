package com.pause.app.domain.model

/**
 * Moves [mode] by [offset] positions, clamping at either end. The returned list
 * keeps every other item in its current order.
 */
fun List<FrictionMode>.moveFrictionMode(
    mode: FrictionMode,
    offset: Int,
): List<FrictionMode> {
    val index = indexOf(mode)
    if (index < 0 || offset == 0) return this
    val target = (index + offset).coerceIn(0, lastIndex)
    if (target == index) return this
    return toMutableList().apply { add(target, removeAt(index)) }
}
