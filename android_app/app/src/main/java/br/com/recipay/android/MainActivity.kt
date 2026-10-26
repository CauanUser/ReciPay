package br.com.recipay.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.NumberFormat
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var database: ReceiptDatabase
    private lateinit var content: LinearLayout
    private lateinit var collaboratorSpinner: Spinner
    private var collaboratorOptions: List<LocalCollaborator> = emptyList()
    private lateinit var amountInput: EditText
    private lateinit var descriptionInput: EditText
    private lateinit var dateInput: EditText
    private lateinit var photoButton: Button
    private lateinit var statusLabel: TextView
    private var selectedPhotoUri: Uri? = null
    private var currentScreen: String = "home"

    private val blue = Color.rgb(30, 90, 168)
    private val ink = Color.rgb(30, 41, 59)
    private val muted = Color.rgb(100, 116, 139)
    private val green = Color.rgb(22, 128, 72)
    private val red = Color.rgb(185, 28, 28)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = ReceiptDatabase(this)
        renderHome()
    }

    private fun renderHome() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(247, 249, 252)) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            setBackgroundColor(blue)
        }
        header.addView(label("Recipay", 26, Color.WHITE, true))
        header.addView(label("Área do Supervisor", 14, Color.rgb(225, 237, 255), false))
        root.addView(header)
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(22)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(dp(4), dp(6), dp(4), dp(6)); setBackgroundColor(Color.WHITE); elevation = dp(8).toFloat()
        }
        listOf("home" to "Início", "expenses" to "Despesas", "people" to "Pessoas", "exchange" to "Envios").forEach { (screen, title) ->
            nav.addView(Button(this).apply {
                text = title; isAllCaps = false; textSize = 12f; minHeight = dp(44); setPadding(dp(2), 0, dp(2), 0)
                setTextColor(if (screen == currentScreen) blue else muted)
                background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(10).toFloat() }
                setOnClickListener { navigate(screen) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(nav)
        setContentView(root)
        navigate("home")
    }

    private fun navigate(screen: String) {
        currentScreen = screen
        if (!::content.isInitialized) return
        when (screen) {
            "home" -> renderDashboard()
            "expenses" -> renderReceipts()
            "people" -> renderCollaborators()
            "exchange" -> renderIntegration()
            "new_expense" -> renderExpenseForm()
            else -> renderDashboard()
        }
        val root = content.parent?.parent as? LinearLayout ?: return
        val nav = root.getChildAt(root.childCount - 1) as? LinearLayout ?: return
        val keys = listOf("home", "expenses", "people", "exchange")
        for (i in 0 until nav.childCount) (nav.getChildAt(i) as? Button)?.setTextColor(if (keys.getOrNull(i) == currentScreen) blue else muted)
    }

    private fun renderDashboard() {
        content.removeAllViews()
        val receipts = database.listReceipts()
        val collaborators = database.listCollaborators()
        val total = receipts.fold(0L) { sum, r -> Math.addExact(sum, r.amountCents) }
        val pending = receipts.sumOf { database.paymentStatus(it.externalId).balanceCents }
        content.addView(label("Olá, Supervisor 👋", 24, ink, true))
        content.addView(label("Resumo das suas despesas", 14, muted, false).apply { setPadding(0, dp(5), 0, dp(16)) })
        val summary = card()
        summary.addView(label("Total registrado", 13, muted, false))
        summary.addView(label(money(total), 28, blue, true).apply { setPadding(0, dp(5), 0, dp(8)) })
        summary.addView(label("${receipts.size} despesa(s) · ${collaborators.size} colaborador(es)", 13, muted, false))
        content.addView(summary, spaced())
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stats.addView(metricCard("Saldo pendente", money(pending), blue), LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        stats.addView(metricCard("Já pago", money(total - pending), green), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
        content.addView(stats, spaced())
        content.addView(label("Ações rápidas", 19, ink, true).apply { setPadding(0, dp(8), 0, dp(10)) })
        content.addView(actionButton("＋  Registrar despesa").apply { setOnClickListener { navigate("new_expense") } }, spaced())
        content.addView(actionButton("＋  Cadastrar colaborador").apply { setOnClickListener { navigate("people"); openCollaboratorDialog() } }, spaced())
        content.addView(actionButton("⇄  Trocar arquivos com o Gestor").apply { setOnClickListener { navigate("exchange") } }, spaced())
        content.addView(label("Últimas despesas", 19, ink, true).apply { setPadding(0, dp(8), 0, dp(10)) })
        if (receipts.isEmpty()) content.addView(emptyState("Ainda não há despesas. Registre a primeira para começar."))
        else {
            receipts.take(3).forEach { r ->
                val payment = database.paymentStatus(r.externalId)
                val item = card()
                item.addView(label(r.collaborator, 15, ink, true))
                item.addView(label(r.description, 13, muted, false).apply { setPadding(0, dp(4), 0, 0) })
                item.addView(label(money(r.amountCents), 18, blue, true).apply { setPadding(0, dp(6), 0, 0) })
                item.addView(statusChip("${payment.status} · saldo ${money(payment.balanceCents)}", payment.status))
                content.addView(item, spaced())
            }
            content.addView(actionButton("Ver todas as despesas").apply { setOnClickListener { navigate("expenses") } }, spaced())
        }
    }

    private fun renderExpenseForm() {
        content.removeAllViews()
        content.addView(label("Nova despesa", 24, ink, true))
        content.addView(label("Preencha os dados e anexe o comprovante.", 14, muted, false).apply { setPadding(0, dp(5), 0, dp(16)) })
        val form = card()
        collaboratorSpinner = Spinner(this)
        refreshCollaboratorSpinner()
        amountInput = field("Valor em reais (ex.: 125,50)").apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        descriptionInput = field("Descrição da despesa")
        dateInput = field("Data (AAAA-MM-DD)").apply { setText(java.time.LocalDate.now().toString()) }
        form.addView(fieldLabel("Colaborador")); form.addView(collaboratorSpinner, fieldParams())
        form.addView(fieldLabel("Valor em reais")); form.addView(amountInput, fieldParams())
        form.addView(fieldLabel("Descrição")); form.addView(descriptionInput, fieldParams())
        form.addView(fieldLabel("Data da despesa")); form.addView(dateInput, fieldParams())
        photoButton = actionButton("Anexar comprovante obrigatório")
        photoButton.setOnClickListener { openPhotoPicker() }; form.addView(photoButton, fieldParams())
        statusLabel = label(if (selectedPhotoUri == null) "O comprovante é obrigatório." else "Comprovante selecionado ✓", 13, if (selectedPhotoUri == null) muted else green, false)
        statusLabel.setPadding(0, dp(4), 0, dp(10)); form.addView(statusLabel)
        form.addView(actionButton("Salvar despesa").apply { setOnClickListener { saveReceipt() } }, matchWrap())
        content.addView(form, spaced())
        content.addView(actionButton("Cancelar").apply { setOnClickListener { navigate("expenses") } }, spaced())
        if (collaboratorOptions.isEmpty()) {
            content.addView(emptyState("Cadastre primeiro um colaborador e sua chave Pix."), spaced())
            content.addView(actionButton("Cadastrar colaborador").apply { setOnClickListener { navigate("people"); openCollaboratorDialog() } }, spaced())
        }
    }

    private fun renderCollaborators() {
        content.removeAllViews()
        val collaborators = database.listCollaborators()
        content.addView(label("Pessoas", 24, ink, true))
        content.addView(label("Colaboradores e dados Pix", 14, muted, false).apply { setPadding(0, dp(5), 0, dp(16)) })
        content.addView(actionButton("＋  Cadastrar colaborador").apply { setOnClickListener { openCollaboratorDialog() } }, spaced())
        content.addView(label("${collaborators.size} colaborador(es)", 14, muted, true).apply { setPadding(0, dp(8), 0, dp(10)) })
        if (collaborators.isEmpty()) { content.addView(emptyState("Nenhum colaborador cadastrado. Adicione o primeiro para registrar despesas.")); return }
        collaborators.forEach { c ->
            val item = card()
            item.addView(label(c.name, 17, ink, true))
            if (c.team.isNotBlank()) item.addView(label("Equipe: ${c.team}", 13, muted, false).apply { setPadding(0, dp(4), 0, 0) })
            item.addView(label("${c.pixType}: ${c.pixKey}", 14, blue, true).apply { setPadding(0, dp(8), 0, 0) })
            item.addView(label("Titular: ${c.pixHolder}", 13, muted, false).apply { setPadding(0, dp(4), 0, 0) })
            content.addView(item, spaced())
        }
    }

    private fun renderIntegration() {
        content.removeAllViews()
        content.addView(label("Envios e retornos", 24, ink, true))
        content.addView(label("Troque arquivos com o Gestor Windows. Não é necessário conectar uma conta bancária.", 14, muted, false).apply { setPadding(0, dp(5), 0, dp(16)) })
        val exportCard = card()
        exportCard.addView(label("1. Enviar despesas", 18, ink, true))
        exportCard.addView(label("Gere uma planilha com despesas, colaboradores, chaves Pix e comprovantes.", 14, muted, false).apply { setPadding(0, dp(6), 0, dp(12)) })
        exportCard.addView(actionButton("Exportar planilha").apply { setOnClickListener { exportReceipts() } })
        content.addView(exportCard, spaced())
        val importCard = card()
        importCard.addView(label("2. Receber pagamentos", 18, ink, true))
        importCard.addView(label("Depois que o Gestor registrar os pagamentos, importe o XLSX de retorno para atualizar os saldos.", 14, muted, false).apply { setPadding(0, dp(6), 0, dp(12)) })
        importCard.addView(actionButton("Importar retorno do Gestor").apply { setOnClickListener { choosePaymentReturn() } })
        content.addView(importCard, spaced())
        val receipts = database.listReceipts()
        val pending = receipts.sumOf { database.paymentStatus(it.externalId).balanceCents }
        content.addView(label("Resumo", 18, ink, true).apply { setPadding(0, dp(8), 0, dp(8)) })
        content.addView(emptyState("${receipts.size} despesa(s) · saldo pendente ${money(pending)}"))
    }

    private fun metricCard(title: String, value: String, valueColor: Int): LinearLayout = card().apply {
        addView(label(title, 12, muted, false))
        addView(label(value, 17, valueColor, true).apply { setPadding(0, dp(6), 0, 0) })
    }

    private fun emptyState(message: String): LinearLayout = card().apply { addView(label(message, 14, muted, false)) }

    private fun statusChip(text: String, status: String): TextView = label(text, 12, when (status) {
        "Pago" -> green
        "Parcial" -> Color.rgb(180, 83, 9)
        else -> blue
    }, true).apply { setPadding(0, dp(8), 0, 0) }

    private fun openCollaboratorDialog() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        fun addInput(hint: String): EditText {
            val input = field(hint)
            form.addView(input, fieldParams())
            return input
        }
        val name = addInput("Nome completo do colaborador")
        val team = addInput("Equipe (opcional)")
        val typeLabel = label("Tipo da chave Pix", 13, ink, true).apply {
            setPadding(0, dp(8), 0, dp(4))
        }
        form.addView(typeLabel)
        val type = Spinner(this)
        val types = listOf("CPF", "CNPJ", "E-mail", "Telefone", "Chave aleatória")
        type.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)
        form.addView(type, fieldParams())
        val key = addInput("Chave Pix")
        val holder = addInput("Nome do titular da chave Pix")
        AlertDialog.Builder(this)
            .setTitle("Novo colaborador")
            .setView(form)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar") { _, _ ->
                val collaboratorName = name.text.toString().trim()
                val pixKey = key.text.toString().trim()
                val pixHolder = holder.text.toString().trim()
                if (collaboratorName.isBlank() || pixKey.isBlank() || pixHolder.isBlank()) {
                    toast("Preencha nome, chave Pix e titular.")
                    return@setPositiveButton
                }
                try {
                    database.addCollaborator(
                        collaboratorName, team.text.toString(),
                        listOf("CPF", "CNPJ", "EMAIL", "TELEFONE", "ALEATORIA")[type.selectedItemPosition],
                        pixKey, pixHolder
                    )
                    toast("Colaborador cadastrado neste celular.")
                    navigate("people")
                } catch (_: android.database.sqlite.SQLiteConstraintException) {
                    toast("Já existe um colaborador com esse nome.")
                } catch (error: Exception) {
                    toast(error.message ?: "Não foi possível cadastrar o colaborador.")
                }
            }
            .show()
    }

    private fun saveReceipt() {
        val selectedCollaborator = collaboratorOptions.getOrNull(collaboratorSpinner.selectedItemPosition)
        val amountText = amountInput.text.toString().trim()
        val description = descriptionInput.text.toString().trim()
        val date = dateInput.text.toString().trim()
        val photoUri = selectedPhotoUri

        if (selectedCollaborator == null || amountText.isBlank() || description.isBlank() || date.isBlank()) {
            toast(if (selectedCollaborator == null) "Cadastre um colaborador antes de registrar a despesa." else "Preencha todos os campos.")
            return
        }
        if (photoUri == null) {
            toast("Anexe o comprovante antes de salvar.")
            return
        }
        val normalizedAmount = amountText.replace(".", "").replace(",", ".")
        val amountCents = try {
            val value = java.math.BigDecimal(normalizedAmount).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
            if (value <= 0L) throw IllegalArgumentException()
            value
        } catch (_: Exception) {
            toast("Informe um valor válido, maior que zero.")
            return
        }
        try {
            java.time.LocalDate.parse(date)
        } catch (_: Exception) {
            toast("Use a data no formato AAAA-MM-DD.")
            return
        }

        try {
            val attachmentDir = File(filesDir, "comprovantes").apply { mkdirs() }
            val attachment = File(attachmentDir, "comprovante_${System.currentTimeMillis()}.img")
            contentResolver.openInputStream(photoUri).use { input ->
                if (input == null) error("Não foi possível abrir o comprovante.")
                attachment.outputStream().use { output -> input.copyTo(output) }
            }
            database.addReceipt(selectedCollaborator.id, selectedCollaborator.name, amountCents, description, date, attachment.absolutePath)
            amountInput.text.clear()
            descriptionInput.text.clear()
            dateInput.setText(java.time.LocalDate.now().toString())
            selectedPhotoUri = null
            photoButton.text = "Anexar foto do comprovante"
            statusLabel.text = "Comprovante obrigatório."
            statusLabel.setTextColor(muted)
            toast("Despesa salva neste celular.")
            navigate("expenses")
        } catch (error: Exception) {
            toast(error.message ?: "Não foi possível salvar a despesa.")
        }
    }

    private fun openPhotoPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, REQUEST_PHOTO)
    }

    private fun choosePaymentReturn() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        }
        startActivityForResult(intent, REQUEST_RETURN)
    }

    private fun exportReceipts() {
        if (database.listReceipts().isEmpty()) {
            toast("Cadastre pelo menos uma despesa antes de exportar.")
            return
        }
        val filename = "recipay_supervisor_${java.time.LocalDate.now()}.xlsx"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_TITLE, filename)
        }
        startActivityForResult(intent, REQUEST_EXPORT)
    }

    @Deprecated("Kept compatible with the minimal native prototype.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data?.data == null) return
        if (requestCode == REQUEST_EXPORT) {
            try {
                contentResolver.openOutputStream(data.data!!).use { output ->
                    if (output == null) error("Não foi possível criar a planilha.")
                    XlsxExportWriter.write(output, database.listReceipts(), database.listCollaborators())
                }
                toast("Planilha criada. Envie o arquivo ao Gestor para importar no Recipay.")
            } catch (error: Exception) {
                toast(error.message ?: "Não foi possível exportar as despesas.")
            }
            return
        }
        if (requestCode == REQUEST_RETURN) {
            try {
                val uri = data.data!!
                val preview = contentResolver.openInputStream(uri).use { input ->
                    if (input == null) error("Não foi possível abrir o arquivo.")
                    XlsxReturnReader.read(input, uri.lastPathSegment ?: "retorno.xlsx", SUPERVISOR_ID)
                }
                val total = money(preview.totalCents)
                AlertDialog.Builder(this)
                    .setTitle("Confirmar retorno do Gestor")
                    .setMessage("Gestor: ${preview.managerName}\nPagamentos no arquivo: ${preview.transfers.size}\nTotal: $total\n\nO app validou o arquivo e os comprovantes. Deseja importar e atualizar os saldos?")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Importar") { _, _ ->
                        try {
                            val result = database.importPaymentReturn(preview, File(filesDir, "payment_proofs"))
                            toast("Retorno processado: ${result.importedTransfers} novo(s), ${result.ignoredTransfers} repetido(s).")
                            navigate("expenses")
                        } catch (error: Exception) {
                            toast(error.message ?: "Não foi possível importar o retorno.")
                        }
                    }
                    .show()
            } catch (error: Exception) {
                toast(error.message ?: "Arquivo de retorno inválido.")
            }
            return
        }
        if (requestCode != REQUEST_PHOTO) return
        selectedPhotoUri = data.data
        if (selectedPhotoUri != null) {
            photoButton.text = "Comprovante selecionado ✓"
            statusLabel.text = "Imagem selecionada. Ela será copiada para o armazenamento privado do app ao salvar."
            statusLabel.setTextColor(green)
        }
    }

    private fun refreshCollaboratorSpinner() {
        collaboratorOptions = database.listCollaborators()
        val labels = if (collaboratorOptions.isEmpty()) listOf("Cadastre primeiro um colaborador")
            else collaboratorOptions.map { it.name }
        collaboratorSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        collaboratorSpinner.isEnabled = collaboratorOptions.isNotEmpty()
    }

    private fun renderReceipts() {
        content.removeAllViews()
        val receipts = database.listReceipts()
        content.addView(label("Despesas", 24, ink, true))
        content.addView(label("Consulte valores, comprovantes e o andamento de cada reembolso.", 14, muted, false).apply { setPadding(0, dp(5), 0, dp(16)) })
        content.addView(actionButton("＋  Registrar despesa").apply { setOnClickListener { navigate("new_expense") } }, spaced())
        val total = receipts.fold(0L) { sum, r -> Math.addExact(sum, r.amountCents) }
        val pending = receipts.sumOf { database.paymentStatus(it.externalId).balanceCents }
        content.addView(label("${receipts.size} despesa(s) · total ${money(total)} · pendente ${money(pending)}", 13, muted, true).apply { setPadding(0, dp(8), 0, dp(12)) })
        if (receipts.isEmpty()) {
            content.addView(emptyState("Nenhuma despesa registrada. Toque em “Registrar despesa” para adicionar a primeira."))
            return
        }
        receipts.forEach { receipt ->
            val item = card()
            val payment = database.paymentStatus(receipt.externalId)
            item.addView(label(receipt.collaborator, 16, ink, true))
            item.addView(label(money(receipt.amountCents), 21, blue, true).apply { setPadding(0, dp(5), 0, 0) })
            item.addView(label(receipt.description, 14, ink, false).apply { setPadding(0, dp(4), 0, 0) })
            item.addView(label("Data: ${receipt.expenseDate}", 12, muted, false).apply { setPadding(0, dp(5), 0, 0) })
            item.addView(statusChip(payment.status, payment.status))
            item.addView(label("Pago: ${money(payment.paidCents)} · Saldo: ${money(payment.balanceCents)}", 13, muted, false).apply { setPadding(0, dp(5), 0, 0) })
            content.addView(item, spaced())
        }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(16).toFloat()
        }
        elevation = dp(1).toFloat()
    }

    private fun field(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        textSize = 15f
        setSingleLine(true)
        setTextColor(ink)
        setHintTextColor(muted)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply {
            setColor(Color.rgb(248, 250, 252))
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), Color.rgb(203, 213, 225))
        }
    }

    private fun fieldLabel(text: String) = label(text, 13, ink, true).apply {
        setPadding(0, dp(9), 0, dp(4))
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
        background = GradientDrawable().apply {
            setColor(blue)
            cornerRadius = dp(12).toFloat()
        }
        minHeight = dp(50)
        stateListAnimator = null
    }

    private fun fieldParams() = LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = dp(4)
    }
    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
    private fun spaced() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun money(cents: Long): String =
        NumberFormat.getCurrencyInstance(Locale("pt", "BR")).format(cents / 100.0)
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQUEST_PHOTO = 1043
        private const val REQUEST_EXPORT = 1044
        private const val REQUEST_RETURN = 1045
        private const val SUPERVISOR_ID = "00000000-0000-4000-8000-000000000001"
    }
}
