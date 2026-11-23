package com.wormgitty.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.widget.EditText
import android.widget.Toast

/**
 * Double-gated wipe consent flow (pure UI + admin check):
 *  Gate 1 — dialog stating the blast radius: full factory reset, FRP lock
 *           on reboot, and the app does not return by itself.
 *  Gate 2 — retyping the device label; wrong typing cancels.
 *  If device admin is inactive, the user is routed to activation instead.
 * Execution itself delegates to OperatorActions/AdminRcv.
 */
object WipePrompt {

    fun show(activity: Activity) {
        val deviceLabel = DeviceIdDeriver.derive(
            DeviceIdDeriver.androidId(activity), activity.packageName
        ).take(8)

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.wipe_btn))
            .setMessage(activity.getString(R.string.wipe_confirm_dialog))
            .setPositiveButton(
                activity.getString(android.R.string.ok)
            ) { gate1, _ ->
                gate1.dismiss()
                promptTyped(activity, deviceLabel)
            }
            .setNegativeButton(activity.getString(android.R.string.cancel)) { d, _ -> d.dismiss() }
            .show()
            .also { it.window?.decorView?.layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL }
    }

    private fun promptTyped(activity: Activity, deviceLabel: String) {
        val input = EditText(activity).apply {
            hint = deviceLabel
            setSingleLine(true)
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.wipe_btn))
            .setMessage(activity.getString(R.string.wipe_second_prompt, deviceLabel))
            .setView(input)
            .setPositiveButton(
                activity.getString(R.string.wipe_btn)
            ) { gate2, _ ->
                gate2.dismiss()
                if (!OperatorActions.isAdminEnabled(activity)) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.wipe_need_admin),
                        Toast.LENGTH_LONG
                    ).show()
                    OperatorActions.openAdminActivation(activity)
                    return@setPositiveButton
                }
                if (input.text?.toString()?.trim() == deviceLabel) {
                    OperatorActions.localFactoryResetConfirmed(activity)
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.wipe_done),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.wipe_second_prompt, deviceLabel),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton(activity.getString(android.R.string.cancel)) { d, _ -> d.dismiss() }
            .show()
            .also { it.window?.decorView?.layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL }
    }
}
