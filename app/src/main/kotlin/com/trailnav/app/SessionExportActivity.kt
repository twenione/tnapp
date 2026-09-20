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
            text = "기록 내보내기"
            textSize = 22f
        }
        status = TextView(this).apply { text = "종료된 기록을 선택하세요" }
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
            text = "선택 기록 공유"
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
        sessions.clear()
        sessions.addAll(SessionExportCatalog.scan(this))
        adapter.notifyDataSetChanged()
        status.text = if (sessions.isEmpty()) "내보낼 종료 기록이 없습니다" else "종료된 기록을 선택하세요"
    }

    private fun updateShareButton() {
        shareButton.isEnabled = selectedIds.isNotEmpty()
        status.text = if (selectedIds.isEmpty()) "종료된 기록을 선택하세요" else "${selectedIds.size}개 기록 선택"
    }

    private fun confirmAndShare() {
        val chosen = sessions.filter { it.startedAtMillis in selectedIds }
        if (chosen.isEmpty()) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("기록 공유")
            .setMessage("위치 기록이 포함된 파일입니다. 신뢰하는 곳으로만 공유하세요.")
            .setNegativeButton("취소", null)
            .setPositiveButton("공유") { _, _ -> share(chosen) }
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
            startActivity(Intent.createChooser(send, "기록 공유"))
        } catch (_: Exception) {
            status.text = "기록을 내보낼 수 없습니다"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
