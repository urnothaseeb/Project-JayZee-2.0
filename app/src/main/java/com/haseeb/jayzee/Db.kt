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
)

class Db private constructor(context: Context) :
    SQLiteOpenHelper(context, "jayzee.db", null, 1) {

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
                done_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future versions add their migrations here.
    }

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
}
