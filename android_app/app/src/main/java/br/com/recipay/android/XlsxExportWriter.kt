package br.com.recipay.android

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** XLSX writer compatible with the Windows Gestor import contract. */
object XlsxExportWriter {
    private const val FORMAT_VERSION = 1
    private const val SUPERVISOR_ID = "00000000-0000-4000-8000-000000000001"

    fun write(output: OutputStream, receipts: List<LocalReceipt>, collaborators: List<LocalCollaborator>) {
        require(receipts.isNotEmpty()) { "Cadastre pelo menos uma despesa antes de exportar." }
        val collaboratorsById = collaborators.associateBy { it.id }
        val selectedCollaborators = receipts.map { r ->
            val id = r.collaboratorId ?: error("A despesa de ${r.collaborator} não está vinculada a um colaborador.")
            collaboratorsById[id] ?: error("Colaborador da despesa ${r.collaborator} não foi encontrado.")
        }.distinctBy { it.externalId }

        data class Attachment(val bytes: ByteArray, val hash: String, val extension: String)
        val attachments = receipts.associate { r ->
            val file = java.io.File(r.attachmentPath)
            require(file.isFile) { "Comprovante da despesa de ${r.collaborator} não foi encontrado neste celular." }
            val bytes = file.readBytes()
            val ext = file.extension.lowercase(Locale.ROOT).let { if (it.matches(Regex("[a-z0-9]{1,8}"))) ".$it" else ".img" }
            r.externalId to Attachment(bytes, sha256(bytes), ext)
        }
        val sorted = receipts.sortedWith(compareBy<LocalReceipt> { it.collaborator.lowercase(Locale.ROOT) }.thenBy { it.expenseDate })
        val colabJson = JSONArray()
        selectedCollaborators.forEach { c ->
            colabJson.put(JSONObject().put("id", c.externalId).put("nome", c.name).put("equipe", c.team)
                .put("chave_pix", c.pixKey).put("tipo_chave", c.pixType).put("titular_pix", c.pixHolder))
        }
        val receiptJson = JSONArray()
        sorted.forEach { r ->
            val c = collaboratorsById[r.collaboratorId] ?: error("Colaborador não encontrado.")
            val a = attachments.getValue(r.externalId)
            receiptJson.put(JSONObject().put("id", r.externalId).put("ref", reference(r.externalId))
                .put("colaborador_id", c.externalId).put("equipe", c.team).put("valor_centavos", r.amountCents)
                .put("descricao", r.description).put("data_despesa", r.expenseDate).put("categoria", "OUTROS")
                .put("chave_nfe", JSONObject.NULL)
                .put("comprovante", JSONObject().put("hash", a.hash).put("extensao", a.extension)))
        }
        val total = sorted.fold(0L) { sum, r -> Math.addExact(sum, r.amountCents) }
        val generatedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).format(Date())
        val data = JSONObject().put("versao_formato", FORMAT_VERSION).put("tipo", "RECIBOS")
            .put("lote_id", UUID.randomUUID().toString())
            .put("gerado_por", JSONObject().put("id", SUPERVISOR_ID).put("nome", "Supervisor Android"))
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
            val c = collaboratorsById[r.collaboratorId]!!
            receiptRows += listOf(reference(r.externalId), r.expenseDate, c.name, c.team, r.description, r.amountCents / 100.0, "OUTROS")
        }
        val collaboratorRows = mutableListOf<List<Any?>>()
        collaboratorRows += listOf("Nome", "Equipe", "Tipo da chave", "Chave Pix", "Titular")
        selectedCollaborators.forEach { c ->
            val label = when (c.pixType) {
                "EMAIL" -> "E-mail"
                "TELEFONE" -> "Telefone"
                "ALEATORIA" -> "Chave aleatória"
                else -> c.pixType
            }
            collaboratorRows += listOf(c.name, c.team, label, c.pixKey, c.pixHolder)
        }
        val proofRows = mutableListOf<List<Any?>>()
        proofRows += listOf("Ref", "Colaborador", "Valor (R$)", "Comprovante")
        sorted.forEach { r ->
            val c = collaboratorsById[r.collaboratorId]!!
            proofRows += listOf(reference(r.externalId), c.name, r.amountCents / 100.0, "Comprovante incluído na planilha")
        }
        val sheets = listOf(
            Sheet("Recibos", receiptRows),
            Sheet("Colaboradores", collaboratorRows),
            Sheet("Comprovantes", proofRows),
            Sheet("_recipay", hiddenRows, true)
        )
        val entries = linkedMapOf<String, String>()
        entries["[Content_Types].xml"] = contentTypes(sheets.size)
        entries["_rels/.rels"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
        entries["xl/workbook.xml"] = workbookXml(sheets)
        entries["xl/_rels/workbook.xml.rels"] = workbookRels(sheets.size)
        sheets.forEachIndexed { index, sheet -> entries["xl/worksheets/sheet${index + 1}.xml"] = sheetXml(sheet.rows) }
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, xml) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private data class Sheet(val name: String, val rows: List<List<Any?>>, val veryHidden: Boolean = false)

    private fun addChunks(rows: MutableList<List<Any?>>, key: String, value: String) {
        value.chunked(32000).forEachIndexed { index, part -> rows += listOf(key, index, part) }
        if (value.isEmpty()) rows += listOf(key, 0, "")
    }

    private fun contentTypes(count: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        for (i in 1..count) append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        append("</Types>")
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
        append("</Relationships>")
    }

    private fun sheetXml(rows: List<List<Any?>>) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        rows.forEachIndexed { rowIndex, row ->
            val rn = rowIndex + 1
            append("<row r=\"$rn\">")
            row.forEachIndexed { colIndex, value ->
                if (value != null) {
                    val ref = columnName(colIndex + 1) + rn
                    when (value) {
                        is Number -> append("<c r=\"$ref\"><v>$value</v></c>")
                        else -> append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escape(value.toString())}</t></is></c>")
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
        while (n > 0) {
            result.insert(0, ('A'.code + (n - 1) % 26).toChar())
            n = (n - 1) / 26
        }
        return result.toString()
    }

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
        .filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 0x20 }

    private fun reference(id: String): String = id.replace("-", "").take(8).uppercase(Locale.ROOT)
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
