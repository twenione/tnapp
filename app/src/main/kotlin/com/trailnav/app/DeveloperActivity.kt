package com.trailnav.app

import android.os.Bundle
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

internal class DeveloperActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        content.addView(TextView(this).apply {
            text = "버전 ${BuildConfig.VERSION_NAME}"
            gravity = Gravity.CENTER_VERTICAL
        })
        content.addView(CheckBox(this).apply {
            text = "원 가속도 수집(연구용)"
            isChecked = NavigationPreferences.imuCollectEnabled(this@DeveloperActivity)
            setOnCheckedChangeListener { _, checked ->
                NavigationPreferences.saveImuCollectEnabled(this@DeveloperActivity, checked)
            }
        })
        setContentView(content)
    }
}
