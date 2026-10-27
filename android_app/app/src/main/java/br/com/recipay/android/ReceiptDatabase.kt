package br.com.recipay.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

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


    fun updateCollaborator(
        id: Long, name: String, team: String, role: String,
        pixType: String, pixKey: String, pixHolder: String
    ) {
        val values = ContentValues().apply {
            put("name", name.trim())
            put("team", team.trim())
            put("role", role.trim().ifBlank { "Funcionário" })
            put("pix_type", pixType)
            put("pix_key", pixKey.trim())
            put("pix_holder", pixHolder.trim())
        }
        require(name.trim().isNotBlank() && pixKey.trim().isNotBlank() && pixHolder.trim().isNotBlank()) {
            "Preencha nome, chave Pix e titular."
        }
        val changed = writableDatabase.update("collaborators", values, "id = ?", arrayOf(id.toString()))
        require(changed == 1) { "Colaborador não encontrado." }
        writableDatabase.execSQL(
            "UPDATE receipts SET collaborator = ? WHERE collaborator_id = ?",
            arrayOf(name.trim(), id)
        )
    }

    /** Exclui apenas colaboradores sem despesas vinculadas para preservar o histórico. */
    fun deleteCollaborator(id: Long) {
        val person = listCollaborators().firstOrNull { it.id == id }
            ?: error("Colaborador não encontrado.")
        val linkedReceipts = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM receipts WHERE collaborator_id = ? OR collaborator = ? COLLATE NOCASE",
            arrayOf(id.toString(), person.name)
        ).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }
        require(linkedReceipts == 0L) {
            "Este colaborador possui despesas registradas. Para preservar o histórico financeiro, não é possível excluí-lo."
        }
        val deleted = writableDatabase.delete("collaborators", "id = ?", arrayOf(id.toString()))
        require(deleted == 1) { "Colaborador não encontrado." }
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
        collaboratorId: Long?,
        collaborator: String,
        amountCents: Long,
        description: String,
        expenseDate: String,
        attachmentPath: String
    ): Long {
        val values = ContentValues().apply {
            put("external_id", java.util.UUID.randomUUID().toString())
            put("collaborator", collaborator)
            if (collaboratorId == null) putNull("collaborator_id") else put("collaborator_id", collaboratorId)
            put("amount_cents", amountCents)
            put("description", description)
            put("expense_date", expenseDate)
            put("attachment_path", attachmentPath)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("receipts", null, values)
    }

    /** Importa registros sem foto, ignorando duplicatas exatas de pessoa, valor, descrição e data. */
    fun importSpreadsheetExpense(
        collaboratorId: Long?, collaborator: String, amountCents: Long,
        description: String, expenseDate: String
    ): Boolean {
        require(collaborator.isNotBlank() && amountCents > 0L && description.isNotBlank()) {
            "Dados da despesa importada inválidos."
        }
        val duplicate = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM receipts WHERE collaborator = ? COLLATE NOCASE AND amount_cents = ? AND description = ? COLLATE NOCASE AND expense_date = ?",
            arrayOf(collaborator.trim(), amountCents.toString(), description.trim(), expenseDate)
        ).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) > 0L }
        if (duplicate) return false
        addReceipt(collaboratorId, collaborator.trim(), amountCents, description.trim(), expenseDate, "")
        return true
    }

    fun setReceiptAttachment(id: Long, attachmentPath: String) {
        require(attachmentPath.isNotBlank()) { "Caminho do comprovante inválido." }
        val changed = writableDatabase.update(
            "receipts", ContentValues().apply { put("attachment_path", attachmentPath) },
            "id = ?", arrayOf(id.toString())
        )
        require(changed == 1) { "Despesa não encontrada." }
    }


    /** Atualiza despesas apenas enquanto não houver pagamentos vinculados. O ID externo é preservado. */
    fun updateReceipt(
        id: Long, collaboratorId: Long, collaborator: String, amountCents: Long,
        description: String, expenseDate: String
    ) {
        require(amountCents > 0L) { "O valor deve ser maior que zero." }
        require(collaborator.isNotBlank() && description.isNotBlank()) { "Preencha colaborador e descrição." }
        val receipt = listReceipts().firstOrNull { it.id == id } ?: error("Despesa não encontrada.")
        val applications = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM payment_applications WHERE receipt_external_id = ?",
            arrayOf(receipt.externalId)
        ).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }
        require(applications == 0L) {
            "Esta despesa já possui pagamentos vinculados. Para preservar o histórico, seus dados não podem ser alterados."
        }
        val values = ContentValues().apply {
            put("collaborator_id", collaboratorId)
            put("collaborator", collaborator.trim())
            put("amount_cents", amountCents)
            put("description", description.trim())
            put("expense_date", expenseDate)
        }
        val changed = writableDatabase.update("receipts", values, "id = ?", arrayOf(id.toString()))
        require(changed == 1) { "Não foi possível atualizar a despesa." }
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
