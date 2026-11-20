package com.wormgitty.agent

/** Wizard steps; strictly sequential and cannot be skipped. */
enum class Step { INTRO, NAME, CHECKLIST, PHONE, SUMMARY, LOCKING }

enum class PermKey(val required: Boolean) {
    OVERLAY(true),
    ACCESSIBILITY(true),
    ALL_FILES(false),
    BATTERY_OPT(true),
    DEVICE_ADMIN(true),
    NOTIFICATIONS(false);

    val titleRes: Int
        get() = when (this) {
            OVERLAY -> R.string.perm_overlay
            ACCESSIBILITY -> R.string.perm_accessibility
            ALL_FILES -> R.string.perm_all_files
            BATTERY_OPT -> R.string.perm_battery_opt
            DEVICE_ADMIN -> R.string.perm_device_admin
            NOTIFICATIONS -> R.string.perm_notifications
        }

    companion object {
        /** Gate order required by the spec: overlay + accessibility + battery + device admin. */
        val GATE_KEYS = listOf(OVERLAY, ACCESSIBILITY, BATTERY_OPT, DEVICE_ADMIN)

        fun gateOpen(states: Map<PermKey, Boolean>): Boolean =
            GATE_KEYS.all { states[it] == true }
    }
}

fun interface PermissionChecker {
    fun states(ctx: android.content.Context): Map<PermKey, Boolean>
}

interface PermissionOpener {
    fun open(ctx: android.content.Context, key: PermKey)
}
