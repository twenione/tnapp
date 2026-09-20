package com.trailnav.app

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView

internal class SessionListAdapter(
    private val context: Context,
    val items: MutableList<ExportableSession>,
) : BaseAdapter() {
    override fun getCount(): Int = items.size
    override fun getItem(position: Int): ExportableSession = items[position]
    override fun getItemId(position: Int): Long = items[position].startedAtMillis

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            minimumHeight = dp(76)
            addView(TextView(context).apply { textSize = 17f })
            addView(TextView(context).apply { textSize = 14f })
            addView(TextView(context).apply { textSize = 14f })
        }
        val session = getItem(position)
        (row.getChildAt(0) as TextView).text = SessionExportCatalog.displayStartTime(session)
        (row.getChildAt(1) as TextView).text = "소요 ${SessionExportCatalog.displayDuration(session)} · ${SessionExportCatalog.displaySize(session)}"
        val status = row.getChildAt(2) as TextView
        status.text = if (session.normalTermination) "정상 종료" else "종료 기록 없음"
        status.setTextColor(if (session.normalTermination) Color.DKGRAY else 0xffb71c1c.toInt())
        row.contentDescription = "${SessionExportCatalog.displayStartTime(session)}, ${status.text}"
        return row
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
