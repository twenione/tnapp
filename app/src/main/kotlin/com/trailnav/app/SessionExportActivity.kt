package com.trailnav.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider

/** User-driven, read-only sharing screen for completed session streams. */
class SessionExportActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var shareButton: Button
    private lateinit var adapter: SessionListAdapter
    private val sessions = mutableListOf<ExportableSession>()
    private val selectedIds = mutableSetOf<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionExportCatalog.cleanupExports(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val title = TextView(this).apply {
            text = getString(R.string.main_export_sessions)
            textSize = 22f
        }
        status = TextView(this).apply { text = getString(R.string.export_sessions_prompt) }
        adapter = SessionListAdapter(this, sessions)
        val list = ListView(this).apply {
            adapter = this@SessionExportActivity.adapter
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
            setOnItemClickListener { _, _, position, _ ->
                val id = adapter.getItemId(position)
                if (!selectedIds.add(id)) selectedIds.remove(id)
                updateShareButton()
            }
        }
        shareButton = Button(this).apply {
            text = getString(R.string.export_sessions_share)
            isEnabled = false
            setOnClickListener { confirmAndShare() }
        }
        root.addView(title)
        root.addView(status)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(shareButton)
        setContentView(root)
        loadSessions()
    }

    private fun loadSessions() {
        status.text = getString(R.string.export_sessions_loading)
        shareButton.isEnabled = false
        selectedIds.clear()
        Thread {
            val loaded = SessionExportCatalog.scan(this)
            runOnUiThread {
                sessions.clear()
                sessions.addAll(loaded)
                adapter.notifyDataSetChanged()
                status.text = if (sessions.isEmpty()) getString(R.string.export_sessions_none) else getString(R.string.export_sessions_prompt)
            }
        }.start()
    }

    private fun updateShareButton() {
        shareButton.isEnabled = selectedIds.isNotEmpty()
        status.text = if (selectedIds.isEmpty()) getString(R.string.export_sessions_prompt) else getString(R.string.export_sessions_selected_count, selectedIds.size)
    }

    private fun confirmAndShare() {
        val chosen = sessions.filter { it.selectionId in selectedIds }
        if (chosen.isEmpty()) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.export_sessions_share_title))
            .setMessage(getString(R.string.export_sessions_privacy_warning))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.common_share)) { _, _ -> share(chosen) }
            .show()
    }

    private fun share(chosen: List<ExportableSession>) {
        try {
            val zip = SessionArchiveBuilder.zipFile(this, chosen)
            val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", zip)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newRawUri("", uri)
            }
            startActivity(Intent.createChooser(send, getString(R.string.export_sessions_share_title)))
        } catch (_: Exception) {
            status.text = getString(R.string.export_sessions_unavailable)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
