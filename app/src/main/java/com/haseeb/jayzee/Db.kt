package com.haseeb.jayzee

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

const val TYPE_REMINDER = "reminder"
const val TYPE_TASK = "task"
const val TYPE_NOTE = "note"

/** One captured thing. Times are epoch milliseconds; 0 means "not set". */
data class Item(
    val id: Long = 0,
    val type: String = TYPE_TASK,
    val title: String = "",
    val dueAt: Long = 0,
    val hasTime: Boolean = false,
    val startBy: Long = 0,
    val effortMin: Int = 60,
    val critical: Boolean = false,
    val done: Boolean = false,
    val rescheduleCount: Int = 0,
    val firstStep: String = "",
    val calendarEventId: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val doneAt: Long = 0,
    /** Start of the day this task was placed on in the weekly plan; 0 = not placed. */
    val plannedDay: Long = 0,
)

// Life of a logged notification:
//   new      -> no rule matched; waiting for your keep/mute decision
//   kept     -> worth answering; shown under "To respond"
//   muted    -> hidden by a rule; waits for the evening review
//   handled  -> you dealt with it
//   reviewed -> muted and already looked at (or muted by hand)
const val N_NEW = "new"
const val N_KEPT = "kept"
const val N_MUTED = "muted"
const val N_HANDLED = "handled"
const val N_REVIEWED = "reviewed"

const val RULE_KEEP = "keep"
const val RULE_MUTE = "mute"

data class Notif(
    val id: Long = 0,
    val pkg: String,
    val app: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val status: String,
    val key: String = "",
)

