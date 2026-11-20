package com.wormgitty.agent

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Read-only diagnostics: alias state, service liveness, last heartbeat, registered phone. */
class StatusActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        collect().forEach { pair ->
            col.addView(
                TextView(this).apply {
                    text = pair.first
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(16), dp(14), dp(16), dp(2))
                }
            )
            col.addView(
                TextView(this).apply {
                    text = pair.second
                    textSize = 17f
                    setPadding(dp(16), dp(0), dp(16), dp(10))
                }
            )
        }
        setContentView(col)
    }

    private fun collect(): List<Pair<String, String>> {
        val pm = packageManager
        val pkg = packageName
        val boost = pm.getComponentEnabledSetting(ComponentName(pkg, "$pkg.LauncherBoost"))
        val hidden = pm.getComponentEnabledSetting(ComponentName(pkg, "$pkg.LauncherHidden"))
        val alias = when {
            hidden == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ->
                getString(R.string.alias_hidden_label)

            boost == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ->
                getString(R.string.app_name)

            else -> "?"
        }
        val beatAge = System.currentTimeMillis() - ServiceAlive.lastBeatMs
        return listOf(
            getString(R.string.status_alias_state) to alias,
            getString(R.string.status_service_alive) to ServiceAlive.started.toString(),
            getString(R.string.status_last_heartbeat) to
                (if (ServiceAlive.lastBeatMs == 0L) "-" else "${beatAge / 1000}s"),
            getString(R.string.status_phone) to (RegistrationStore(this).load()?.phone ?: "-")
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
