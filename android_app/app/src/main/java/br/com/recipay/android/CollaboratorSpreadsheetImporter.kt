package br.com.recipay.android

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/** Leitor simples de .xlsx para a importação de colaboradores, sem dependências externas. */
object CollaboratorSpreadsheetImporter {
    data class Row(
        val name: String,
        val team: String,
        val pixKey: String,
        val pixType: String,
        val pixHolder: String,
        val role: String
    )

    fun read(input: InputStream): List<Row> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "xl/sharedStrings.xml" || entry.name == "xl/worksheets/sheet1.xml") {
                    entries[entry.name] = zip.readBytes()
                }
                zip.closeEntry()
            }
        }
        val sheet = entries["xl/worksheets/sheet1.xml"]
            ?: error("Não encontrei a primeira aba. Salve o arquivo no formato .xlsx e tente novamente.")
        val shared = entries["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        val matrix = parseSheet(sheet, shared)
        if (matrix.isEmpty()) error("A planilha está vazia.")
        val headers = matrix.first().map(::normalizeHeader)
        val indexes = headers.withIndex().associate { it.value to it.index }
        val missing = listOf("nome", "chave_pix").filter { it !in indexes }
        if (missing.isNotEmpty()) error("Colunas obrigatórias ausentes: ${missing.joinToString(", ")}. Use o modelo ReciPay.")
        return matrix.drop(1).mapNotNull { cells ->
            fun value(key: String) = indexes[key]?.let { cells.getOrElse(it) { "" } }?.trim().orEmpty()
            val row = Row(value("nome"), value("equipe"), value("chave_pix"), value("tipo_chave"), value("titular_pix"), value("cargo"))
            if (listOf(row.name, row.team, row.pixKey, row.pixType, row.pixHolder, row.role).all { it.isBlank() }) null else row
        }
    }

    fun normalizeType(raw: String, key: String): String? {
        val type = raw.trim().uppercase(Locale.ROOT).replace("É", "E").replace("Á", "A")
            .replace(" ", "").replace("-", "").replace("_", "")
        return when (type) {
            "CPF" -> "CPF"
            "CNPJ" -> "CNPJ"
            "EMAIL", "E-MAIL" -> "EMAIL"
            "TELEFONE", "CELULAR", "PHONE" -> "TELEFONE"
            "ALEATORIA", "CHAVEALEATORIA", "RANDOM" -> "ALEATORIA"
            "" -> when {
                key.contains("@") -> "EMAIL"
                key.replace(Regex("\\D"), "").length == 11 -> "CPF"
                key.replace(Regex("\\D"), "").length == 14 -> "CNPJ"
                key.replace(Regex("\\D"), "").length in 10..13 -> "TELEFONE"
                else -> "ALEATORIA"
            }
            else -> null
        }
    }

    private fun normalizeHeader(value: String): String {
        val s = value.trim().lowercase(Locale.ROOT)
            .replace("á", "a").replace("ã", "a").replace("â", "a")
            .replace("é", "e").replace("ê", "e").replace("í", "i")
            .replace("ó", "o").replace("ô", "o").replace("õ", "o").replace("ú", "u").replace("ç", "c")
            .replace(" ", "_").replace("-", "_")
        return when (s) {
            "nome_completo", "colaborador" -> "nome"
            "time" -> "equipe"
            "chave", "pix" -> "chave_pix"
            "tipo", "tipo_da_chave" -> "tipo_chave"
            "titular", "titular_da_chave" -> "titular_pix"
            "funcao", "cargo_funcao" -> "cargo"
            else -> s
        }
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val parser = newParser(bytes)
        val result = mutableListOf<String>()
        var inString = false
        val current = StringBuilder()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> { inString = true; current.setLength(0) }
                    "t" -> if (inString) current.append(parser.nextText())
                }
                XmlPullParser.END_TAG -> if (parser.name == "si") { result += current.toString(); inString = false }
            }
            parser.next()
        }
        return result
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val parser = newParser(bytes)
        val rows = mutableListOf<List<String>>()
        var row = mutableMapOf<Int, String>()
        var cellRef = ""
        var cellType = ""
        var value = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> row = mutableMapOf()
                    "c" -> { cellRef = parser.getAttributeValue(null, "r") ?: ""; cellType = parser.getAttributeValue(null, "t") ?: ""; value = "" }
                    "v" -> value = parser.nextText()
                    "t" -> if (cellType == "inlineStr") value += parser.nextText()
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "c" -> {
                        val index = columnIndex(cellRef)
                        val resolved = if (cellType == "s") shared.getOrNull(value.toIntOrNull() ?: -1).orEmpty() else value
                        if (index >= 0) row[index] = resolved
                    }
                    "row" -> {
                        val max = row.keys.maxOrNull() ?: -1
                        if (max >= 0) rows += (0..max).map { row[it].orEmpty() }
                    }
                }
            }
            parser.next()
        }
        return rows
    }

    private fun columnIndex(ref: String): Int {
        val letters = ref.takeWhile { it.isLetter() }
        if (letters.isEmpty()) return -1
        var value = 0
        letters.forEach { value = value * 26 + (it.uppercaseChar() - 'A' + 1) }
        return value - 1
    }

    private fun newParser(bytes: ByteArray): XmlPullParser =
        XmlPullParserFactory.newInstance().newPullParser().apply { setInput(bytes.inputStream(), "UTF-8") }
}
