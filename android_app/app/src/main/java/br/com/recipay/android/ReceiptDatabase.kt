package br.com.recipay.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class LocalReceipt(
    val id: Long,
    val collaborator: String,
    val amountCents: Long,
    val description: String,
    val expenseDate: String,
    val attachmentPath: String,
    val createdAt: Long
)

data class LocalCollaborator(
    val id: Long,
    val name: String,
    val team: String,
    val pixType: String,
    val pixKey: String,
    val pixHolder: String
)

class ReceiptDatabase(context: Context) : SQLiteOpenHelper(context, "recipay_supervisor.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE receipts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                collaborator TEXT NOT NULL,
                amount_cents INTEGER NOT NULL CHECK(amount_cents > 0),
                description TEXT NOT NULL,
                expense_date TEXT NOT NULL,
                attachment_path TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        createCollaboratorsTable(db)
    }

    private fun createCollaboratorsTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS collaborators (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL COLLATE NOCASE UNIQUE,
                team TEXT NOT NULL DEFAULT '',
                pix_type TEXT NOT NULL,
                pix_key TEXT NOT NULL,
                pix_holder TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createCollaboratorsTable(db)
    }

    fun addCollaborator(name: String, team: String, pixType: String, pixKey: String, pixHolder: String): Long {
        val values = ContentValues().apply {
            put("name", name.trim())
            put("team", team.trim())
            put("pix_type", pixType)
            put("pix_key", pixKey.trim())
            put("pix_holder", pixHolder.trim())
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("collaborators", null, values)
    }

    fun listCollaborators(): List<LocalCollaborator> {
        val result = mutableListOf<LocalCollaborator>()
        readableDatabase.query(
            "collaborators",
            arrayOf("id", "name", "team", "pix_type", "pix_key", "pix_holder"),
            null, null, null, null, "name COLLATE NOCASE ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += LocalCollaborator(
                    cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                    cursor.getString(3), cursor.getString(4), cursor.getString(5)
                )
            }
        }
        return result
    }

    fun addReceipt(
        collaborator: String,
        amountCents: Long,
        description: String,
        expenseDate: String,
        attachmentPath: String
    ): Long {
        val values = ContentValues().apply {
            put("collaborator", collaborator)
            put("amount_cents", amountCents)
            put("description", description)
            put("expense_date", expenseDate)
            put("attachment_path", attachmentPath)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("receipts", null, values)
    }

    fun listReceipts(): List<LocalReceipt> {
        val result = mutableListOf<LocalReceipt>()
        readableDatabase.query(
            "receipts",
            arrayOf("id", "collaborator", "amount_cents", "description", "expense_date", "attachment_path", "created_at"),
            null, null, null, null, "id DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += LocalReceipt(
                    id = cursor.getLong(0),
                    collaborator = cursor.getString(1),
                    amountCents = cursor.getLong(2),
                    description = cursor.getString(3),
                    expenseDate = cursor.getString(4),
                    attachmentPath = cursor.getString(5),
                    createdAt = cursor.getLong(6)
                )
            }
        }
        return result
    }
}
