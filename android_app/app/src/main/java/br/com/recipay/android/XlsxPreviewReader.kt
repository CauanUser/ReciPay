package br.com.recipay.android

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class ReceiptPreviewItem(
    val reference: String,
    val collaborator: String,
    val amountCents: Long,
    val attachmentValid: Boolean
)

data class ReceiptPreview(
    val fileName: String,
    val batchId: String,
    val supervisor: String,
    val items: List<ReceiptPreviewItem>,
    val totalCents: Long
)

object XlsxPreviewReader {
    private const val FORMAT_VERSION = 1

    fun read(input: InputStream, fileName: String): ReceiptPreview {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    entries[entry.name] = zip.readBytes()
                }
                zip.closeEntry()
            }
        }
        fun xml(path: String): org.w3c.dom.Document {
            val bytes = entries[path] ?: error("Arquivo XLSX incompleto: $path ausente.")
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
            try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Exception) {}
            try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Exception) {}
            try { factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") } catch (_: Exception) {}
            try { factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") } catch (_: Exception) {}
            return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        }

        val workbook = xml("xl/workbook.xml")
        val relationships = xml("xl/_rels/workbook.xml.rels")
        val targets = mutableMapOf<String, String>()
        val relNodes = relationships.getElementsByTagNameNS("*", "Relationship")
        for (i in 0 until relNodes.length) {
            val rel = relNodes.item(i) as Element
            targets[rel.getAttribute("Id")] = rel.getAttribute("Target")
        }
        val sheetPaths = mutableMapOf<String, String>()
        val sheets = workbook.getElementsByTagNameNS("*", "sheet")
        for (i in 0 until sheets.length) {
            val sheet = sheets.item(i) as Element
            val relationId = sheet.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
            val target = targets[relationId] ?: continue
            val path = if (target.startsWith("/")) target.removePrefix("/")
                else if (target.startsWith("xl/")) target else "xl/$target"
            sheetPaths[sheet.getAttribute("name")] = path
        }
        val technicalPath = sheetPaths["_recipay"] ?: error("Arquivo não reconhecido: aba técnica do Recipay ausente.")
        val receiptPath = sheetPaths["Recibos"] ?: error("Arquivo não reconhecido: aba Recibos ausente.")

        val shared = mutableListOf<String>()
        entries["xl/sharedStrings.xml"]?.let { bytes ->
            val sharedDoc = parseXml(bytes)
            val sis = sharedDoc.getElementsByTagNameNS("*", "si")
            for (i in 0 until sis.length) shared += allText(sis.item(i) as Element)
        }

        fun rows(path: String): List<Map<Int, String>> {
            val doc = xml(path)
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
                        val value = when (type) {
                            "inlineStr" -> allText(cell)
                            "s" -> {
                                val raw = childText(cell, "v")
                                val index = raw.toIntOrNull()
                                if (index == null || index !in shared.indices) error("Índice de texto XLSX inválido.")
                                shared[index]
                            }
                            else -> childText(cell, "v")
                        }
                        cells[col] = value
                    }
                    child = child.nextSibling
                }
                result += cells
            }
            return result
        }

        val technicalRows = rows(technicalPath)
        val header = technicalRows.firstOrNull() ?: error("Planilha Recipay vazia.")
        if (header[0] != "RECIPAY") error("Marca técnica Recipay não encontrada.")
        val headerVersion = header[1]?.toIntOrNull() ?: error("Versão do formato inválida.")
        if (headerVersion < 1 || headerVersion > FORMAT_VERSION) error("Versão do formato não suportada.")

        val fragments = mutableMapOf<String, MutableList<Pair<Int, String>>>()
        for (row in technicalRows.drop(1)) {
            val key = row[0] ?: continue
            val index = row[1]?.toIntOrNull() ?: error("Fragmento técnico inválido.")
            if (index < 0) error("Fragmento técnico inválido.")
            fragments.getOrPut(key) { mutableListOf() }.add(index to (row[2] ?: ""))
        }
        val joined = fragments.mapValues { (key, parts) ->
            val indices = parts.map { it.first }
            if (indices.distinct().size != indices.size) error("Fragmentos técnicos duplicados: $key.")
            parts.sortedBy { it.first }.joinToString("") { it.second }
        }
        val jsonText = joined["dados"]?.takeIf { it.isNotEmpty() } ?: error("Dados técnicos ausentes.")
        val expectedHash = joined["hash"] ?: error("Hash técnico ausente.")
        if (sha256(jsonText.toByteArray(Charsets.UTF_8)) != expectedHash) error("Integridade dos dados não confere; solicite novo arquivo.")
        val data = try { JSONObject(jsonText) } catch (_: Exception) { error("JSON interno inválido.") }
        if (data.optString("tipo") != "RECIBOS") error("Este protótipo aceita apenas arquivos de recibos.")
        val dataVersion = data.optInt("versao_formato", 0)
        if (dataVersion != headerVersion || dataVersion !in 1..FORMAT_VERSION) error("Versões do formato inconsistentes ou não suportadas.")

        val attachments = mutableMapOf<String, ByteArray>()
        for ((key, encoded) in joined) {
            if (!key.startsWith("comp:")) continue
            val bytes = try { Base64.decode(encoded, Base64.DEFAULT) } catch (_: Exception) { error("Comprovante codificado de forma inválida.") }
            val hash = key.removePrefix("comp:")
            if (sha256(bytes) != hash) error("Comprovante danificado (integridade inválida).")
            attachments[hash] = bytes
        }

        val visibleRows = rows(receiptPath).drop(1)
        val visible = linkedMapOf<String, MutableList<Long>>()
        for (row in visibleRows) {
            val ref = row[0].orEmpty()
            if (ref.isBlank()) continue
            val amount = row[5]?.let {
                try { BigDecimal(it).multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).longValueExact() }
                catch (_: Exception) { error("Valor visível inválido para $ref.") }
            } ?: continue
            visible.getOrPut(ref) { mutableListOf() }.add(amount)
        }

        val collaborators = data.optJSONArray("colaboradores") ?: error("Lista de colaboradores inválida.")
        val names = mutableMapOf<String, String>()
        for (i in 0 until collaborators.length()) {
            val collaborator = collaborators.optJSONObject(i) ?: error("Colaborador inválido.")
            val id = collaborator.optString("id")
            if (id.isBlank() || names.put(id, collaborator.optString("nome")) != null) error("ID de colaborador ausente ou duplicado.")
        }
        val receipts = data.optJSONArray("recibos") ?: error("Lista de recibos inválida.")
        val items = mutableListOf<ReceiptPreviewItem>()
        val references = mutableSetOf<String>()
        var calculatedTotal = 0L
        for (i in 0 until receipts.length()) {
            val item = receipts.optJSONObject(i) ?: error("Recibo inválido.")
            val reference = item.optString("ref")
            val collaboratorId = item.optString("colaborador_id")
            val amount = item.optLong("valor_centavos", -1L)
            val attachment = item.optJSONObject("comprovante")?.optString("hash").orEmpty()
            if (reference.isBlank() || !references.add(reference)) error("Referência de recibo ausente ou duplicada.")
            if (amount <= 0L) error("Valor inválido no recibo $reference.")
            if (names[collaboratorId] == null) error("Colaborador do recibo $reference não encontrado.")
            if (visible[reference] != listOf(amount)) error("Valor visível do recibo $reference foi alterado, duplicado ou está ausente.")
            if (!attachments.containsKey(attachment)) error("Comprovante do recibo $reference ausente ou danificado.")
            calculatedTotal = Math.addExact(calculatedTotal, amount)
            items += ReceiptPreviewItem(reference, names.getValue(collaboratorId), amount, true)
        }
        if (visible.keys != references) error("A aba visível contém recibos extras ou referências inconsistentes.")
        val declaredTotal = data.optLong("total_centavos", -1L)
        if (declaredTotal != calculatedTotal) error("Total do lote inconsistente.")
        val generatedBy = data.optJSONObject("gerado_por") ?: JSONObject()
        val supervisor = generatedBy.optString("nome").ifBlank { "Supervisor não informado" }
        val batchId = data.optString("lote_id").ifBlank { error("Identificador do lote ausente.") }
        return ReceiptPreview(fileName, batchId, supervisor, items, calculatedTotal)
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
