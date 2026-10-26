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

class ReceiptDatabase(context: Context) : SQLiteOpenHelper(context, "recipay_supervisor.db", null, 1) {
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future schema migrations must be explicit; never discard supervisor data.
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
