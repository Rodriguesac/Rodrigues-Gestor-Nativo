package com.rodrigues.gestor.notifications

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.rodrigues.gestor.MainActivity
import com.rodrigues.gestor.R
import kotlin.math.abs

object FloatingPanelController {
    private const val POS_PREFS = "rodrigues_floating_panel_position"
    private var windowManager: WindowManager? = null
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var collapsed: LinearLayout? = null
    private var expanded: LinearLayout? = null
    private var activeBadge: TextView? = null
    private var metricCounts: List<TextView> = emptyList()
    private var isExpanded = false

    fun hasOverlayPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun enableOrRequest(context: Context): Boolean {
        AlertPreferences.setFloatingPanel(context, true)
        return if (hasOverlayPermission(context)) {
            sync(context)
            true
        } else {
            try {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
            }
            false
        }
    }

    fun disable(context: Context) {
        AlertPreferences.setFloatingPanel(context, false)
        hide(context)
    }

    fun sync(context: Context, summary: DailyOrderSummary = DailyOrderSummary.load(context)) {
        if (!AlertPreferences.floatingPanel(context) || !hasOverlayPermission(context)) {
            hide(context)
            return
        }
        if (root == null) show(context.applicationContext)
        update(summary)
    }

    fun hide(context: Context) {
        val view = root ?: return
        try {
            (windowManager ?: context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (_: Throwable) {
        }
        root = null
        collapsed = null
        expanded = null
        activeBadge = null
        metricCounts = emptyList()
        params = null
        windowManager = null
        isExpanded = false
    }

    private fun show(context: Context) {
        if (root != null || !hasOverlayPermission(context)) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val display = context.resources.displayMetrics
            val saved = context.getSharedPreferences(POS_PREFS, Context.MODE_PRIVATE)
            x = saved.getInt("x", (display.widthPixels - dp(context, 82)).coerceAtLeast(0))
            y = saved.getInt("y", dp(context, 140))
        }

        val container = FrameLayout(context)
        val compactView = buildCollapsed(context)
        val expandedView = buildExpanded(context)
        expandedView.visibility = View.GONE
        container.addView(compactView)
        container.addView(expandedView)
        installDrag(context, compactView, p)

        windowManager = wm
        params = p
        root = container
        collapsed = compactView
        expanded = expandedView
        try {
            wm.addView(container, p)
        } catch (_: Throwable) {
            root = null
            windowManager = null
            params = null
        }
    }

    private fun buildCollapsed(context: Context): LinearLayout {
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(context, 8), dp(context, 7), dp(context, 8), dp(context, 7))
            background = rounded(Color.WHITE, dp(context, 24).toFloat(), 0x225A078F, 1)
            elevation = dp(context, 8).toFloat()
        }
        val logo = ImageView(context).apply {
            setImageResource(R.drawable.app_logo)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        wrap.addView(logo, LinearLayout.LayoutParams(dp(context, 38), dp(context, 38)))
        val badge = TextView(context).apply {
            text = "0"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = rounded(0xFF5A078F.toInt(), dp(context, 15).toFloat())
        }
        wrap.addView(badge, LinearLayout.LayoutParams(dp(context, 30), dp(context, 30)).apply {
            marginStart = dp(context, 4)
        })
        activeBadge = badge
        return wrap
    }

