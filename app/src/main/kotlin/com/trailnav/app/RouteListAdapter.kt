package com.trailnav.app

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView

/** Two-line, large-target route row required by the Phase 2 route list. */
internal class RouteListAdapter(
    private val context: Context,
    val items: MutableList<SavedRoute>,
) : BaseAdapter() {
    override fun getCount(): Int = items.size
    override fun getItem(position: Int): SavedRoute = items[position]
    override fun getItemId(position: Int): Long = items[position].addedAt

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            minimumHeight = dp(68)
            addView(TextView(context).apply { textSize = 18f })
            addView(TextView(context).apply { textSize = 14f })
        }
        val route = getItem(position)
        val title = row.getChildAt(0) as TextView
        val details = row.getChildAt(1) as TextView
        val readable = RouteCatalog.isReadable(context, route)
        title.text = route.displayName
        details.text = RouteCatalog.detailLine(route, readable)
        details.setTextColor(if (readable) Color.DKGRAY else 0xffb71c1c.toInt())
        row.contentDescription = "${route.displayName}, ${details.text}"
        return row
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
