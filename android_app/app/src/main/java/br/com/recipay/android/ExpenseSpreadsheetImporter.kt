package br.com.recipay.android

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

data class ImportedExpenseRow(
    val rowNumber: Int,
    val sheetName: String,
    val recipient: String,
    val amountCents: Long,
    val date: String,
    val description: String,
    val sourceStatus: String,
    val pixKey: String,
    val pixType: String,
    val observations: String,
    val errors: List<String>
)

data class ExpenseSpreadsheetPreview(val fileName: String, val rows: List<ImportedExpenseRow>) {
    val validRows: List<ImportedExpenseRow> get() = rows.filter { it.errors.isEmpty() }
    val invalidRows: List<ImportedExpenseRow> get() = rows.filter { it.errors.isNotEmpty() }
}

object ExpenseSpreadsheetImporter {
    fun read(input: InputStream, fileName: String): ExpenseSpreadsheetPreview {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        fun xml(path: String): org.w3c.dom.Document {
            val bytes = entries[path] ?: error("Arquivo XLSX inválido: $path ausente.")
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
            try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Exception) {}
            try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Exception) {}
            return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        }
        val workbook = xml("xl/workbook.xml")
        val rels = xml("xl/_rels/workbook.xml.rels")
        val targets = mutableMapOf<String, String>()
        val relNodes = rels.getElementsByTagNameNS("*", "Relationship")
        for (i in 0 until relNodes.length) {
            val rel = relNodes.item(i) as Element
            targets[rel.getAttribute("Id")] = rel.getAttribute("Target")
        }
        val shared = mutableListOf<String>()
        entries["xl/sharedStrings.xml"]?.let { bytes ->
            val nodes = parseXml(bytes).getElementsByTagNameNS("*", "si")
            for (i in 0 until nodes.length) shared += allText(nodes.item(i) as Element)
        }

        fun rows(path: String): List<Map<Int, String>> {
            val rowNodes = xml(path).getElementsByTagNameNS("*", "row")
            val parsed = mutableListOf<Map<Int, String>>()
            for (ri in 0 until rowNodes.length) {
                val row = rowNodes.item(ri) as Element
                val cells = linkedMapOf<Int, String>()
                var child = row.firstChild
                while (child != null) {
                    if (child.nodeType == Node.ELEMENT_NODE && (child as Element).localName == "c") {
                        val cell = child as Element
                        val col = columnIndex(cell.getAttribute("r"))
                        val value = when (cell.getAttribute("t")) {
                            "inlineStr" -> allText(cell)
                            "s" -> shared.getOrNull(childText(cell, "v").toIntOrNull() ?: -1)
                                ?: error("Texto compartilhado inválido no XLSX.")
                            "b" -> if (childText(cell, "v") == "1") "true" else "false"
                            else -> childText(cell, "v")
                        }
                        cells[col] = value.trim()
                    }
                    child = child.nextSibling
                }
                parsed += cells
            }
            return parsed
        }

        val sheets = workbook.getElementsByTagNameNS("*", "sheet")
        if (sheets.length == 0) error("A planilha não possui abas.")
        val allRows = mutableListOf<ImportedExpenseRow>()
        for (sheetIndex in 0 until sheets.length) {
            val sheet = sheets.item(sheetIndex) as Element
            val sheetName = sheet.getAttribute("name").ifBlank { "Aba ${sheetIndex + 1}" }
            val rid = sheet.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
            val target = targets[rid] ?: continue
            val path = if (target.startsWith("/")) target.removePrefix("/")
                else if (target.startsWith("xl/")) target else "xl/$target"
            val parsed = rows(path)
            if (parsed.isEmpty()) continue

            fun headerMap(row: Map<Int, String>) = row.mapValues { normalize(it.value) }
            fun locateHeader(): Int {
                for (i in 0 until minOf(parsed.size, 20)) {
                    val headers = headerMap(parsed[i])
                    val hasRecipient = headers.values.any { it == "destinatario" || it == "beneficiario" || it == "colaborador" || it == "nome" || it == "favorecido" || it == "recebedor" }
                    val hasAmount = headers.values.any { it == "valor" || it.startsWith("valor ") || it == "amount" }
                    if (hasRecipient && hasAmount) return i
                }
                return -1
            }
            val headerIndex = locateHeader()
            if (headerIndex < 0) continue
            val headers = headerMap(parsed[headerIndex])
            fun findHeader(vararg candidates: String): Int? {
                val normalizedCandidates = candidates.map { normalize(it) }
                return headers.entries.firstOrNull { (_, header) ->
                    normalizedCandidates.any { candidate -> header == candidate }
                }?.key
            }
            val recipientCol = findHeader("destinatario", "beneficiario", "colaborador", "nome", "favorecido", "recebedor")
                ?: continue
            // Prefere a coluna de valor individual. "Valor total" só é fallback quando
            // a planilha não tem uma coluna própria chamada Valor.
            val amountCol = findHeader("valor", "valor r", "valor da despesa", "amount")
                ?: findHeader("valor total")
                ?: continue
            val dateCol = findHeader("data", "data da despesa", "data pagamento", "data do pagamento")
            val descriptionCol = findHeader("descricao", "despesa", "categoria", "motivo")
            val statusCol = findHeader("status", "situacao", "situação do pagamento", "estado")
            val pixCol = findHeader("chave pix", "chave_pix", "pix")
            val pixTypeCol = findHeader("tipo chave", "tipo de chave", "tipo da chave", "tipo pix", "tipo_chave")
            val observationsCol = findHeader("observacoes", "observação", "observações", "observacao", "notas")
            for (index in headerIndex + 1 until parsed.size) {
                val row = parsed[index]
                if (row.values.all { it.isBlank() }) continue
                val recipient = row[recipientCol].orEmpty()
                // Ignora linhas de total/subtotal e resumos: elas não representam uma despesa individual.
                val recipientNormalized = normalize(recipient)
                if (recipientNormalized in setOf("total", "total geral", "subtotal", "soma", "saldo", "saldo total", "valor total", "total de despesas", "total a pagar", "total pago") ||
                    recipientNormalized.startsWith("subtotal ") || recipientNormalized.startsWith("total geral ")) continue
                val amount = parseMoney(row[amountCol].orEmpty())
                val rawDate = dateCol?.let { row[it].orEmpty() }.orEmpty()
                val date = normalizeDate(rawDate)
                val observations = observationsCol?.let { row[it].orEmpty() }.orEmpty()
                val sourceStatus = statusCol?.let { row[it].orEmpty() }.orEmpty()
                val description = descriptionCol?.let { row[it].orEmpty() }.orEmpty()
                    .ifBlank { observations.ifBlank { "Despesa importada da planilha" } }
                val errors = mutableListOf<String>()
                if (recipient.isBlank()) errors += "Destinatário vazio"
                if (amount == null || amount <= 0L) errors += "Valor inválido"
                // A data da planilha costuma ser a data do pagamento; pendências podem não ter data ainda.
                allRows += ImportedExpenseRow(
                    rowNumber = index + 1, sheetName = sheetName, recipient = recipient,
                    amountCents = amount ?: 0L, date = date.orEmpty(),
                    description = description, sourceStatus = sourceStatus,
                    pixKey = pixCol?.let { row[it].orEmpty() }.orEmpty(),
                    pixType = pixTypeCol?.let { row[it].orEmpty() }.orEmpty(),
                    observations = observations, errors = errors
                )
            }
        }
        if (allRows.isEmpty()) error("Não encontrei linhas de despesas. Verifique se há cabeçalhos como Destinatário e Valor.")
        return ExpenseSpreadsheetPreview(fileName, allRows)
    }

    private fun parseMoney(raw: String): Long? {
        val value = raw.trim().replace("R$", "").replace(" ", "")
        if (value.isBlank()) return null
        val normalized = when {
            value.contains(",") -> value.replace(".", "").replace(",", ".")
            value.count { it == '.' } > 1 -> value.replace(".", "")
            else -> value
        }
        return try { BigDecimal(normalized).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }
        catch (_: Exception) { null }
    }

    private fun normalizeDate(raw: String): String? {
        if (raw.isBlank()) return null
        val s = raw.trim()
        try { return java.time.LocalDate.parse(s).toString() } catch (_: Exception) {}
        val formats = listOf("d/M/yyyy", "dd/MM/yyyy", "d-M-yyyy", "dd-MM-yyyy", "M/d/yyyy")
        for (pattern in formats) {
            try { return java.time.LocalDate.parse(s, java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.ROOT)).toString() }
            catch (_: Exception) {}
        }
        val serial = s.toDoubleOrNull()
        if (serial != null && serial >= 1.0 && serial < 100000.0) {
            return try { java.time.LocalDate.of(1899, 12, 30).plusDays(serial.toLong()).toString() } catch (_: Exception) { null }
        }
        return null
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.trim().lowercase(java.util.Locale.ROOT), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "").replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun parseXml(bytes: ByteArray): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
        try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Exception) {}
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
        require(letters.isNotEmpty()) { "Referência de célula inválida." }
        var value = 0
        for (letter in letters) value = value * 26 + (letter.uppercaseChar() - 'A' + 1)
        return value - 1
    }
}