    private fun buildExpanded(context: Context): LinearLayout {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12), dp(context, 11), dp(context, 12), dp(context, 12))
            background = rounded(Color.WHITE, dp(context, 20).toFloat(), 0x335A078F, 1)
            elevation = dp(context, 10).toFloat()
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(context).apply {
            setImageResource(R.drawable.app_logo)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(context, 34), dp(context, 34)))
        val title = TextView(context).apply {
            text = "Rodrigues Gestor • Hoje"
            setTextColor(0xFF20152B.toInt())
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(context, 8), 0, 0, 0)
        }
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val close = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6))
            setOnClickListener { toggleExpanded() }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(context, 34), dp(context, 34)))
        panel.addView(header, LinearLayout.LayoutParams(dp(context, 292), LinearLayout.LayoutParams.WRAP_CONTENT))

        val grid = GridLayout(context).apply {
            columnCount = 4
            rowCount = 2
            setPadding(0, dp(context, 10), 0, 0)
        }
        val specs = listOf(
            MetricSpec(R.drawable.ic_stat_new, "Novos"),
            MetricSpec(R.drawable.ic_stat_queue, "Fila"),
            MetricSpec(R.drawable.ic_stat_preparing, "Preparo"),
            MetricSpec(R.drawable.ic_stat_ready, "Prontos"),
            MetricSpec(R.drawable.ic_stat_delivery, "Entrega"),
            MetricSpec(R.drawable.ic_stat_completed, "Concluídos"),
            MetricSpec(R.drawable.ic_stat_canceled, "Cancelados"),
        )
        val counts = mutableListOf<TextView>()
        specs.forEach { spec ->
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(context, 4), dp(context, 7), dp(context, 4), dp(context, 7))
                background = rounded(0xFFF7F4FA.toInt(), dp(context, 13).toFloat())
                setOnClickListener { openApp(context) }
            }
            val top = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val icon = ImageView(context).apply {
                setImageResource(spec.icon)
                setColorFilter(0xFF5A078F.toInt())
            }
            top.addView(icon, LinearLayout.LayoutParams(dp(context, 20), dp(context, 20)))
            val count = TextView(context).apply {
                text = "0"
                setTextColor(0xFF20152B.toInt())
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(context, 5), 0, 0, 0)
            }
            top.addView(count)
            counts += count
            cell.addView(top)
            val label = TextView(context).apply {
                text = spec.label
                setTextColor(0xFF6B6172.toInt())
                textSize = 9f
                gravity = Gravity.CENTER
                maxLines = 1
            }
            cell.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = dp(context, 70)
                height = dp(context, 62)
                setMargins(dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 2))
            })
        }
        metricCounts = counts
        panel.addView(grid)

        val open = TextView(context).apply {
            text = "ABRIR GESTOR"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(context, 9), 0, dp(context, 9))
            background = rounded(0xFF5A078F.toInt(), dp(context, 12).toFloat())
            setOnClickListener { openApp(context) }
        }
        panel.addView(open, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 8)
        })
        return panel
    }

    private fun update(summary: DailyOrderSummary) {
        activeBadge?.text = summary.active.toString()
        val values = listOf(
            summary.newOrders,
            summary.queue,
            summary.preparing,
            summary.ready,
            summary.delivery,
            summary.completed,
            summary.canceled,
        )
        metricCounts.forEachIndexed { index, view -> view.text = values.getOrElse(index) { 0 }.toString() }
    }

    private fun toggleExpanded() {
        isExpanded = !isExpanded
        collapsed?.visibility = if (isExpanded) View.GONE else View.VISIBLE
        expanded?.visibility = if (isExpanded) View.VISIBLE else View.GONE
        try {
            root?.let { view -> params?.let { p -> windowManager?.updateViewLayout(view, p) } }
        } catch (_: Throwable) {
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installDrag(context: Context, handle: View, layoutParams: WindowManager.LayoutParams) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = layoutParams.x
                    startY = layoutParams.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    val metrics = context.resources.displayMetrics
                    layoutParams.x = (startX + dx).coerceIn(0, (metrics.widthPixels - dp(context, 70)).coerceAtLeast(0))
                    layoutParams.y = (startY + dy).coerceIn(0, (metrics.heightPixels - dp(context, 70)).coerceAtLeast(0))
                    try { root?.let { windowManager?.updateViewLayout(it, layoutParams) } } catch (_: Throwable) { }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.rawX - downRawX) > dp(context, 8) || abs(event.rawY - downRawY) > dp(context, 8)
                    context.getSharedPreferences(POS_PREFS, Context.MODE_PRIVATE).edit()
                        .putInt("x", layoutParams.x)
                        .putInt("y", layoutParams.y)
                        .apply()
                    if (!moved) toggleExpanded()
                    true
                }
                else -> false
            }
        }
    }

    private fun openApp(context: Context) {
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
        } catch (_: Throwable) {
        }
    }

    private fun rounded(color: Int, radius: Float, strokeColor: Int = Color.TRANSPARENT, strokeWidth: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private data class MetricSpec(val icon: Int, val label: String)
}
