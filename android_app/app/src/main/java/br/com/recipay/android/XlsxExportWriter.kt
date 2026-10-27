package br.com.recipay.android

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** XLSX writer compatible with the Windows Gestor import contract and the visible workbook template. */
object XlsxExportWriter {
    private const val FORMAT_VERSION = 1
    private const val SUPERVISOR_ID = "00000000-0000-4000-8000-000000000001"

    fun write(
        output: OutputStream,
        receipts: List<LocalReceipt>,
        collaborators: List<LocalCollaborator>,
        attachmentsByReceipt: Map<Long, List<String>> = emptyMap(),
        senderName: String = "Supervisor"
    ) {
        require(receipts.isNotEmpty()) { "Não há despesas cadastradas para exportar." }
        val collaboratorsById = collaborators.associateBy { it.id }
        val fallbackCollaborators = mutableMapOf<String, LocalCollaborator>()
        fun collaboratorFor(receipt: LocalReceipt): LocalCollaborator {
            receipt.collaboratorId?.let { collaboratorsById[it] }?.let { return it }
            collaborators.firstOrNull { it.name.equals(receipt.collaborator, ignoreCase = true) }?.let { return it }
            val key = receipt.collaborator.trim().lowercase(Locale.ROOT)
            return fallbackCollaborators.getOrPut(key) {
                LocalCollaborator(
                    -1L,
                    UUID.nameUUIDFromBytes(("recipay-colaborador:" + key).toByteArray(Charsets.UTF_8)).toString(),
                    receipt.collaborator.trim().ifBlank { "Colaborador não identificado" },
                    "", "", "", receipt.collaborator.trim(), ""
                )
            }
        }
        val collaboratorForReceipt = receipts.associate { it.id to collaboratorFor(it) }
        val selectedCollaborators = receipts.map { collaboratorForReceipt.getValue(it.id) }.distinctBy { it.externalId }
        data class Attachment(val bytes: ByteArray, val hash: String, val extension: String, val missing: Boolean)
        val sorted = receipts.sortedWith(compareBy<LocalReceipt> { it.collaborator.lowercase(Locale.ROOT) }.thenBy { it.expenseDate }.thenBy { it.id })
        val attachments = sorted.associate { r ->
            val candidates = (attachmentsByReceipt[r.id].orEmpty() + r.attachmentPath)
                .filter { it.isNotBlank() }.distinct().map { java.io.File(it) }.filter { it.isFile }
            val file = candidates.firstOrNull()
            val missing = file == null
            val bytes = file?.readBytes() ?: missingProofPdf(r.collaborator, r.description, r.expenseDate)
            val ext = file?.extension?.lowercase(Locale.ROOT)?.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }?.let { ".$it" } ?: ".pdf"
            r.externalId to Attachment(bytes, sha256(bytes), ext, missing)
        }

