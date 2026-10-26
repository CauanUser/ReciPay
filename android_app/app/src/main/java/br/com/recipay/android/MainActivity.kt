package br.com.recipay.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.NumberFormat
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var chooseButton: Button
    private var selectedPreview: ReceiptPreview? = null
    private val blue = Color.rgb(30, 90, 168)
    private val paleBlue = Color.rgb(238, 245, 255)
    private val ink = Color.rgb(30, 41, 59)
    private val muted = Color.rgb(100, 116, 139)
    private val green = Color.rgb(22, 128, 72)
    private val red = Color.rgb(185, 28, 28)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderHome()
    }

    private fun renderHome() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(247, 249, 252))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(22))
            setBackgroundColor(blue)
        }
        header.addView(label("Recipay", 28, Color.WHITE, true))
        header.addView(label("Despesas organizadas. Pagamentos conferidos.", 14, Color.rgb(225, 237, 255), false).apply {
            setPadding(0, dp(5), 0, 0)
        })
        root.addView(header)

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        content.addView(label("Área do Gestor", 22, ink, true))
        content.addView(label("Importe a planilha enviada pelo Supervisor e confira os dados antes de qualquer ação.", 14, muted, false).apply {
            setPadding(0, dp(6), 0, dp(18))
        })

        val card = card()
        card.addView(label("Importar arquivo de recibos", 17, ink, true))
        card.addView(label("Selecione um arquivo .xlsx exportado pelo Recipay para validar o lote e visualizar os recibos.", 14, muted, false).apply {
            setPadding(0, dp(8), 0, dp(16))
        })
        chooseButton = actionButton("Selecionar planilha")
        chooseButton.setOnClickListener { openXlsxPicker() }
        card.addView(chooseButton, matchWrap())
        content.addView(card, spaced())

        val note = card()
        note.addView(label("Prévia segura", 16, blue, true))
        note.addView(label("Nesta versão, o arquivo é apenas validado e exibido. Nenhum recibo ou pagamento é gravado, e nenhum Pix é enviado.", 14, muted, false).apply {
            setPadding(0, dp(7), 0, 0)
        })
        content.addView(note, spaced())
        showEmptyState()
    }

    private fun showEmptyState() {
        val empty = card()
        empty.addView(label("Nenhum arquivo selecionado", 16, ink, true))
        empty.addView(label("Depois de selecionar a planilha, você verá o supervisor, o total do lote e cada recibo validado.", 14, muted, false).apply {
            setPadding(0, dp(7), 0, 0)
        })
        content.addView(empty, spaced())
    }

    private fun openXlsxPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        }
        startActivityForResult(intent, REQUEST_XLSX)
    }

    @Deprecated("Use file picker result callback; kept compatible with the minimal native prototype.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_XLSX || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        chooseButton.isEnabled = false
        chooseButton.text = "Validando planilha…"
        Thread {
            try {
                val name = displayName(uri)
                val stream = contentResolver.openInputStream(uri) ?: error("Não foi possível abrir o arquivo selecionado.")
                val preview = stream.use { XlsxPreviewReader.read(it, name) }
                runOnUiThread {
                    chooseButton.isEnabled = true
                    chooseButton.text = "Selecionar outra planilha"
                    selectedPreview = preview
                    renderPreview(preview)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    chooseButton.isEnabled = true
                    chooseButton.text = "Tentar novamente"
                    selectedPreview = null
                    renderError(error.message ?: "Não foi possível validar esta planilha.")
                }
            }
        }.start()
    }

    private fun renderPreview(preview: ReceiptPreview) {
        content.removeViews(4, (content.childCount - 4).coerceAtLeast(0))
        val status = card()
        status.addView(label("✓  Planilha validada", 17, green, true))
        status.addView(label(preview.fileName, 13, muted, false).apply { setPadding(0, dp(5), 0, 0) })
        content.addView(status, spaced())

        val summary = card()
        summary.addView(label("Resumo do lote", 18, ink, true))
        summary.addView(label("Supervisor: ${preview.supervisor}", 14, muted, false).apply { setPadding(0, dp(10), 0, 0) })
        summary.addView(label("Recibos: ${preview.items.size}", 14, muted, false).apply { setPadding(0, dp(5), 0, 0) })
        summary.addView(label("Total do lote", 13, muted, false).apply { setPadding(0, dp(12), 0, 0) })
        summary.addView(label(money(preview.totalCents), 27, blue, true).apply { setPadding(0, dp(2), 0, 0) })
        summary.addView(label("Identificador: ${preview.batchId}", 11, muted, false).apply { setPadding(0, dp(8), 0, 0) })
        content.addView(summary, spaced())

        content.addView(label("Recibos conferidos", 19, ink, true).apply { setPadding(dp(2), dp(8), 0, dp(10)) })
        if (preview.items.isEmpty()) {
            val noItems = card()
            noItems.addView(label("Este lote não contém recibos.", 14, muted, false))
            content.addView(noItems, spaced())
        }
        preview.items.forEach { item ->
            val row = card()
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(label(item.collaborator, 16, ink, true))
            left.addView(label("Ref. ${item.reference}", 12, muted, false).apply { setPadding(0, dp(4), 0, 0) })
            left.addView(label("✓ Comprovante íntegro", 12, green, false).apply { setPadding(0, dp(5), 0, 0) })
            top.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(label(money(item.amountCents), 16, ink, true).apply { gravity = Gravity.CENTER_VERTICAL })
            row.addView(top)
            content.addView(row, spaced())
        }

        val info = card()
        info.setBackgroundColor(paleBlue)
        info.addView(label("Próxima etapa", 16, blue, true))
        info.addView(label("Esta é somente a prévia. A gravação segura, a prevenção de duplicidade e o retorno ao Windows serão implementados e testados antes de habilitar a importação real.", 14, ink, false).apply {
            setPadding(0, dp(7), 0, 0)
        })
        content.addView(info, spaced())
    }

    private fun renderError(message: String) {
        content.removeViews(2, (content.childCount - 2).coerceAtLeast(0))
        val errorCard = card()
        errorCard.addView(label("Não foi possível validar", 17, red, true))
        errorCard.addView(label(message, 14, ink, false).apply { setPadding(0, dp(8), 0, 0) })
        errorCard.addView(label("Nenhum dado foi importado.", 13, green, true).apply { setPadding(0, dp(10), 0, 0) })
        content.addView(errorCard, spaced())
    }

    private fun displayName(uri: Uri): String {
        var name = "planilha.xlsx"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) name = cursor.getString(index) ?: name
            }
        }
        return name
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(Color.WHITE, dp(16))
        elevation = dp(1).toFloat()
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.START
            includeFontPadding = true
        }

    private fun actionButton(text: String): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.WHITE)
        background = rounded(blue, dp(12))
        minHeight = dp(50)
        stateListAnimator = null
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
    private fun spaced() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun money(cents: Long): String = NumberFormat.getCurrencyInstance(Locale("pt", "BR")).format(cents / 100.0)

    companion object {
        private const val REQUEST_XLSX = 1042
    }
}
