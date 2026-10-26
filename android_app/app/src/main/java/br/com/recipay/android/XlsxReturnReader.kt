package br.com.recipay.android

import android.util.Base64
import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

data class ReturnApplication(
    val id: String,
    val receiptId: String,
    val amountCents: Long
)

data class ReturnTransfer(
    val id: String,
    val reference: String,
    val collaboratorId: String,
    val amountCents: Long,
    val date: String,
    val pixKey: String,
    val pixType: String,
    val pixHolder: String,
    val attachmentHash: String,
    val attachmentExtension: String,
    val applications: List<ReturnApplication>
)

data class PaymentReturnPreview(
    val fileName: String,
    val batchId: String,
    val managerId: String,
    val managerName: String,
    val recipientId: String,
    val transfers: List<ReturnTransfer>,
    val attachments: Map<String, ByteArray>,
    val totalCents: Long
)

object XlsxReturnReader {
    fun read(input: InputStream, fileName: String, expectedSupervisorId: String): PaymentReturnPreview {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        fun xml(path: String): org.w3c.dom.Document {
            val bytes = entries[path] ?: error("Arquivo XLSX incompleto: $path ausente.")
            return parseXml(bytes)
        }

        val workbook = xml("xl/workbook.xml")
        val relationships = xml("xl/_rels/workbook.xml.rels")
        val targets = mutableMapOf<String, String>()
        val rels = relationships.getElementsByTagNameNS("*", "Relationship")
        for (i in 0 until rels.length) {
            val rel = rels.item(i) as Element
            targets[rel.getAttribute("Id")] = rel.getAttribute("Target")
        }
        val paths = mutableMapOf<String, String>()
        val sheets = workbook.getElementsByTagNameNS("*", "sheet")
        for (i in 0 until sheets.length) {
            val sheet = sheets.item(i) as Element
            val id = sheet.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
            val target = targets[id] ?: continue
            paths[sheet.getAttribute("name")] = when {
                target.startsWith("/") -> target.removePrefix("/")
                target.startsWith("xl/") -> target
                else -> "xl/$target"
            }
        }
        val technicalPath = paths["_recipay"] ?: error("Aba técnica do Recipay ausente.")
        val paymentsPath = paths["Pagamentos"] ?: error("Este arquivo não é um retorno de pagamentos do Recipay.")
        val technicalRows = rows(xml(technicalPath))
        val header = technicalRows.firstOrNull() ?: error("Planilha vazia.")
        if (header[0] != "RECIPAY") error("Marca técnica Recipay não encontrada.")
        val formatVersion = header[1]?.toIntOrNull() ?: error("Versão do formato inválida.")
        if (formatVersion != 1) error("Versão do formato não suportada.")

        val fragments = mutableMapOf<String, MutableList<Pair<Int, String>>>()
        technicalRows.drop(1).forEach { row ->
            val key = row[0] ?: return@forEach
            val index = row[1]?.toIntOrNull() ?: error("Fragmento técnico inválido.")
            if (index < 0) error("Fragmento técnico inválido.")
            fragments.getOrPut(key) { mutableListOf() }.add(index to (row[2] ?: ""))
        }
        val joined = fragments.mapValues { (key, parts) ->
            val indices = parts.map { it.first }
            if (indices.distinct().size != indices.size) error("Fragmentos duplicados: $key.")
            if (indices.sorted() != indices.indices.toList()) error("Fragmentos ausentes: $key.")
            parts.sortedBy { it.first }.joinToString("") { it.second }
        }
        val jsonText = joined["dados"]?.takeIf { it.isNotEmpty() } ?: error("Dados técnicos ausentes.")
        val expectedHash = joined["hash"] ?: error("Hash técnico ausente.")
        if (sha256(jsonText.toByteArray(Charsets.UTF_8)) != expectedHash) error("Integridade dos dados não confere.")
        val data = try { JSONObject(jsonText) } catch (_: Exception) { error("JSON interno inválido.") }
        if (data.optString("tipo") != "RETORNO") error("Este arquivo não é um retorno de pagamentos.")
        if (data.optInt("versao_formato") != formatVersion) error("Versões do formato inconsistentes.")
        val recipient = data.optJSONObject("destinatario") ?: error("Destinatário ausente.")
        val recipientId = recipient.optString("id")
        if (recipientId != expectedSupervisorId) error("Este retorno não pertence a este Supervisor.")
        val batchId = data.optString("lote_id").ifBlank { error("Identificador do lote ausente.") }
        val manager = data.optJSONObject("gerado_por") ?: error("Gestor remetente ausente.")
        val managerId = manager.optString("id").ifBlank { error("ID do Gestor ausente.") }
        val managerName = manager.optString("nome").ifBlank { error("Nome do Gestor ausente.") }

        val attachments = mutableMapOf<String, ByteArray>()
        joined.forEach { (key, encoded) ->
            if (!key.startsWith("comp:")) return@forEach
            val bytes = try { Base64.decode(encoded, Base64.DEFAULT) } catch (_: Exception) { error("Comprovante codificado inválido.") }
            val hash = key.removePrefix("comp:")
            if (sha256(bytes) != hash) error("Comprovante danificado.")
            attachments[hash] = bytes
        }

        val visible = mutableMapOf<String, MutableSet<Long>>()
        rows(xml(paymentsPath)).drop(1).forEach { row ->
            val ref = row[0].orEmpty()
            val raw = row[5] ?: return@forEach
            if (ref.isBlank()) return@forEach
            val cents = try { BigDecimal(raw).multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).longValueExact() }
                catch (_: Exception) { error("Valor visível inválido para Pix $ref.") }
            visible.getOrPut(ref) { mutableSetOf() }.add(cents)
        }

