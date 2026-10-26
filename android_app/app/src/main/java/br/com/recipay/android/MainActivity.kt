package br.com.recipay.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import androidx.core.content.FileProvider
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private var pendingCameraUri: Uri? = null
    private var currentScreen: String = "home"

    private val blue = Color.rgb(139, 92, 246)
    private val ink = Color.rgb(248, 250, 252)
    private val muted = Color.rgb(180, 169, 205)
    private val green = Color.rgb(74, 222, 128)
    private val red = Color.rgb(251, 113, 133)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showIntro()
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !isDestroyed) {
                database = ReceiptDatabase(this)
                renderHome()
            }
        }, 1100)
    }

    private fun showIntro() {
        val intro = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(5, 2, 8))
            setPadding(dp(56), dp(56), dp(56), dp(56))
        }
        val logo = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.recipay_launcher_foreground)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "Logo ReciPay"
        }
        intro.addView(logo, LinearLayout.LayoutParams(dp(230), dp(230)))
        intro.addView(label("ReciPay", 28, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(4))
        })
        intro.addView(label("GESTÃO FINANCEIRA SIMPLES", 11, Color.rgb(180, 130, 255), true).apply {
            gravity = Gravity.CENTER
        })
        setContentView(intro)
    }

    private lateinit var screenRoot: LinearLayout

    private fun renderHome() {
        screenRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 6, 25))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(31, 10, 67), Color.rgb(91, 33, 182), blue))
        }
        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brandLogo = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.recipay_launcher_foreground)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "Logo ReciPay"
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(38, 255, 255, 255))
                setStroke(dp(1), Color.argb(95, 255, 255, 255))
            }
        }
        brandRow.addView(brandLogo, LinearLayout.LayoutParams(dp(58), dp(58)).apply {
            rightMargin = dp(13)
        })
        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brandText.addView(label("ReciPay", 27, Color.WHITE, true))
        brandText.addView(label("GESTÃO DE DESPESAS  •  SUPERVISOR", 12, Color.rgb(233, 213, 255), true).apply {
            setPadding(0, dp(3), 0, 0)
        })
        brandRow.addView(brandText, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(brandRow)
        screenRoot.addView(header)

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(20))
        }
        scroll.addView(content)
        screenRoot.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(7), dp(4), dp(7))
            setBackgroundColor(Color.rgb(17, 10, 34))
            elevation = dp(8).toFloat()
        }
        listOf(
            "home" to "Início",
            "receipts" to "Despesas",
            "people" to "Pessoas",
            "files" to "Arquivos"
        ).forEach { (key, title) ->
            val button = TextView(this).apply {
                text = title
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(12), dp(2), dp(12))
                setTypeface(typeface, Typeface.BOLD)
                background = if (key == currentScreen) GradientDrawable().apply { setColor(Color.rgb(46, 25, 78)); cornerRadius = dp(12).toFloat() } else GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dp(12).toFloat() }
                setTextColor(if (key == currentScreen) blue else muted)
                setOnClickListener { showScreen(key) }
            }
            nav.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
        }
        screenRoot.addView(nav)
        setContentView(screenRoot)
        showScreen("home")
    }

    private fun showScreen(screen: String) {
        currentScreen = screen
        content.removeAllViews()
        when (screen) {
            "home" -> renderDashboard()
            "new" -> renderExpenseForm()
            "receipts" -> renderReceipts()
            "people" -> renderCollaborators()
            "files" -> renderFiles()
        }
        // Rebuild the bottom navigation to reflect the selected destination.
        val nav = screenRoot.getChildAt(2) as? LinearLayout ?: return
        for (i in 0 until nav.childCount) {
            val item = nav.getChildAt(i) as? TextView ?: continue
            val key = listOf("home", "receipts", "people", "files").getOrNull(i)
            val selected = key == screen || (screen == "new" && key == "receipts")
            item.setTextColor(if (selected) blue else muted)
            item.background = if (selected) GradientDrawable().apply { setColor(Color.rgb(46, 25, 78)); cornerRadius = dp(12).toFloat() } else GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dp(12).toFloat() }
        }
    }

    private fun renderDashboard() {
        val receipts = database.listReceipts()
        val collaborators = database.listCollaborators()
        val total = receipts.fold(0L) { sum, item -> Math.addExact(sum, item.amountCents) }
        content.addView(label("Olá, Supervisor 👋", 24, ink, true))
        content.addView(label("Aqui está o resumo das suas despesas.", 14, muted, false).apply {
            setPadding(0, dp(5), 0, dp(18))
        })
        val summary = card()
        summary.addView(label("Total registrado", 14, muted, false))
        summary.addView(label(money(total), 28, blue, true).apply { setPadding(0, dp(5), 0, dp(3)) })
        summary.addView(label("${receipts.size} despesas  ·  ${collaborators.size} colaboradores", 13, muted, false))
        content.addView(summary, spaced())

        val pendingCount = receipts.count { database.paymentStatus(it.externalId).status.equals("Pendente", ignoreCase = true) }
        val partialCount = receipts.count { database.paymentStatus(it.externalId).status.equals("Parcial", ignoreCase = true) }
        val paidCount = receipts.count { database.paymentStatus(it.externalId).status.equals("Pago", ignoreCase = true) }
        val paymentTotals = receipts.map { database.paymentStatus(it.externalId) }
        val paidAmount = paymentTotals.fold(0L) { sum, status -> Math.addExact(sum, status.paidCents) }
        val openAmount = paymentTotals.fold(0L) { sum, status -> Math.addExact(sum, status.balanceCents) }
        val statusSummary = card()
        statusSummary.addView(label("Situação dos pagamentos", 16, ink, true))
        statusSummary.addView(label("Pendentes: $pendingCount", 14, red, true).apply { setPadding(0, dp(8), 0, 0) })
        statusSummary.addView(label("Parciais: $partialCount", 14, blue, true).apply { setPadding(0, dp(5), 0, 0) })
        statusSummary.addView(label("Pagas: $paidCount", 14, green, true).apply { setPadding(0, dp(5), 0, 0) })
        statusSummary.addView(label("Valor já pago: ${money(paidAmount)}", 13, green, true).apply { setPadding(0, dp(9), 0, 0) })
        statusSummary.addView(label("Saldo em aberto: ${money(openAmount)}", 13, red, true).apply { setPadding(0, dp(4), 0, 0) })
        content.addView(statusSummary, spaced())

        content.addView(label("Ações rápidas", 18, ink, true).apply { setPadding(0, dp(12), 0, dp(8)) })
        val add = actionButton("+ Cadastrar despesa")
        add.setOnClickListener { showScreen("new") }
        content.addView(add, spaced())
        val people = actionButton("Cadastrar colaborador")
        people.setOnClickListener { showScreen("people"); openCollaboratorDialog() }
        content.addView(people, spaced())
        val export = actionButton("Preparar arquivo para o Gestor")
        export.setOnClickListener { showScreen("files") }
        content.addView(export, spaced())

        content.addView(label("Atividade recente", 18, ink, true).apply { setPadding(0, dp(12), 0, dp(8)) })
        if (receipts.isEmpty()) {
            val empty = card()
            empty.addView(label("Ainda não há despesas. Comece cadastrando sua primeira despesa.", 14, muted, false))
            content.addView(empty, spaced())
        } else {
            receipts.take(3).forEach { receipt ->
                val item = card()
                item.addView(label(receipt.collaborator, 15, ink, true))
                item.addView(label(money(receipt.amountCents), 19, blue, true).apply { setPadding(0, dp(4), 0, 0) })
                item.addView(label("${receipt.description} · ${receipt.expenseDate}", 13, muted, false).apply { setPadding(0, dp(3), 0, 0) })
                content.addView(item, spaced())
            }
            val all = actionButton("Ver todas as despesas")
            all.setOnClickListener { showScreen("receipts") }
            content.addView(all, spaced())
        }
    }

    private fun renderExpenseForm() {
        content.addView(label("Nova despesa", 23, ink, true))
        content.addView(label("Preencha os dados e anexe o comprovante. Os dados ficam salvos neste celular.", 14, muted, false).apply {
            setPadding(0, dp(5), 0, dp(16))
        })
        val form = card()
        collaboratorSpinner = Spinner(this)
        refreshCollaboratorSpinner()
        amountInput = field("Ex.: 125,50").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        descriptionInput = field("Ex.: transporte, alimentação")
        dateInput = field("AAAA-MM-DD").apply { setText(java.time.LocalDate.now().toString()) }
        form.addView(fieldLabel("Colaborador"))
        form.addView(collaboratorSpinner, fieldParams())
        form.addView(fieldLabel("Valor (R$)"))
        form.addView(amountInput, fieldParams())
        form.addView(fieldLabel("Descrição"))
        form.addView(descriptionInput, fieldParams())
        form.addView(fieldLabel("Data da despesa"))
        form.addView(dateInput, fieldParams())
        photoButton = actionButton("Tirar foto ou anexar comprovante")
        photoButton.setOnClickListener { showPhotoOptions() }
        form.addView(photoButton, fieldParams())
        statusLabel = label("Obrigatório para salvar.", 13, muted, false).apply { setPadding(0, dp(3), 0, dp(10)) }
        form.addView(statusLabel)
        val save = actionButton("Salvar despesa")
        save.setOnClickListener { saveReceipt() }
        form.addView(save, matchWrap())
        content.addView(form, spaced())
        if (collaboratorOptions.isEmpty()) {
            val addPerson = actionButton("Cadastrar colaborador primeiro")
            addPerson.setOnClickListener { showScreen("people"); openCollaboratorDialog() }
            content.addView(addPerson, spaced())
        }
        val cancel = actionButton("Cancelar")
        cancel.setOnClickListener { showScreen("receipts") }
        content.addView(cancel, spaced())
    }

    private fun renderCollaborators() {
        val collaborators = database.listCollaborators()
        content.addView(label("Pessoas", 23, ink, true))
        content.addView(label("Colaboradores e suas chaves Pix cadastradas.", 14, muted, false).apply {
            setPadding(0, dp(5), 0, dp(15))
        })
        val add = actionButton("+ Cadastrar colaborador")
        add.setOnClickListener { openCollaboratorDialog() }
        content.addView(add, spaced())
        if (collaborators.isEmpty()) {
            val empty = card()
            empty.addView(label("Nenhum colaborador cadastrado.", 15, ink, true))
            empty.addView(label("Cadastre o colaborador e confirme os dados da chave Pix antes de registrar despesas.", 13, muted, false).apply { setPadding(0, dp(5), 0, 0) })
            content.addView(empty, spaced())
            return
        }
        collaborators.forEach { person ->
            val item = card()
            item.addView(label(person.name, 17, ink, true))
            item.addView(label("Função: ${person.role}", 13, blue, true).apply { setPadding(0, dp(4), 0, 0) })
            if (person.team.isNotBlank()) item.addView(label("Equipe: ${person.team}", 13, muted, false).apply { setPadding(0, dp(3), 0, 0) })
            item.addView(label("${person.pixType}: ${person.pixKey}", 14, blue, true).apply { setPadding(0, dp(6), 0, 0) })
            item.addView(label("Titular: ${person.pixHolder}", 13, muted, false).apply { setPadding(0, dp(3), 0, dp(10)) })
            val edit = actionButton("Editar colaborador")
            edit.setOnClickListener { openCollaboratorDialog(person) }
            item.addView(edit, matchWrap())
            val delete = actionButton("Excluir colaborador").apply {
                background = GradientDrawable().apply {
                    setColor(red)
                    cornerRadius = dp(12).toFloat()
                }
            }
            delete.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Excluir colaborador?")
                    .setMessage("Deseja excluir ${person.name}? Para proteger o histórico, só é possível excluir quem ainda não possui despesas registradas.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Excluir") { _, _ ->
                        try {
                            database.deleteCollaborator(person.id)
                            toast("Colaborador excluído.")
                            showScreen("people")
                        } catch (error: Exception) {
                            toast(error.message ?: "Não foi possível excluir o colaborador.")
                        }
                    }
                    .show()
            }
            item.addView(delete, matchWrap())
            content.addView(item, spaced())
        }
    }

    private fun renderFiles() {
        content.addView(label("Arquivos e retornos", 23, ink, true))
        content.addView(label("Troque planilhas com o Gestor Windows por aqui.", 14, muted, false).apply {
            setPadding(0, dp(5), 0, dp(16))
        })
        val exportCard = card()
        exportCard.addView(label("1. Enviar despesas ao Gestor", 17, ink, true))
        exportCard.addView(label("Gera uma planilha com despesas, colaboradores e comprovantes para o Gestor importar no computador.", 13, muted, false).apply { setPadding(0, dp(5), 0, dp(12)) })
        val export = actionButton("Exportar planilha (.xlsx)")
        export.setOnClickListener { exportReceipts() }
        exportCard.addView(export, matchWrap())
        content.addView(exportCard, spaced())

        val importCard = card()
        importCard.addView(label("2. Receber retorno do Gestor", 17, ink, true))
        importCard.addView(label("Importa o arquivo de retorno, valida os comprovantes e atualiza os saldos após sua confirmação.", 13, muted, false).apply { setPadding(0, dp(5), 0, dp(12)) })
        val import = actionButton("Importar retorno (.xlsx)")
        import.setOnClickListener { choosePaymentReturn() }
        importCard.addView(import, matchWrap())
        content.addView(importCard, spaced())

        val note = card()
        note.addView(label("Dica", 14, blue, true))
        note.addView(label("Envie ao Gestor somente a planilha exportada pelo ReciPay. Depois, importe o arquivo de retorno que ele gerar.", 13, muted, false).apply { setPadding(0, dp(4), 0, 0) })
        content.addView(note, spaced())
    }

    private fun openCollaboratorDialog(existing: LocalCollaborator? = null) {
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
        val roleLabel = label("Função / cargo", 13, ink, true).apply { setPadding(0, dp(8), 0, dp(4)) }
        form.addView(roleLabel)
        val role = addInput("Ex.: Eletricista, Motorista, Encarregado")
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
        if (existing != null) {
            name.setText(existing.name)
            team.setText(existing.team)
            role.setText(existing.role)
            val typeIndex = listOf("CPF", "CNPJ", "EMAIL", "TELEFONE", "ALEATORIA").indexOf(existing.pixType)
            if (typeIndex >= 0) type.setSelection(typeIndex)
            key.setText(existing.pixKey)
            holder.setText(existing.pixHolder)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Novo colaborador" else "Editar colaborador")
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
                    val pixType = listOf("CPF", "CNPJ", "EMAIL", "TELEFONE", "ALEATORIA")[type.selectedItemPosition]
                    if (existing == null) {
                        database.addCollaborator(
                            collaboratorName, team.text.toString(), role.text.toString().trim(),
                            pixType, pixKey, pixHolder
                        )
                        toast("Colaborador cadastrado neste celular.")
                    } else {
                        database.updateCollaborator(
                            existing.id, collaboratorName, team.text.toString(), role.text.toString().trim(),
                            pixType, pixKey, pixHolder
                        )
                        toast("Dados do colaborador atualizados.")
                    }
                    showScreen("people")
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
        // Aceita formatos brasileiros (250,99 / 1.250,99) e ponto decimal (250.99).
        val normalizedAmount = when {
            amountText.contains(",") -> amountText.replace(".", "").replace(",", ".")
            amountText.count { it == '.' } == 1 && amountText.substringAfterLast('.').length in 1..2 -> amountText
            else -> amountText.replace(".", "")
        }
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
            photoButton.text = "Tirar foto ou anexar comprovante"
            statusLabel.text = "Comprovante obrigatório."
            statusLabel.setTextColor(muted)
            toast("Despesa salva neste celular.")
            showScreen("receipts")
        } catch (error: Exception) {
            toast(error.message ?: "Não foi possível salvar a despesa.")
        }
    }

    private fun openReceiptEditDialog(receipt: LocalReceipt) {
        val collaborators = database.listCollaborators()
        if (collaborators.isEmpty()) {
            toast("Cadastre um colaborador antes de editar a despesa.")
            return
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val personSpinner = Spinner(this)
        personSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, collaborators.map { it.name }
        )
        val currentIndex = collaborators.indexOfFirst { it.id == receipt.collaboratorId }
        if (currentIndex >= 0) personSpinner.setSelection(currentIndex)
        val amount = field("Ex.: 250,99").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(NumberFormat.getNumberInstance(Locale("pt", "BR")).format(receipt.amountCents / 100.0))
        }
        val description = field("Descrição").apply { setText(receipt.description) }
        val date = field("AAAA-MM-DD").apply { setText(receipt.expenseDate) }
        form.addView(fieldLabel("Colaborador"))
        form.addView(personSpinner, fieldParams())
        form.addView(fieldLabel("Valor (R$)"))
        form.addView(amount, fieldParams())
        form.addView(fieldLabel("Descrição"))
        form.addView(description, fieldParams())
        form.addView(fieldLabel("Data da despesa"))
        form.addView(date, fieldParams())
        form.addView(label("O comprovante atual será mantido. Despesas com pagamentos vinculados não podem ser editadas.", 12, muted, false).apply {
            setPadding(0, dp(8), 0, 0)
        })
        AlertDialog.Builder(this)
            .setTitle("Editar despesa")
            .setView(form)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar alterações") { _, _ ->
                val selected = collaborators.getOrNull(personSpinner.selectedItemPosition)
                val rawAmount = amount.text.toString().trim()
                val normalized = when {
                    rawAmount.contains(",") -> rawAmount.replace(".", "").replace(",", ".")
                    rawAmount.count { it == '.' } == 1 && rawAmount.substringAfterLast('.').length in 1..2 -> rawAmount
                    else -> rawAmount.replace(".", "")
                }
                val cents = try {
                    java.math.BigDecimal(normalized).movePointRight(2)
                        .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
                        .also { require(it > 0L) }
                } catch (_: Exception) {
                    toast("Informe um valor válido, maior que zero.")
                    return@setPositiveButton
                }
                val expenseDate = date.text.toString().trim()
                try {
                    java.time.LocalDate.parse(expenseDate)
                } catch (_: Exception) {
                    toast("Use a data no formato AAAA-MM-DD.")
                    return@setPositiveButton
                }
                if (selected == null || description.text.toString().trim().isBlank()) {
                    toast("Selecione um colaborador e preencha a descrição.")
                    return@setPositiveButton
                }
                try {
                    database.updateReceipt(
                        receipt.id, selected.id, selected.name, cents,
                        description.text.toString().trim(), expenseDate
                    )
                    toast("Despesa atualizada. O comprovante foi mantido.")
                    showScreen("receipts")
                } catch (error: Exception) {
                    toast(error.message ?: "Não foi possível atualizar a despesa.")
                }
            }
            .show()
    }

    private fun showPhotoOptions() {
        AlertDialog.Builder(this)
            .setTitle("Adicionar comprovante")
            .setItems(arrayOf("📷 Tirar foto agora", "🖼️ Escolher da galeria")) { _, which ->
                if (which == 0) takeReceiptPhoto() else openPhotoPicker()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun takeReceiptPhoto() {
        try {
            val photoFile = File(cacheDir, "recibo_${System.currentTimeMillis()}.jpg")
            pendingCameraUri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", photoFile
            )
            val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(android.provider.MediaStore.EXTRA_OUTPUT, pendingCameraUri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (intent.resolveActivity(packageManager) == null) {
                pendingCameraUri = null
                toast("Nenhum aplicativo de câmera foi encontrado.")
                return
            }
            startActivityForResult(intent, REQUEST_CAMERA)
        } catch (error: Exception) {
            pendingCameraUri = null
            toast(error.message ?: "Não foi possível abrir a câmera.")
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
        if (resultCode != Activity.RESULT_OK) {
            if (requestCode == REQUEST_CAMERA) pendingCameraUri = null
            return
        }
        if (requestCode == REQUEST_CAMERA) {
            selectedPhotoUri = pendingCameraUri
            pendingCameraUri = null
            if (selectedPhotoUri != null) {
                photoButton.text = "Foto do recibo selecionada ✓"
                statusLabel.text = "Foto capturada. Ela será salva junto à despesa."
                statusLabel.setTextColor(green)
            } else {
                toast("Não foi possível obter a foto. Tente novamente.")
            }
            return
        }
        if (data?.data == null) return
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
                            showScreen("receipts")
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

    private fun themedSpinnerAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                return super.getView(position, convertView, parent).apply {
                    (this as? TextView)?.setTextColor(ink)
                }
            }

            override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                return super.getDropDownView(position, convertView, parent).apply {
                    (this as? TextView)?.apply {
                        setTextColor(ink)
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                    }
                    setBackgroundColor(Color.rgb(23, 14, 43))
                }
            }
        }.apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun refreshCollaboratorSpinner() {
        collaboratorOptions = database.listCollaborators()
        val labels = if (collaboratorOptions.isEmpty()) listOf("Cadastre primeiro um colaborador")
            else collaboratorOptions.map { it.name }
        collaboratorSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        collaboratorSpinner.isEnabled = collaboratorOptions.isNotEmpty()
    }

    private fun renderReceipts() {
        val receipts = database.listReceipts()
        content.addView(label("Despesas", 24, ink, true))
        content.addView(label("Consulte, acompanhe e gerencie os registros antes de enviar ao Gestor.", 14, muted, false).apply {
            setPadding(0, dp(5), 0, dp(15))
        })
        val add = actionButton("+ Nova despesa")
        add.setOnClickListener { showScreen("new") }
        content.addView(add, spaced())

        val total = receipts.fold(0L) { sum, receipt -> Math.addExact(sum, receipt.amountCents) }
        val statuses = receipts.map { database.paymentStatus(it.externalId) }
        val paid = statuses.fold(0L) { sum, status -> Math.addExact(sum, status.paidCents) }
        val pending = statuses.fold(0L) { sum, status -> Math.addExact(sum, status.balanceCents) }
        val summary = card()
        summary.addView(label("Total registrado", 13, muted, false))
        summary.addView(label(money(total), 25, blue, true).apply { setPadding(0, dp(3), 0, 0) })
        summary.addView(label("${receipts.size} despesa(s)  ·  ${receipts.count { database.paymentStatus(it.externalId).status == "Pendente" }} pendente(s)", 12, muted, false).apply { setPadding(0, dp(3), 0, 0) })
        summary.addView(label("Pago: ${money(paid)}     Em aberto: ${money(pending)}", 13, blue, true).apply { setPadding(0, dp(7), 0, 0) })
        content.addView(summary, spaced())

        val search = field("Buscar por colaborador, descrição ou data")
        search.setCompoundDrawablePadding(dp(8))
        content.addView(search, spaced())

        content.addView(fieldLabel("Filtrar por situação"))
        val statusFilter = Spinner(this)
        val statusOptions = listOf("Todos os status", "Pendente", "Parcial", "Pago")
        statusFilter.adapter = themedSpinnerAdapter(statusOptions)
        statusFilter.background = GradientDrawable().apply {
            setColor(Color.rgb(17, 10, 34))
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), Color.rgb(73, 54, 101))
        }
        statusFilter.setPadding(dp(12), dp(8), dp(12), dp(8))
        statusFilter.setPopupBackgroundDrawable(GradientDrawable().apply {
            setColor(Color.rgb(23, 14, 43))
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), Color.rgb(73, 54, 101))
        })
        content.addView(statusFilter, spaced())

        val listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listContainer)

        fun renderItems(rawQuery: String, rawStatus: String) {
            listContainer.removeAllViews()
            val query = rawQuery.trim().lowercase(Locale.ROOT)
            val selectedStatus = rawStatus.lowercase(Locale.ROOT)
            val filtered = receipts.filter { receipt ->
                val matchesQuery = query.isBlank() ||
                    receipt.collaborator.lowercase(Locale.ROOT).contains(query) ||
                    receipt.description.lowercase(Locale.ROOT).contains(query) ||
                    receipt.expenseDate.lowercase(Locale.ROOT).contains(query)
                val paymentStatus = database.paymentStatus(receipt.externalId).status.lowercase(Locale.ROOT)
                val matchesStatus = selectedStatus == "todos os status" || paymentStatus == selectedStatus
                matchesQuery && matchesStatus
            }
            if (filtered.isEmpty()) {
                val empty = card()
                empty.addView(label(if (receipts.isEmpty()) "Nenhuma despesa cadastrada" else "Nenhuma despesa encontrada", 16, ink, true))
                empty.addView(label(if (receipts.isEmpty()) "Cadastre sua primeira despesa para começar." else "Tente outra busca ou selecione “Todos os status”.", 13, muted, false).apply { setPadding(0, dp(5), 0, 0) })
                listContainer.addView(empty, spaced())
                return
            }
            filtered.forEach { receipt ->
                val item = card()
                val payment = database.paymentStatus(receipt.externalId)
                item.addView(label(receipt.collaborator, 16, ink, true))
                item.addView(label(money(receipt.amountCents), 22, blue, true).apply { setPadding(0, dp(5), 0, 0) })
                item.addView(label(receipt.description, 14, ink, false).apply { setPadding(0, dp(4), 0, 0) })
                item.addView(label("Data: ${receipt.expenseDate}", 12, muted, false).apply { setPadding(0, dp(5), 0, 0) })
                val statusColor = when (payment.status.lowercase(Locale.ROOT)) {
                    "pago" -> green
                    "parcial" -> Color.rgb(180, 83, 9)
                    else -> blue
                }
                item.addView(label("Status: ${payment.status}", 13, statusColor, true).apply { setPadding(0, dp(6), 0, 0) })
                item.addView(label("Pago: ${money(payment.paidCents)}  ·  Saldo: ${money(payment.balanceCents)}", 12, muted, false).apply { setPadding(0, dp(3), 0, dp(12)) })
                if (payment.paidCents == 0L) {
                    val edit = actionButton("Editar despesa")
                    edit.background = GradientDrawable().apply {
                        setColor(Color.rgb(46, 25, 78))
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), Color.rgb(191, 219, 254))
                    }
                    edit.setTextColor(blue)
                    edit.setOnClickListener { openReceiptEditDialog(receipt) }
                    item.addView(edit, matchWrap())
                } else {
                    item.addView(label("Edição bloqueada após pagamentos para proteger o histórico.", 12, muted, false).apply {
                        setPadding(0, 0, 0, dp(8))
                    })
                }
                val delete = actionButton("Excluir despesa")
                delete.background = GradientDrawable().apply {
                    setColor(Color.rgb(254, 242, 242))
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), Color.rgb(254, 202, 202))
                }
                delete.setTextColor(red)
                delete.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Excluir esta despesa?")
                        .setMessage("“${receipt.description}” — ${money(receipt.amountCents)}\n\nEssa ação remove o registro e o comprovante deste celular. Se já enviou a planilha ao Gestor, apagar aqui não remove a cópia do computador. Despesas com pagamentos vinculados não podem ser excluídas.")
                        .setNegativeButton("Manter", null)
                        .setPositiveButton("Excluir") { _, _ ->
                            try {
                                database.deleteReceipt(receipt.id)
                                toast("Despesa excluída.")
                                showScreen("receipts")
                            } catch (error: Exception) {
                                toast(error.message ?: "Não foi possível excluir esta despesa.")
                            }
                        }
                        .show()
                }
                item.addView(delete, matchWrap())
                listContainer.addView(item, spaced())
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                renderItems(s?.toString().orEmpty(), statusFilter.selectedItem?.toString().orEmpty())
            }
        })
        statusFilter.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                renderItems(search.text.toString(), statusOptions[position])
            }
        }
        renderItems("", statusOptions.first())
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply {
            setColor(Color.rgb(23, 14, 43))
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.rgb(57, 39, 82))
        }
        elevation = dp(2).toFloat()
    }

    private fun field(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        textSize = 15f
        setSingleLine(true)
        setTextColor(ink)
        setHintTextColor(muted)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply {
            setColor(Color.rgb(17, 10, 34))
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), Color.rgb(73, 54, 101))
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
        private const val REQUEST_CAMERA = 1046
        private const val REQUEST_EXPORT = 1044
        private const val REQUEST_RETURN = 1045
        private const val SUPERVISOR_ID = "00000000-0000-4000-8000-000000000001"
    }
}
