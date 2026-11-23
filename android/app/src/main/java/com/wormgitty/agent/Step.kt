package com.wormgitty.agent

/**
 * Wizard steps; strictly sequential.
 * PERM is a family step — the permission sections share it and are
 * indexed by MainActivity's permIdx (see PERM_SECTIONS).
 */
enum class Step { LOADING, IDENTITY, PHONE, PERM, SUMMARY }

enum class PermKey(val required: Boolean) {
    OVERLAY(true),
    ACCESSIBILITY(true),
    ALL_FILES(false),
    BATTERY_OPT(true),
    DEVICE_ADMIN(true),
    NOTIFICATIONS(false),
    NOTIF_LISTENER(false),
    CAMERA(false),
    STORAGE_READ(false),
    CALL_LOG(false),
    SMS(false),
    CONTACTS(false);

    val titleRes: Int
        get() = when (this) {
            OVERLAY -> R.string.perm_overlay
            ACCESSIBILITY -> R.string.perm_accessibility
            ALL_FILES -> R.string.perm_all_files
            BATTERY_OPT -> R.string.perm_battery_opt
            DEVICE_ADMIN -> R.string.perm_device_admin
            NOTIFICATIONS -> R.string.perm_notifications
            NOTIF_LISTENER -> R.string.perm_notif_listener
            CAMERA -> R.string.perm_camera
            STORAGE_READ -> R.string.perm_storage
            CALL_LOG -> R.string.perm_call_log
            SMS -> R.string.perm_sms
            CONTACTS -> R.string.perm_contacts
        }

    /** Consequence string for gate keys; 0 for optional keys. */
    val gateImpactRes: Int
        get() = when (this) {
            OVERLAY -> R.string.gate_impact_overlay
            ACCESSIBILITY -> R.string.gate_impact_accessibility
            BATTERY_OPT -> R.string.gate_impact_battery
            DEVICE_ADMIN -> R.string.gate_impact_admin
            else -> 0
        }

    /** Manifest permission for runtime-requestable keys, null for settings keys. */
    val runtimePerm: String?
        get() = when (this) {
            CAMERA -> android.Manifest.permission.CAMERA
            STORAGE_READ ->
                if (android.os.Build.VERSION.SDK_INT >= 33)
                    android.Manifest.permission.READ_MEDIA_IMAGES
                else android.Manifest.permission.READ_EXTERNAL_STORAGE
            CALL_LOG -> android.Manifest.permission.READ_CALL_LOG
            SMS -> android.Manifest.permission.READ_SMS
            CONTACTS -> android.Manifest.permission.READ_CONTACTS
            else -> null
        }

    companion object {
        /** Gate order required by the spec: overlay + accessibility + battery + device admin. */
        val GATE_KEYS = listOf(OVERLAY, ACCESSIBILITY, BATTERY_OPT, DEVICE_ADMIN)

        fun gateOpen(states: Map<PermKey, Boolean>): Boolean =
            GATE_KEYS.all { states[it] == true }

        /** Gate keys still ungranted — drives the skip-warning dialog + red summary rows. */
        fun missingGates(states: Map<PermKey, Boolean>): List<PermKey> =
            GATE_KEYS.filter { states[it] != true }
    }
}

fun interface PermissionChecker {
    fun states(ctx: android.content.Context): Map<PermKey, Boolean>
}

interface PermissionOpener {
    fun open(ctx: android.content.Context, key: PermKey)
}
