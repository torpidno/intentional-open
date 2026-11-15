package com.pause.app.domain.model

import androidx.compose.ui.graphics.ImageBitmap

/**
 * A launchable app on the device, resolved from PackageManager.
 *
 * The icon is decoded to an [ImageBitmap] once on a background thread so lists
 * and the pause screen can draw it without touching PackageManager again.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap? = null,
)