        val colabJson = JSONArray()
        selectedCollaborators.forEach { c ->
            colabJson.put(JSONObject().put("id", c.externalId).put("nome", c.name).put("equipe", c.team)
                .put("chave_pix", c.pixKey).put("tipo_chave", c.pixType).put("titular_pix", c.pixHolder).put("funcao", c.role))
        }
        val receiptJson = JSONArray()
        sorted.forEach { r ->
            val c = collaboratorForReceipt.getValue(r.id)
            val a = attachments.getValue(r.externalId)
            receiptJson.put(JSONObject().put("id", r.externalId).put("ref", reference(r.externalId))
                .put("colaborador_id", c.externalId).put("equipe", c.team).put("valor_centavos", r.amountCents)
                .put("descricao", r.description).put("data_despesa", r.expenseDate).put("categoria", "OUTROS")
                .put("chave_nfe", JSONObject.NULL)
                .put("comprovante", JSONObject().put("hash", a.hash).put("extensao", a.extension)
                    .put("sem_comprovante_anexado", a.missing)))
        }
        val total = sorted.fold(0L) { sum, r -> Math.addExact(sum, r.amountCents) }
        val generatedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).format(Date())
        val data = JSONObject().put("versao_formato", FORMAT_VERSION).put("tipo", "RECIBOS")
            .put("lote_id", UUID.randomUUID().toString())
            .put("gerado_por", JSONObject().put("id", SUPERVISOR_ID).put("nome", senderName.ifBlank { "Supervisor" }))
            .put("gerado_em", generatedAt).put("total_centavos", total)
            .put("colaboradores", colabJson).put("recibos", receiptJson).put("cancelamentos", JSONArray())
        val jsonText = data.toString()
        val dataHash = sha256(jsonText.toByteArray(Charsets.UTF_8))

        val hiddenRows = mutableListOf<List<Any?>>()
        hiddenRows += listOf("RECIPAY", FORMAT_VERSION, "Não altere esta aba.")
        hiddenRows += listOf("hash", 0, dataHash)
        addChunks(hiddenRows, "dados", jsonText)
        attachments.values.distinctBy { it.hash }.forEach {
            addChunks(hiddenRows, "comp:${it.hash}", Base64.encodeToString(it.bytes, Base64.NO_WRAP))
        }

        val receiptRows = mutableListOf<List<Any?>>()
        receiptRows += listOf("Ref", "Data", "Colaborador", "Equipe", "Descrição", "Valor (R$)", "Categoria")
        sorted.forEach { r ->
            val c = collaboratorForReceipt.getValue(r.id)
            receiptRows += listOf(reference(r.externalId), excelDate(r.expenseDate), c.name, c.team, r.description, r.amountCents / 100.0, "OUTROS")
        }
        receiptRows += listOf("", "", "", "", "TOTAL", total / 100.0, null)
        receiptRows += listOf(null, null, null, null, null, null, null)
        receiptRows += listOf("Enviado por ${senderName.ifBlank { "Supervisor" }} em ${LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))}. Importe esta planilha no Union; não altere os valores.", null, null, null, null, null, null)

        val collaboratorRows = mutableListOf<List<Any?>>()
        collaboratorRows += listOf("Nome", "Equipe", "Tipo da chave", "Chave Pix", "Titular")
        selectedCollaborators.forEach { c ->
            val label = when (c.pixType.uppercase(Locale.ROOT)) {
                "EMAIL", "E-MAIL" -> "E-mail"
                "TELEFONE", "PHONE" -> "Telefone"
                "ALEATORIA", "ALEATÓRIA", "RANDOM" -> "Chave aleatória"
                "CPF" -> "CPF"
                "CNPJ" -> "CNPJ"
                else -> c.pixType
            }
            collaboratorRows += listOf(c.name, c.team, label, c.pixKey, c.pixHolder)
        }
        val proofRows = mutableListOf<List<Any?>>()
        proofRows += listOf("Ref", "Colaborador", "Valor (R$)", "Comprovante")
        sorted.forEach { r ->
            val c = collaboratorForReceipt.getValue(r.id)
            val a = attachments.getValue(r.externalId)
            proofRows += listOf(reference(r.externalId), c.name, r.amountCents / 100.0,
                if (a.missing) "SEM COMPROVANTE ANEXADO — confira antes de pagar" else "Comprovante incluído na planilha")
        }
        val sheets = listOf(
            Sheet("Recibos", receiptRows, listOf(12, 12, 30, 16, 40, 14, 26)),
            Sheet("Colaboradores", collaboratorRows, listOf(30, 16, 16, 36, 30)),
            Sheet("Comprovantes", proofRows, listOf(12, 30, 14, 48)),
            Sheet("_recipay", hiddenRows, emptyList(), true)
        )
        val entries = linkedMapOf<String, String>()
        entries["[Content_Types].xml"] = contentTypes(sheets.size)
        entries["_rels/.rels"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
        entries["xl/workbook.xml"] = workbookXml(sheets)
        entries["xl/_rels/workbook.xml.rels"] = workbookRels(sheets.size)
        entries["xl/styles.xml"] = stylesXml()
        sheets.forEachIndexed { index, sheet -> entries["xl/worksheets/sheet${index + 1}.xml"] = sheetXml(sheet) }
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, xml) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private data class Sheet(val name: String, val rows: List<List<Any?>>, val widths: List<Int>, val veryHidden: Boolean = false)

    private fun missingProofPdf(name: String, description: String, date: String): ByteArray {
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val canvas: Canvas = page.canvas
        canvas.drawColor(Color.WHITE)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170, 35, 35); textSize = 24f; isFakeBoldText = true }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 15f }
        canvas.drawText("SEM COMPROVANTE ANEXADO", 48f, 110f, title)
        canvas.drawText("Esta despesa foi exportada sem arquivo de comprovante.", 48f, 160f, body)
        canvas.drawText("Colaborador: ${name.take(65)}", 48f, 205f, body)
        canvas.drawText("Data: ${date.take(30)}", 48f, 235f, body)
        canvas.drawText("Descrição: ${description.take(65)}", 48f, 265f, body)
        canvas.drawText("Este arquivo é apenas um aviso, não é um comprovante de pagamento.", 48f, 330f, body)
        document.finishPage(page)
        val out = ByteArrayOutputStream()
        document.writeTo(out)
        document.close()
        return out.toByteArray()
    }

    private fun excelDate(value: String): Double {
        return try { LocalDate.parse(value.take(10)).toEpochDay().toDouble() + 25569.0 }
        catch (_: DateTimeParseException) { value.toDoubleOrNull() ?: 0.0 }
    }

    private fun addChunks(rows: MutableList<List<Any?>>, key: String, value: String) {
        value.chunked(32000).forEachIndexed { index, part -> rows += listOf(key, index, part) }
        if (value.isEmpty()) rows += listOf(key, 0, "")
    }

    private fun contentTypes(count: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        for (i in 1..count) append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""")
    }

    private fun workbookXml(sheets: List<Sheet>) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        sheets.forEachIndexed { i, s ->
            append("<sheet name=\"${escape(s.name)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"")
            if (s.veryHidden) append(" state=\"veryHidden\"")
            append("/>")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(count: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..count) append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
        append("""<Relationship Id="rId${count + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
    }

    private fun stylesXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<numFmts count="2"><numFmt numFmtId="164" formatCode="DD/MM/YYYY"/><numFmt numFmtId="165" formatCode="&quot;R$&quot; #,##0.00"/></numFmts>
