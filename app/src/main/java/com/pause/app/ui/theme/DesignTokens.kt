package com.pause.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Spacing follows an 8 dp grid. The 4 dp token is only for optical gaps inside
 * one control (icon to label); page structure uses 8/16/24/32.
 */
object PauseDimens {
    val space4 = 4.dp
    val space8 = 8.dp
    val space16 = 16.dp
    val space24 = 24.dp
    val space32 = 32.dp
    val space48 = 48.dp

    /** WCAG / Material minimum interactive size. */
    val touchTarget = 48.dp

    /** Keeps prose readable on tablets instead of stretching edge to edge. */
    val contentMaxWidth = 640.dp
    val readingMaxWidth = 560.dp
}
