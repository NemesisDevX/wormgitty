package com.wormgitty.agent

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Read-only notification sink. Stores the LAST [NotificationsStore.CAP]
 * postings (excluding our own package to prevent a feedback loop) into
 * files/operator/notifications.json, mirroring a line into evidence.txt.
 */
@Serializable
data class NotifEntry(
    val pkg: String,
    val title: String,
    val text: String,
    val atMs: Long
)

object NotifFilter {

    /** Pure, test-covered: our own app must never enter the log. */
    fun shouldRecord(selfPkg: String, incomingPkg: String): Boolean =
        incomingPkg != selfPkg

    /** Pure, test-covered: cap at newest CAP entries. */
    fun cap(entries: List<NotifEntry>, cap: Int = NotificationsStore.CAP): List<NotifEntry> =
        entries.takeLast(cap)

    /** Simple-object conversion from an SB-like (pkg,title,text) triple. */
    fun entryOf(pkg: String, title: String?, text: String?, atMs: Long): NotifEntry =
        NotifEntry(pkg, title.orEmpty(), text.orEmpty(), atMs)
}

object NotificationsStore {

    const val CAP = 100

    fun file(context: Context): File =
        File(File(context.filesDir, "operator").apply { if (!exists()) mkdirs() },
            "notifications.json")

    fun evidence(context: Context): File =
        File(File(context.filesDir, "operator").apply { if (!exists()) mkdirs() },
            "evidence.txt")

    fun load(context: Context): MutableList<NotifEntry> {
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        val text = f.readText()
        if (text.isBlank()) return mutableListOf()
        return runCatching {
            Wire.json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(NotifEntry.serializer()),
                text
            )
        }.getOrDefault(emptyList()).toMutableList()
    }

    fun record(context: Context, entry: NotifEntry) {
        if (!NotifFilter.shouldRecord(context.packageName, entry.pkg)) return
        val list = load(context)
        list.add(entry)
        file(context).writeText(
            Wire.json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(NotifEntry.serializer()),
                NotifFilter.cap(list)
            )
        )
        evidence(context).appendText(
            "NOTIF ${entry.atMs} ${entry.pkg} title=${entry.title.take(60)} text=${entry.text.take(80)}\n"
        )
    }

    fun extract(sbn: StatusBarNotification): NotifEntry? {
        if (sbn.isOngoing && sbn.notification == null) return null
        val extras = sbn.notification?.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        return NotifFilter.entryOf(sbn.packageName, title, text, sbn.postTime)
    }
}

/** Silent listener: records others' notifications, skips self, caps at 100. */
class NotificationSink : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val sbn = sbn ?: return
        val entry = NotificationsStore.extract(sbn) ?: return
        if (!NotifFilter.shouldRecord(packageName, entry.pkg)) return
        NotificationsStore.record(this, entry)
    }
}
