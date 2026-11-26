package com.trailnav.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

internal class RouteListAdapter(
    private val context: Context,
    val items: MutableList<SavedRoute>,
) : BaseAdapter() {
    var selectedUri: String? = null
    var onRouteClick: ((SavedRoute) -> Unit)? = null
    var onMenuClick: ((View, SavedRoute) -> Unit)? = null

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): SavedRoute = items[position]
    override fun getItemId(position: Int): Long = items[position].addedAt

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = (convertView as? LinearLayout)?.takeIf { it.tag is RowHolder } ?: createRow()
        val holder = row.tag as RowHolder
        val route = getItem(position)
        val readable = RouteCatalog.isReadable(context, route)
        val selected = isSelectedRoute(route, selectedUri)
        holder.indicator.text = if (selected) "●" else "○"
        holder.indicator.setTextColor(if (selected) SELECTED_GREEN else Color.DKGRAY)
        holder.title.text = route.displayName
        holder.title.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        holder.title.maxLines = 1
        holder.title.ellipsize = TextUtils.TruncateAt.END
        holder.length.text = String.format(Locale.US, "%.1f km", route.lengthMeters / 1_000.0)
        holder.unreadable.visibility = if (readable) View.GONE else View.VISIBLE
        holder.unreadable.text = context.getString(R.string.route_row_unreadable)
        holder.menu.contentDescription = context.getString(R.string.route_row_actions_description, route.displayName)
        row.background = GradientDrawable().apply {
            cornerRadius = dp(8f).toFloat()
            setColor(if (selected) SELECTED_BACKGROUND else Color.TRANSPARENT)
            setStroke(dp(2f), if (selected) SELECTED_GREEN else Color.TRANSPARENT)
        }
        row.contentDescription = listOfNotNull(
            route.displayName,
            holder.length.text.toString(),
            if (readable) null else context.getString(R.string.route_row_unreadable),
        ).joinToString(", ")
        row.setOnClickListener { onRouteClick?.invoke(route) }
        holder.menu.setOnClickListener { onMenuClick?.invoke(holder.menu, route) }
        return row
    }

    private fun createRow(): LinearLayout {
        val indicator = TextView(context).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            minWidth = dp(36f)
        }
        val title = TextView(context).apply { textSize = 18f }
        val length = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
        }
        val unreadable = TextView(context).apply {
            textSize = 14f
            setTextColor(0xffb71c1c.toInt())
            setPadding(dp(8f), 0, 0, 0)
        }
        val details = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(length, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(unreadable, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(details, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val menu = TextView(context).apply {
            text = "⋮"
            textSize = 24f
            gravity = Gravity.CENTER
            minWidth = dp(48f)
            minHeight = dp(48f)
            isClickable = true
            isFocusable = true
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            minimumHeight = dp(64f)
            addView(indicator, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
            addView(textColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(menu, LinearLayout.LayoutParams(dp(48f), dp(48f)))
            tag = RowHolder(indicator, title, length, unreadable, menu)
        }
    }

    private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).toInt()

    private data class RowHolder(
        val indicator: TextView,
        val title: TextView,
        val length: TextView,
        val unreadable: TextView,
        val menu: TextView,
    )

    private companion object {
        val SELECTED_GREEN = 0xff1f6b45.toInt()
        val SELECTED_BACKGROUND = 0xffedf6f0.toInt()
    }
}

internal fun isSelectedRoute(route: SavedRoute, selectedUri: String?): Boolean = route.uri == selectedUri
