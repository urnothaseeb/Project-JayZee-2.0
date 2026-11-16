package com.haseeb.jayzee

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/** Small key-value settings (which week is planned, which day was reviewed). */
object Prefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("jayzee", Context.MODE_PRIVATE)
    fun get(ctx: Context, key: String): String = prefs(ctx).getString(key, "") ?: ""
    fun set(ctx: Context, key: String, value: String) {
        prefs(ctx).edit().putString(key, value).apply()
    }
}

/**
 * Decides what happens to each notification, and learns rules from your choices.
 *
 * Learning, in plain terms:
 *   - 3 identical choices in a row for the same sender in an app -> a rule for that sender.
 *   - 5 identical choices in a row for an app, across at least 2 senders -> a rule for the whole app.
 * Until then the app keeps asking.
 */
object Triage {
    private const val SENDER_STREAK = 3
    private const val APP_STREAK = 5

    fun enabled(ctx: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    /** The most specific rule that fits, or null. Sender rules beat keyword rules beat app rules. */
    fun match(rules: List<Rule>, pkg: String, title: String, text: String): Rule? =
        rules.filter { r ->
            r.pkg == pkg &&
                (r.sender.isEmpty() || title.equals(r.sender, ignoreCase = true)) &&
                (r.keyword.isEmpty() || "$title $text".contains(r.keyword, ignoreCase = true))
        }.maxByOrNull { (if (it.sender.isNotEmpty()) 2 else 0) + (if (it.keyword.isNotEmpty()) 1 else 0) }

    private fun removeFromShade(n: Notif) {
        if (n.key.isEmpty()) return
        try {
            NotifListener.instance?.cancelNotification(n.key)
        } catch (e: Exception) {
            // The notification may already be gone.
        }
    }

    /**
     * A one-off choice on a notification. Returns a sentence if a rule was learned from it.
     */
    fun decide(ctx: Context, n: Notif, keep: Boolean): String? {
        val db = Db.get(ctx)
        val decision = if (keep) RULE_KEEP else RULE_MUTE
        db.setNotifDecision(n.id, decision)
        db.setNotifStatus(n.id, if (keep) N_KEPT else N_REVIEWED)
        if (!keep) removeFromShade(n)

        val rules = db.rules()
        val verb = if (keep) "keep" else "mute"
        if (n.title.isNotEmpty() && rules.none { it.pkg == n.pkg && it.sender.equals(n.title, true) && it.keyword.isEmpty() }) {
            val last = db.recentDecisions(n.pkg, n.title, SENDER_STREAK)
            if (last.size == SENDER_STREAK && last.all { it.second == decision }) {
                addRule(ctx, Rule(pkg = n.pkg, app = n.app, sender = n.title, action = decision, auto = true))
                return "Learned: always $verb \"${n.title}\" in ${n.app}."
            }
        }
        if (rules.none { it.pkg == n.pkg && it.sender.isEmpty() && it.keyword.isEmpty() }) {
            val last = db.recentDecisions(n.pkg, null, APP_STREAK)
            if (last.size == APP_STREAK && last.all { it.second == decision } && last.map { it.first }.distinct().size >= 2) {
                addRule(ctx, Rule(pkg = n.pkg, app = n.app, action = decision, auto = true))
                return "Learned: always $verb ${n.app}."
            }
        }
        return null
    }

    /** Saves a rule and applies it to everything still waiting for a decision. */
    fun addRule(ctx: Context, rule: Rule) {
        val db = Db.get(ctx)
        db.insertRule(rule)
        val rules = db.rules()
        db.notifsByStatus(N_NEW, 500).forEach { n ->
            val hit = match(rules, n.pkg, n.title, n.text) ?: return@forEach
            if (hit.action == RULE_MUTE) {
                db.setNotifStatus(n.id, N_MUTED)
                removeFromShade(n)
            } else {
                db.setNotifStatus(n.id, N_KEPT)
            }
        }
    }

    fun describe(r: Rule): String {
        val what = StringBuilder(if (r.action == RULE_MUTE) "Mute " else "Keep ")
        what.append(r.app)
        if (r.sender.isNotEmpty()) what.append(" · from ").append(r.sender)
        if (r.keyword.isNotEmpty()) what.append(" · containing \"").append(r.keyword).append("\"")
        if (r.auto) what.append(" (learned)")
        return what.toString()
    }
}

/**
 * Reads every notification the phone shows, logs it, and removes muted ones from the shade.
 * Android only starts this after you allow "notification access" for JayZee in system settings.
 */
class NotifListener : NotificationListenerService() {
    companion object {
        @Volatile
        var instance: NotifListener? = null

        // Things that are not messages: calls, alarms, music, downloads, navigation.
        private val skippedCategories = setOf(
            Notification.CATEGORY_CALL,
            Notification.CATEGORY_ALARM,
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_NAVIGATION,
            Notification.CATEGORY_STOPWATCH,
        )
        private const val SAME_WITHIN_MS = 10 * 60 * 1000L
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            if (sbn != null) handle(sbn)
        } catch (e: Exception) {
            // Never let one odd notification stop the reader.
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category in skippedCategories) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim() ?: ""
        if (title.isEmpty() && text.isEmpty()) return

        val db = Db.get(this)
        if (db.notifExists(sbn.packageName, title, text, sbn.postTime - SAME_WITHIN_MS)) return

        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }

        val rule = Triage.match(db.rules(), sbn.packageName, title, text)
        val status = when (rule?.action) {
            RULE_MUTE -> N_MUTED
            RULE_KEEP -> N_KEPT
            else -> N_NEW
        }
        db.insertNotif(
            Notif(
                pkg = sbn.packageName, app = app, title = title, text = text,
                postedAt = sbn.postTime, status = status, key = sbn.key
            )
        )
        if (status == N_MUTED) cancelNotification(sbn.key)
    }
}
