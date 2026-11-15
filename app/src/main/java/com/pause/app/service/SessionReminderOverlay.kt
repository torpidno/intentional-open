package com.pause.app.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.pause.app.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A small, non-blocking reminder card shown when a session limit expires.
 *
 * Deliberately built with platform Views instead of Compose: it is a few
 * milliseconds of UI work added directly to WindowManager from the
 * accessibility service, and it must not require an Activity lifecycle.
 *
 * Window type:
 *  - TYPE_APPLICATION_OVERLAY when the user granted "Display over other apps".
 *  - TYPE_ACCESSIBILITY_OVERLAY otherwise, which an accessibility service is
 *    allowed to use without the app-level overlay grant.
 */
@Singleton
class SessionReminderOverlay @Inject constructor() {

    private var root: View? = null
    private var windowManager: WindowManager? = null

    fun show(
        host: Context,
        appLabel: String,
        onCloseApp: () -> Unit,
        onDismiss: () -> Unit,
    ): Boolean {
        hide()
        val manager = host.getSystemService(WindowManager::class.java) ?: return false
        val density = host.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val windowType = if (Settings.canDrawOverlays(host)) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Without FLAG_LAYOUT_IN_SCREEN the window manager keeps the card
            // inside the content area, below the status bar and cutouts.
            gravity = Gravity.TOP
            y = dp(8)
        }

        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(10))
            elevation = dp(12).toFloat()
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#F21A211D"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
        }

        val title = TextView(host).apply {
            text = host.getString(R.string.session_reminder_title)
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }

        val body = TextView(host).apply {
            text = host.getString(R.string.session_reminder_body, appLabel)
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 14f
            setPadding(0, dp(4), 0, 0)
        }

        val actions = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(6), 0, 0)
        }

        val dismiss = TextView(host).apply {
            text = host.getString(R.string.session_reminder_keep_going)
            setTextColor(Color.parseColor("#B3FFFFFF"))
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener {
                hide()
                onDismiss()
            }
        }

        val closeApp = TextView(host).apply {
            text = host.getString(R.string.session_reminder_close_app)
            setTextColor(Color.parseColor("#A8D5BA"))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener {
                hide()
                onCloseApp()
            }
        }

        actions.addView(dismiss)
        actions.addView(closeApp)
        card.addView(title)
        card.addView(body)
        card.addView(actions)

        val container = FrameLayout(host).apply {
            setPadding(dp(16), 0, dp(16), 0)
        }
        container.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        return runCatching {
            manager.addView(container, params)
            windowManager = manager
            root = container
            container.alpha = 0f
            container.translationY = -dp(16).toFloat()
            container.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180L)
                .start()
            true
        }.getOrDefault(false)
    }

    fun hide() {
        val view = root ?: return
        root = null
        view.animate().cancel()
        runCatching { windowManager?.removeView(view) }
        windowManager = null
    }
}