/** "Notifications from [pkg] (from [sender]) (containing [keyword]) are always kept/muted." */
data class Rule(
    val id: Long = 0,
    val pkg: String,
    val app: String,
    val sender: String = "",
    val keyword: String = "",
    val action: String,
    val auto: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

class Db private constructor(context: Context) :
    SQLiteOpenHelper(context, "jayzee.db", null, 2) {

    companion object {
        @Volatile
        private var instance: Db? = null

        fun get(context: Context): Db =
            instance ?: synchronized(this) {
                instance ?: Db(context.applicationContext).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                title TEXT NOT NULL,
                due_at INTEGER NOT NULL DEFAULT 0,
                has_time INTEGER NOT NULL DEFAULT 0,
                start_by INTEGER NOT NULL DEFAULT 0,
                effort_min INTEGER NOT NULL DEFAULT 60,
                critical INTEGER NOT NULL DEFAULT 0,
                done INTEGER NOT NULL DEFAULT 0,
                reschedule_count INTEGER NOT NULL DEFAULT 0,
                first_step TEXT NOT NULL DEFAULT '',
                calendar_event_id INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL DEFAULT 0,
                done_at INTEGER NOT NULL DEFAULT 0,
                planned_day INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        createNotificationTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE items ADD COLUMN planned_day INTEGER NOT NULL DEFAULT 0")
            createNotificationTables(db)
        }
    }

    private fun createNotificationTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS notifs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pkg TEXT NOT NULL,
                app TEXT NOT NULL,
                title TEXT NOT NULL,
                text TEXT NOT NULL,
                posted_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                skey TEXT NOT NULL DEFAULT '',
                decision TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS notifs_status ON notifs (status, posted_at)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS rules (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pkg TEXT NOT NULL,
                app TEXT NOT NULL,
                sender TEXT NOT NULL DEFAULT '',
                keyword TEXT NOT NULL DEFAULT '',
                action TEXT NOT NULL,
                auto INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    // ---- items

    private fun values(item: Item) = ContentValues().apply {
        put("type", item.type)
        put("title", item.title)
        put("due_at", item.dueAt)
        put("has_time", if (item.hasTime) 1 else 0)
        put("start_by", item.startBy)
        put("effort_min", item.effortMin)
        put("critical", if (item.critical) 1 else 0)
        put("done", if (item.done) 1 else 0)
        put("reschedule_count", item.rescheduleCount)
        put("first_step", item.firstStep)
        put("calendar_event_id", item.calendarEventId)
        put("created_at", item.createdAt)
        put("done_at", item.doneAt)
        put("planned_day", item.plannedDay)
    }

    private fun read(c: Cursor) = Item(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        type = c.getString(c.getColumnIndexOrThrow("type")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        dueAt = c.getLong(c.getColumnIndexOrThrow("due_at")),
        hasTime = c.getInt(c.getColumnIndexOrThrow("has_time")) != 0,
        startBy = c.getLong(c.getColumnIndexOrThrow("start_by")),
        effortMin = c.getInt(c.getColumnIndexOrThrow("effort_min")),
        critical = c.getInt(c.getColumnIndexOrThrow("critical")) != 0,
        done = c.getInt(c.getColumnIndexOrThrow("done")) != 0,
        rescheduleCount = c.getInt(c.getColumnIndexOrThrow("reschedule_count")),
        firstStep = c.getString(c.getColumnIndexOrThrow("first_step")),
        calendarEventId = c.getLong(c.getColumnIndexOrThrow("calendar_event_id")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
        doneAt = c.getLong(c.getColumnIndexOrThrow("done_at")),
        plannedDay = c.getLong(c.getColumnIndexOrThrow("planned_day")),
    )

    fun insert(item: Item): Long = writableDatabase.insert("items", null, values(item))

    fun update(item: Item) {
        writableDatabase.update("items", values(item), "id = ?", arrayOf(item.id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("items", "id = ?", arrayOf(id.toString()))
    }

    fun get(id: Long): Item? =
        readableDatabase.rawQuery("SELECT * FROM items WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) read(c) else null
        }

    fun all(): List<Item> =
        readableDatabase.rawQuery("SELECT * FROM items ORDER BY due_at ASC, id ASC", null).use { c ->
            val out = ArrayList<Item>()
            while (c.moveToNext()) out.add(read(c))
            out
        }

    // ---- notification log

    private fun readNotif(c: Cursor) = Notif(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        pkg = c.getString(c.getColumnIndexOrThrow("pkg")),
        app = c.getString(c.getColumnIndexOrThrow("app")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        text = c.getString(c.getColumnIndexOrThrow("text")),
        postedAt = c.getLong(c.getColumnIndexOrThrow("posted_at")),
        status = c.getString(c.getColumnIndexOrThrow("status")),
        key = c.getString(c.getColumnIndexOrThrow("skey")),
    )

    fun insertNotif(n: Notif): Long {
        val v = ContentValues().apply {
            put("pkg", n.pkg)
            put("app", n.app)
            put("title", n.title)
            put("text", n.text)
            put("posted_at", n.postedAt)
            put("status", n.status)
            put("skey", n.key)
        }
        return writableDatabase.insert("notifs", null, v)
    }

    fun notifExists(pkg: String, title: String, text: String, since: Long): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM notifs WHERE pkg = ? AND title = ? AND text = ? AND posted_at >= ? LIMIT 1",
            arrayOf(pkg, title, text, since.toString())
        ).use { it.moveToFirst() }

    fun setNotifStatus(id: Long, status: String) {
        val v = ContentValues().apply { put("status", status) }
        writableDatabase.update("notifs", v, "id = ?", arrayOf(id.toString()))
    }

    /** Records a keep/mute choice you made by hand; these are what rules are learned from. */
    fun setNotifDecision(id: Long, decision: String) {
        val v = ContentValues().apply { put("decision", decision) }
        writableDatabase.update("notifs", v, "id = ?", arrayOf(id.toString()))
    }

    /** Newest first. */
    fun notifsByStatus(status: String, limit: Int): List<Notif> =
        readableDatabase.rawQuery(
            "SELECT * FROM notifs WHERE status = ? ORDER BY posted_at DESC, id DESC LIMIT $limit",
            arrayOf(status)
        ).use { c ->
            val out = ArrayList<Notif>()
            while (c.moveToNext()) out.add(readNotif(c))
            out
        }

    fun countByStatus(status: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM notifs WHERE status = ?", arrayOf(status)).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /** Search the whole log, newest first. */
    fun searchNotifs(query: String, limit: Int): List<Notif> {
        val like = "%" + query.trim() + "%"
        return readableDatabase.rawQuery(
            "SELECT * FROM notifs WHERE title LIKE ? OR text LIKE ? OR app LIKE ? ORDER BY posted_at DESC, id DESC LIMIT $limit",
            arrayOf(like, like, like)
        ).use { c ->
            val out = ArrayList<Notif>()
            while (c.moveToNext()) out.add(readNotif(c))
            out
        }
    }

    /** Your latest hand-made choices for an app (optionally one sender), newest first: (sender, decision). */
    fun recentDecisions(pkg: String, sender: String?, limit: Int): List<Pair<String, String>> {
        val where = if (sender != null) "pkg = ? AND title = ? AND decision != ''" else "pkg = ? AND decision != ''"
        val args = if (sender != null) arrayOf(pkg, sender) else arrayOf(pkg)
        return readableDatabase.rawQuery(
            "SELECT title, decision FROM notifs WHERE $where ORDER BY posted_at DESC, id DESC LIMIT $limit", args
        ).use { c ->
            val out = ArrayList<Pair<String, String>>()
            while (c.moveToNext()) out.add(c.getString(0) to c.getString(1))
            out
        }
    }

    // ---- rules

    fun rules(): List<Rule> =
        readableDatabase.rawQuery("SELECT * FROM rules ORDER BY app ASC, id ASC", null).use { c ->
            val out = ArrayList<Rule>()
            while (c.moveToNext()) {
                out.add(
                    Rule(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        pkg = c.getString(c.getColumnIndexOrThrow("pkg")),
                        app = c.getString(c.getColumnIndexOrThrow("app")),
                        sender = c.getString(c.getColumnIndexOrThrow("sender")),
                        keyword = c.getString(c.getColumnIndexOrThrow("keyword")),
                        action = c.getString(c.getColumnIndexOrThrow("action")),
                        auto = c.getInt(c.getColumnIndexOrThrow("auto")) != 0,
                        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                    )
                )
            }
            out
        }

    fun insertRule(r: Rule): Long {
        val v = ContentValues().apply {
            put("pkg", r.pkg)
            put("app", r.app)
            put("sender", r.sender)
            put("keyword", r.keyword)
            put("action", r.action)
            put("auto", if (r.auto) 1 else 0)
            put("created_at", r.createdAt)
        }
        return writableDatabase.insert("rules", null, v)
    }

    fun deleteRule(id: Long) {
        writableDatabase.delete("rules", "id = ?", arrayOf(id.toString()))
    }
}
