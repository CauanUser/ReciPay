package br.com.recipay.android

import android.content.ContentValues
import java.io.File

data class PaymentReturnImportResult(
    val importedTransfers: Int,
    val ignoredTransfers: Int,
    val totalCents: Long
)

fun ReceiptDatabase.importPaymentReturn(
    preview: PaymentReturnPreview,
    proofDirectory: File
): PaymentReturnImportResult {
    val db = writableDatabase
    val receipts = listReceipts().associateBy { it.externalId }
    val collaborators = listCollaborators()
    val collaboratorsByExternalId = collaborators.associateBy { it.externalId }
    val newTransfers = preview.transfers.filter { transfer ->
        db.rawQuery("SELECT 1 FROM imported_transfers WHERE transfer_id = ?", arrayOf(transfer.id)).use { !it.moveToFirst() }
    }
    val ignoredTransfers = preview.transfers.size - newTransfers.size

    val allApplications = newTransfers.flatMap { it.applications }
    if (allApplications.map { it.id }.distinct().size != allApplications.size) {
        error("O retorno contém aplicações duplicadas.")
    }
    for (transfer in newTransfers) {
        val collaborator = collaboratorsByExternalId[transfer.collaboratorId]
            ?: error("O colaborador do Pix ${transfer.reference} não está cadastrado neste celular.")
        if (transfer.date.isBlank() || transfer.pixKey.isBlank() || transfer.pixType.isBlank() || transfer.pixHolder.isBlank()) {
            error("O retorno contém dados incompletos do Pix ${transfer.reference}.")
        }
        for (application in transfer.applications) {
            val receipt = receipts[application.receiptId]
                ?: error("O retorno cita uma despesa que não existe neste celular. Nenhum pagamento foi importado.")
            if (receipt.collaboratorId != collaborator.id) {
                error("O Pix ${transfer.reference} está associado ao colaborador errado.")
            }
        }
    }

    val additionalByReceipt = allApplications.groupBy { it.receiptId }.mapValues { (_, apps) ->
        apps.fold(0L) { total, app -> Math.addExact(total, app.amountCents) }
    }
    additionalByReceipt.forEach { (receiptId, additional) ->
        val receipt = receipts.getValue(receiptId)
        val alreadyPaid = db.rawQuery(
            "SELECT COALESCE(SUM(amount_cents), 0) FROM payment_applications WHERE receipt_external_id = ?",
            arrayOf(receiptId)
        ).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }
        if (Math.addExact(alreadyPaid, additional) > receipt.amountCents) {
            error("O retorno excede o valor da despesa de ${receipt.collaborator}. Nenhum pagamento foi importado.")
        }
    }
    allApplications.forEach { app ->
        db.rawQuery("SELECT 1 FROM payment_applications WHERE application_id = ?", arrayOf(app.id)).use {
            if (it.moveToFirst()) error("Uma aplicação já existe com outro ID de transferência.")
        }
    }

    proofDirectory.mkdirs()
    val savedFiles = mutableListOf<File>()
    try {
        db.beginTransaction()
        for (transfer in newTransfers) {
            val proofBytes = preview.attachments[transfer.attachmentHash]
                ?: error("Comprovante do Pix ${transfer.reference} ausente.")
            val safeId = transfer.id.replace("-", "")
            val proof = File(proofDirectory, safeId + transfer.attachmentExtension)
            proof.writeBytes(proofBytes)
            savedFiles += proof

            val transferValues = ContentValues().apply {
                put("transfer_id", transfer.id)
                put("batch_id", preview.batchId)
                put("manager_id", preview.managerId)
                put("manager_name", preview.managerName)
                put("payment_date", transfer.date)
                put("amount_cents", transfer.amountCents)
                put("pix_key", transfer.pixKey)
                put("pix_type", transfer.pixType)
                put("pix_holder", transfer.pixHolder)
                put("attachment_path", proof.absolutePath)
                put("attachment_hash", transfer.attachmentHash)
                put("imported_at", System.currentTimeMillis())
            }
            db.insertOrThrow("imported_transfers", null, transferValues)

            transfer.applications.forEach { application ->
                val values = ContentValues().apply {
                    put("application_id", application.id)
                    put("transfer_id", transfer.id)
                    put("receipt_external_id", application.receiptId)
                    put("amount_cents", application.amountCents)
                }
                db.insertOrThrow("payment_applications", null, values)
            }
        }
        val importedTotal = newTransfers.fold(0L) { total, transfer -> Math.addExact(total, transfer.amountCents) }
        val history = ContentValues().apply {
            put("batch_id", preview.batchId)
            put("file_name", preview.fileName)
            put("manager_name", preview.managerName)
            put("imported_at", System.currentTimeMillis())
            put("new_transfers", newTransfers.size)
            put("ignored_transfers", ignoredTransfers)
            put("total_cents", importedTotal)
        }
        db.insertOrThrow("import_history", null, history)
        db.setTransactionSuccessful()
        return PaymentReturnImportResult(newTransfers.size, ignoredTransfers, importedTotal)
    } catch (error: Exception) {
        savedFiles.forEach { it.delete() }
        throw error
    } finally {
        db.endTransaction()
    }
}

fun ReceiptDatabase.paymentStatus(receiptExternalId: String): LocalPaymentStatus {
    val receipt = listReceipts().firstOrNull { it.externalId == receiptExternalId }
        ?: error("Despesa não encontrada.")
    val paid = readableDatabase.rawQuery(
        "SELECT COALESCE(SUM(amount_cents), 0) FROM payment_applications WHERE receipt_external_id = ?",
        arrayOf(receiptExternalId)
    ).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }
    val balance = (receipt.amountCents - paid).coerceAtLeast(0L)
    val status = when {
        paid == 0L -> "Pendente"
        balance == 0L -> "Pago"
        else -> "Parcial"
    }
    return LocalPaymentStatus(status, paid, balance)
}

data class LocalPaymentStatus(val status: String, val paidCents: Long, val balanceCents: Long)
