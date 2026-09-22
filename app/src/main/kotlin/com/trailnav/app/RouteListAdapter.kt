package com.trailnav.app

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView

/** Two-line, large-target route row required by the Phase 2 route list. */
internal class RouteListAdapter(
    private val context: Context,
    val items: MutableList<SavedRoute>,
    private val onDeleteClicked: (SavedRoute) -> Unit,
) : BaseAdapter() {
    var selectedUri: String? = null

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): SavedRoute = items[position]
    override fun getItemId(position: Int): Long = items[position].addedAt

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = (convertView as? LinearLayout)?.takeIf { it.tag == ROW_TAG } ?: LinearLayout(context).apply {
            tag = ROW_TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            minimumHeight = dp(68)
            val textColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { textSize = 18f })
                addView(TextView(context).apply { textSize = 14f })
            }
            addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(context).apply {
                text = "삭제"
                setAllCaps(false)
                textSize = 16f
                minimumWidth = dp(72)
                minimumHeight = dp(48)
            })
        }
        val route = getItem(position)
        val textColumn = row.getChildAt(0) as LinearLayout
        val title = textColumn.getChildAt(0) as TextView
        val details = textColumn.getChildAt(1) as TextView
        val delete = row.getChildAt(1) as Button
        val readable = RouteCatalog.isReadable(context, route)
        val selected = isSelectedRoute(route, selectedUri)
        title.text = if (selected) "✓ ${route.displayName}" else route.displayName
        title.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        row.setBackgroundColor(if (selected) 0xffe3f2fd.toInt() else Color.TRANSPARENT)
        details.text = RouteCatalog.detailLine(route, readable)
        details.setTextColor(if (readable) Color.DKGRAY else 0xffb71c1c.toInt())
        delete.contentDescription = "${route.displayName} 삭제"
        delete.setOnClickListener { onDeleteClicked(route) }
        row.contentDescription = "${route.displayName}, ${details.text}"
        return row
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val ROW_TAG = "trailnav-route-row"
    }
}

internal fun isSelectedRoute(route: SavedRoute, selectedUri: String?): Boolean = route.uri == selectedUri