<fonts count="3"><font><sz val="11"/><name val="Calibri"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF1E5AA8"/><bgColor indexed="64"/></patternFill></fill></fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="6"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" applyFont="1" applyFill="1" xfId="0"/><xf numFmtId="164" fontId="0" fillId="0" borderId="0" applyNumberFormat="1" xfId="0"/><xf numFmtId="165" fontId="0" fillId="0" borderId="0" applyNumberFormat="1" xfId="0"/><xf numFmtId="0" fontId="2" fillId="0" borderId="0" applyFont="1" xfId="0"/><xf numFmtId="165" fontId="2" fillId="0" borderId="0" applyFont="1" applyNumberFormat="1" xfId="0"/></cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""

    private fun sheetXml(sheet: Sheet) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        if (!sheet.veryHidden) append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        if (sheet.widths.isNotEmpty()) {
            append("<cols>")
            sheet.widths.forEachIndexed { index, width -> append("<col min=\"${index + 1}\" max=\"${index + 1}\" width=\"$width\" customWidth=\"1\"/>") }
            append("</cols>")
        }
        append("<sheetData>")
        val totalRowIndex = if (sheet.name == "Recibos") sheet.rows.indexOfFirst { it.getOrNull(4) == "TOTAL" } else -1
        sheet.rows.forEachIndexed { rowIndex, row ->
            val rn = rowIndex + 1
            append("<row r=\"$rn\">")
            row.forEachIndexed { colIndex, value ->
                if (value != null) {
                    val ref = columnName(colIndex + 1) + rn
                    val style = when {
                        sheet.name != "_recipay" && rowIndex == 0 -> 1
                        sheet.name == "Recibos" && rowIndex == totalRowIndex && colIndex == 4 -> 4
                        sheet.name == "Recibos" && rowIndex == totalRowIndex && colIndex == 5 -> 5
                        sheet.name == "Recibos" && rowIndex > 0 && rowIndex < totalRowIndex && colIndex == 1 -> 2
                        sheet.name == "Recibos" && rowIndex > 0 && rowIndex < totalRowIndex && colIndex == 5 -> 3
                        sheet.name == "Comprovantes" && rowIndex > 0 && colIndex == 2 -> 3
                        else -> 0
                    }
                    when (value) {
                        is Number -> append("<c r=\"$ref\" s=\"$style\"><v>$value</v></c>")
                        else -> append("<c r=\"$ref\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escape(value.toString())}</t></is></c>")
                    }
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    private fun columnName(number: Int): String {
        var n = number
        val result = StringBuilder()
        while (n > 0) { result.insert(0, ('A'.code + (n - 1) % 26).toChar()); n = (n - 1) / 26 }
        return result.toString()
    }

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
        .filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 0x20 }

    private fun reference(id: String): String = id.replace("-", "").take(8).uppercase(Locale.ROOT)
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
