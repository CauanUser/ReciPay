package br.com.recipay.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class LocalReceipt(
    val id: Long,
    val externalId: String,
    val collaboratorId: Long?,
    val collaborator: String,
    val amountCents: Long,
    val description: String,
    val expenseDate: String,
    val attachmentPath: String,
    val createdAt: Long
)

data class LocalCollaborator(
    val id: Long,
    val externalId: String,
    val name: String,
    val team: String,
    val pixType: String,
    val pixKey: String,
    val pixHolder: String,
    val role: String
)

class ReceiptDatabase(context: Context) : SQLiteOpenHelper(context, "recipay_supervisor.db", null, 6) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE receipts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                external_id TEXT NOT NULL UNIQUE,
                collaborator TEXT NOT NULL,
                collaborator_id INTEGER,
                amount_cents INTEGER NOT NULL CHECK(amount_cents > 0),
                description TEXT NOT NULL,
                expense_date TEXT NOT NULL,
                attachment_path TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        createCollaboratorsTable(db)
        createPaymentTables(db)
    }

    private fun createPaymentTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS imported_transfers (transfer_id TEXT PRIMARY KEY, batch_id TEXT NOT NULL, manager_id TEXT NOT NULL, manager_name TEXT NOT NULL, payment_date TEXT NOT NULL, amount_cents INTEGER NOT NULL CHECK(amount_cents > 0), pix_key TEXT NOT NULL, pix_type TEXT NOT NULL, pix_holder TEXT NOT NULL, attachment_path TEXT NOT NULL, attachment_hash TEXT NOT NULL, imported_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS payment_applications (application_id TEXT PRIMARY KEY, transfer_id TEXT NOT NULL, receipt_external_id TEXT NOT NULL, amount_cents INTEGER NOT NULL CHECK(amount_cents > 0), FOREIGN KEY(transfer_id) REFERENCES imported_transfers(transfer_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS import_history (id INTEGER PRIMARY KEY AUTOINCREMENT, batch_id TEXT NOT NULL, file_name TEXT NOT NULL, manager_name TEXT NOT NULL, imported_at INTEGER NOT NULL, new_transfers INTEGER NOT NULL, ignored_transfers INTEGER NOT NULL, total_cents INTEGER NOT NULL)")
    }

    private fun createCollaboratorsTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS collaborators (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                external_id TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL COLLATE NOCASE UNIQUE,
                team TEXT NOT NULL DEFAULT '',
                pix_type TEXT NOT NULL,
                pix_key TEXT NOT NULL,
                pix_holder TEXT NOT NULL,
                role TEXT NOT NULL DEFAULT 'Funcionário',
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
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
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE receipts ADD COLUMN collaborator_id INTEGER")
            db.execSQL(
                """UPDATE receipts SET collaborator_id = (
                    SELECT collaborators.id FROM collaborators
                    WHERE collaborators.name = receipts.collaborator COLLATE NOCASE
                    LIMIT 1
                ) WHERE collaborator_id IS NULL""".trimIndent()
            )
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE receipts ADD COLUMN external_id TEXT")
            db.execSQL("ALTER TABLE collaborators ADD COLUMN external_id TEXT")
            db.rawQuery("SELECT id FROM receipts WHERE external_id IS NULL", null).use { cursor ->
                val ids = mutableListOf<Long>()
                while (cursor.moveToNext()) ids += cursor.getLong(0)
                ids.forEach { id ->
                    db.execSQL("UPDATE receipts SET external_id = ? WHERE id = ?",
                        arrayOf(java.util.UUID.randomUUID().toString(), id))
                }
            }
            db.rawQuery("SELECT id FROM collaborators WHERE external_id IS NULL", null).use { cursor ->
                val ids = mutableListOf<Long>()
                while (cursor.moveToNext()) ids += cursor.getLong(0)
                ids.forEach { id ->
                    db.execSQL("UPDATE collaborators SET external_id = ? WHERE id = ?",
                        arrayOf(java.util.UUID.randomUUID().toString(), id))
                }
            }
        }
        if (oldVersion < 5) createPaymentTables(db)
        if (oldVersion < 6) db.execSQL("ALTER TABLE collaborators ADD COLUMN role TEXT NOT NULL DEFAULT 'Funcionário'")
    }

    fun addCollaborator(name: String, team: String, role: String, pixType: String, pixKey: String, pixHolder: String): Long {
        val values = ContentValues().apply {
            put("external_id", java.util.UUID.randomUUID().toString())
            put("name", name.trim())
            put("team", team.trim())
            put("pix_type", pixType)
            put("pix_key", pixKey.trim())
            put("pix_holder", pixHolder.trim())
            put("role", role.trim().ifBlank { "Funcionário" })
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("collaborators", null, values)
    }

    fun listCollaborators(): List<LocalCollaborator> {
        val result = mutableListOf<LocalCollaborator>()
        readableDatabase.query(
            "collaborators",
            arrayOf("id", "external_id", "name", "team", "pix_type", "pix_key", "pix_holder", "role"),
            null, null, null, null, "name COLLATE NOCASE ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += LocalCollaborator(
                    cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3),
                    cursor.getString(4), cursor.getString(5), cursor.getString(6), cursor.getString(7)
                )
            }
        }
        return result
    }

    fun addReceipt(
        collaboratorId: Long,
        collaborator: String,
        amountCents: Long,
        description: String,
        expenseDate: String,
        attachmentPath: String
    ): Long {
        val values = ContentValues().apply {
            put("external_id", java.util.UUID.randomUUID().toString())
            put("collaborator", collaborator)
            put("collaborator_id", collaboratorId)
            put("amount_cents", amountCents)
            put("description", description)
            put("expense_date", expenseDate)
            put("attachment_path", attachmentPath)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("receipts", null, values)
    }


    /** Remove somente despesas sem pagamentos vinculados. O histórico financeiro é preservado. */
    fun deleteReceipt(id: Long) {
        val receipt = listReceipts().firstOrNull { it.id == id } ?: error("Despesa não encontrada.")
        val paid = readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(amount_cents), 0) FROM payment_applications WHERE receipt_external_id = ?",
            arrayOf(receipt.externalId)
        ).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }
        require(paid == 0L) { "Esta despesa já tem pagamentos vinculados. Não é seguro excluí-la." }
        writableDatabase.delete("receipts", "id = ?", arrayOf(id.toString()))
        File(receipt.attachmentPath).delete()
    }

    fun listReceipts(): List<LocalReceipt> {
        val result = mutableListOf<LocalReceipt>()
        readableDatabase.query(
            "receipts",
            arrayOf("id", "external_id", "collaborator_id", "collaborator", "amount_cents", "description", "expense_date", "attachment_path", "created_at"),
            null, null, null, null, "id DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += LocalReceipt(
                    id = cursor.getLong(0),
                    externalId = cursor.getString(1),
                    collaboratorId = if (cursor.isNull(2)) null else cursor.getLong(2),
                    collaborator = cursor.getString(3),
                    amountCents = cursor.getLong(4),
                    description = cursor.getString(5),
                    expenseDate = cursor.getString(6),
                    attachmentPath = cursor.getString(7),
                    createdAt = cursor.getLong(8)
                )
            }
        }
        return result
    }
}