        val transfersJson = data.optJSONArray("transferencias") ?: error("Lista de transferências ausente.")
        val transfers = mutableListOf<ReturnTransfer>()
        val ids = mutableSetOf<String>()
        val refs = mutableSetOf<String>()
        var total = 0L
        for (i in 0 until transfersJson.length()) {
            val t = transfersJson.optJSONObject(i) ?: error("Transferência inválida.")
            val id = t.optString("id").ifBlank { error("ID de transferência ausente.") }
            val ref = t.optString("ref").ifBlank { error("Referência de transferência ausente.") }
            if (!ids.add(id) || !refs.add(ref)) error("Transferência duplicada no retorno.")
            val amount = t.optLong("valor_centavos", -1L)
            if (amount <= 0) error("Valor de transferência inválido.")
            if (visible[ref] != setOf(amount)) error("Valor visível do Pix $ref foi alterado ou está ausente.")
            val hash = t.optJSONObject("comprovante")?.optString("hash").orEmpty()
            val ext = t.optJSONObject("comprovante")?.optString("extensao").orEmpty()
            if (hash !in attachments) error("Comprovante do Pix $ref ausente ou danificado.")
            if (!ext.matches(Regex("\\.[A-Za-z0-9]{1,8}"))) error("Extensão de comprovante inválida.")
            val appsJson = t.optJSONArray("aplicacoes") ?: error("Aplicações do Pix $ref ausentes.")
            val apps = mutableListOf<ReturnApplication>()
            val appIds = mutableSetOf<String>()
            var appTotal = 0L
            for (j in 0 until appsJson.length()) {
                val a = appsJson.optJSONObject(j) ?: error("Aplicação de pagamento inválida.")
                val appId = a.optString("id").ifBlank { error("ID de aplicação ausente.") }
                if (!appIds.add(appId)) error("Aplicação duplicada.")
                val receiptId = a.optString("recibo_id").ifBlank { error("ID do recibo ausente.") }
                val appCents = a.optLong("valor_centavos", -1L)
                if (appCents <= 0L) error("Valor aplicado inválido.")
                appTotal = Math.addExact(appTotal, appCents)
                apps += ReturnApplication(appId, receiptId, appCents)
            }
            if (apps.isEmpty() || appTotal != amount) error("As aplicações do Pix $ref não correspondem ao valor transferido.")
            total = Math.addExact(total, amount)
            transfers += ReturnTransfer(id, ref, t.optString("colaborador_id"), amount,
                t.optString("data_pagamento"), t.optString("chave_pix_usada"), t.optString("tipo_chave_usada"),
                t.optString("titular_usado"), hash, ext, apps)
        }
        if (visible.keys != refs) error("A aba visível contém referências extras ou inconsistentes.")
        if (data.optLong("total_centavos", -1L) != total) error("Total do retorno inconsistente.")
        return PaymentReturnPreview(fileName, batchId, managerId, managerName, recipientId, transfers, attachments, total)
    }

    private fun rows(doc: org.w3c.dom.Document): List<Map<Int, String>> {
        val rowNodes = doc.getElementsByTagNameNS("*", "row")
        val result = mutableListOf<Map<Int, String>>()
        for (ri in 0 until rowNodes.length) {
            val row = rowNodes.item(ri) as Element
            val cells = linkedMapOf<Int, String>()
            var child = row.firstChild
            while (child != null) {
                if (child.nodeType == Node.ELEMENT_NODE && (child as Element).localName == "c") {
                    val cell = child as Element
                    val ref = cell.getAttribute("r")
                    val col = columnIndex(ref)
                    val type = cell.getAttribute("t")
                    val value = if (type == "inlineStr") allText(cell) else childText(cell, "v")
                    cells[col] = value
                }
                child = child.nextSibling
            }
            result += cells
        }
        return result
    }

    private fun parseXml(bytes: ByteArray): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
        try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Exception) {}
        try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Exception) {}
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun allText(element: Element): String {
        val texts = element.getElementsByTagNameNS("*", "t")
        if (texts.length == 0) return element.textContent ?: ""
        return buildString { for (i in 0 until texts.length) append(texts.item(i).textContent ?: "") }
    }

    private fun childText(element: Element, localName: String): String {
        var node = element.firstChild
        while (node != null) {
            if (node.nodeType == Node.ELEMENT_NODE && (node as Element).localName == localName) return node.textContent ?: ""
            node = node.nextSibling
        }
        return ""
    }

    private fun columnIndex(reference: String): Int {
        val letters = reference.takeWhile { it.isLetter() }
        if (letters.isEmpty()) error("Referência de célula inválida.")
        var value = 0
        for (letter in letters) value = value * 26 + (letter.uppercaseChar() - 'A' + 1)
        return value - 1
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
