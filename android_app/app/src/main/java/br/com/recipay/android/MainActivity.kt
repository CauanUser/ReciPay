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
    private val selectedPhotoUris = mutableListOf<Uri>()
    private var expectedPhotoCount = 0
    private var cameraCaptureMode = false
    private var pendingCameraUri: Uri? = null
    private var pendingAttachmentReceiptId: Long? = null
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
        screenRoot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(9, 10, 18)) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12)); setBackgroundColor(Color.rgb(15, 16, 28))
        }
        val logo = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.recipay_launcher_foreground); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "Logo ReciPay"
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(10) })
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(label("ReciPay", 20, ink, true))
        brand.addView(label("Controle de despesas", 12, muted, false))
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        screenRoot.addView(header)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(20), dp(18), dp(24)) }
        scroll.addView(content); screenRoot.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(dp(8), dp(7), dp(8), dp(7)); setBackgroundColor(Color.rgb(15, 16, 28))
        }
        listOf("home" to "Início", "receipts" to "Despesas", "people" to "Pessoas", "files" to "Mais").forEach { (key, title) ->
            val button = TextView(this).apply {
                text = title; textSize = 12f; gravity = Gravity.CENTER
                setPadding(dp(2), dp(12), dp(2), dp(12)); setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (key == currentScreen) blue else muted); background = navBackground(key == currentScreen)
                setOnClickListener { showScreen(key) }
            }
            nav.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
        }
        screenRoot.addView(nav); setContentView(screenRoot); showScreen("home")
    }

    private fun showScreen(screen: String) {
        currentScreen = screen; content.removeAllViews()
        when (screen) {
            "home" -> renderDashboard()
            "new" -> renderExpenseForm()
            "receipts" -> renderReceipts()
            "people" -> renderCollaborators()
            "files" -> renderFiles()
        }
        val nav = screenRoot.getChildAt(2) as? LinearLayout ?: return
        val keys = listOf("home", "receipts", "people", "files")
        for (i in 0 until nav.childCount) {
            val item = nav.getChildAt(i) as? TextView ?: continue
            val selected = keys.getOrNull(i) == screen || (screen == "new" && keys.getOrNull(i) == "receipts")
            item.setTextColor(if (selected) blue else muted); item.background = navBackground(selected)
        }
    }

    private fun navBackground(selected: Boolean) = GradientDrawable().apply {
        setColor(if (selected) Color.rgb(40, 31, 67) else Color.TRANSPARENT); cornerRadius = dp(12).toFloat()
    }

    private fun renderDashboard() {
        val receipts = database.listReceipts()
        val collaborators = database.listCollaborators()
        val total = receipts.fold(0L) { sum, item -> Math.addExact(sum, item.amountCents) }
        val statuses = receipts.map { database.paymentStatus(it.externalId) }
        val openAmount = statuses.fold(0L) { sum, status -> Math.addExact(sum, status.balanceCents) }
        val pendingCount = statuses.count { it.status.equals("Pendente", true) }
        val missingProof = receipts.count { it.attachmentPath.isBlank() || !File(it.attachmentPath).exists() }
        content.addView(label("Visão geral", 25, ink, true))
        content.addView(label("O essencial, sem complicação.", 14, muted, false).apply { setPadding(0, dp(4), 0, dp(18)) })
        val balance = card()
        balance.addView(label("TOTAL DE DESPESAS", 11, muted, true))
        balance.addView(label(money(total), 30, ink, true).apply { setPadding(0, dp(7), 0, dp(3)) })
        balance.addView(label(receipts.size.toString() + " registros • " + collaborators.size + " pessoas", 13, muted, false))
        balance.addView(android.view.View(this).apply { setBackgroundColor(Color.rgb(55, 49, 72)) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(15); bottomMargin = dp(12) })
        balance.addView(label("Saldo em aberto", 12, muted, false))
        balance.addView(label(money(openAmount), 20, if (openAmount > 0) Color.rgb(251, 191, 36) else green, true).apply { setPadding(0, dp(3), 0, 0) })
        content.addView(balance, spaced())
        content.addView(label("Acesso rápido", 17, ink, true).apply { setPadding(0, dp(12), 0, dp(10)) })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(quickAction("＋", "Nova despesa") { showScreen("new") }, LinearLayout.LayoutParams(0, dp(92), 1f).apply { rightMargin = dp(5) })
        actions.addView(quickAction("↗", "Importar Excel") { showScreen("receipts"); chooseExpenseSpreadsheet() }, LinearLayout.LayoutParams(0, dp(92), 1f).apply { leftMargin = dp(5); rightMargin = dp(5) })
        actions.addView(quickAction("＋", "Pessoa") { showScreen("people"); openCollaboratorDialog() }, LinearLayout.LayoutParams(0, dp(92), 1f).apply { leftMargin = dp(5) })
        content.addView(actions)
        content.addView(label("Atenção", 17, ink, true).apply { setPadding(0, dp(22), 0, dp(10)) })
        val attention = card()
        if (missingProof > 0 || pendingCount > 0) {
            if (missingProof > 0) attention.addView(label(missingProof.toString() + " despesa(s) sem comprovante", 14, Color.rgb(251, 191, 36), true))
            if (pendingCount > 0) attention.addView(label(pendingCount.toString() + " pagamento(s) pendente(s)", 14, muted, false).apply { setPadding(0, dp(5), 0, 0) })
            attention.addView(actionButton("Revisar despesas").apply { setOnClickListener { showScreen("receipts") } },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        } else {
            attention.addView(label("Tudo em dia", 15, green, true))
            attention.addView(label("Nenhuma pendência importante encontrada.", 13, muted, false).apply { setPadding(0, dp(4), 0, 0) })
        }
        content.addView(attention, spaced())
        content.addView(label("Recentes", 17, ink, true).apply { setPadding(0, dp(12), 0, dp(10)) })
        if (receipts.isEmpty()) {
            val empty = card()
            empty.addView(label("Suas despesas aparecerão aqui.", 15, ink, true))
            empty.addView(label("Cadastre uma despesa ou importe sua planilha para começar.", 13, muted, false).apply { setPadding(0, dp(5), 0, 0) })
            content.addView(empty, spaced())
        } else {
            receipts.take(3).forEach { receipt -> content.addView(expenseSummaryRow(receipt) { showReceiptActions(receipt) }, spaced()) }
            content.addView(actionButton("Ver todas").apply { setOnClickListener { showScreen("receipts") } }, spaced())
        }
    }

    private fun quickAction(symbol: String, title: String, action: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(5), dp(10), dp(5), dp(10))
            background = GradientDrawable().apply { setColor(Color.rgb(22, 23, 38)); cornerRadius = dp(16).toFloat(); setStroke(dp(1), Color.rgb(49, 47, 69)) }
            addView(label(symbol, 23, blue, true).apply { gravity = Gravity.CENTER })
            addView(label(title, 11, ink, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(7), 0, 0) })
            setOnClickListener { action() }
        }

    private fun expenseSummaryRow(receipt: LocalReceipt, onClick: () -> Unit): LinearLayout {
        val payment = database.paymentStatus(receipt.externalId)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = GradientDrawable().apply { setColor(Color.rgb(22, 23, 38)); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Color.rgb(49, 47, 69)) }
            val details = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            details.addView(label(receipt.collaborator, 14, ink, true))
            details.addView(label(receipt.description, 12, muted, false).apply { setPadding(0, dp(3), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            details.addView(label(receipt.expenseDate + " • " + payment.status, 11, if (payment.status.equals("Pago", true)) green else muted, false).apply { setPadding(0, dp(4), 0, 0) })
            addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            val trailing = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(label(money(receipt.amountCents), 14, ink, true).apply {
                    gravity = Gravity.END
                    setPadding(dp(8), 0, 0, dp(4))
                })
                addView(label("Opções  ›", 11, blue, true).apply {
                    gravity = Gravity.END
                    setPadding(dp(8), dp(3), 0, 0)
                })
                setOnClickListener { onClick() }
            }
            addView(trailing, LinearLayout.LayoutParams(-2, -2))
            setOnClickListener { onClick() }
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
        photoButton = actionButton("Definir quantidade de fotos")
        photoButton.setOnClickListener { askPhotoCount() }
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
        content.addView(label("Pessoas", 25, ink, true))
        content.addView(label("Seus colaboradores e dados Pix.", 13, muted, false).apply { setPadding(0, dp(4), 0, dp(16)) })
        content.addView(actionButton("+ Adicionar pessoa").apply { setOnClickListener { openCollaboratorDialog() } }, spaced())
        if (collaborators.isEmpty()) {
            val empty = card()
            empty.addView(label("Nenhuma pessoa cadastrada", 16, ink, true))
            empty.addView(label("Cadastre uma pessoa para começar a registrar despesas.", 13, muted, false).apply { setPadding(0, dp(5), 0, 0) })
            content.addView(empty, spaced()); return
        }
        val search = field("Buscar colaborador")
        content.addView(search, spaced())
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
        fun render(queryRaw: String) {
            list.removeAllViews()
            val query = queryRaw.trim().lowercase(Locale.ROOT)
            val filtered = collaborators.filter { it.name.lowercase(Locale.ROOT).contains(query) || it.team.lowercase(Locale.ROOT).contains(query) }
            if (filtered.isEmpty()) {
                list.addView(card().apply { addView(label("Nenhuma pessoa encontrada.", 14, muted, false)) }, spaced()); return
            }
            filtered.forEach { person ->
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    background = GradientDrawable().apply { setColor(Color.rgb(22, 23, 38)); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Color.rgb(49, 47, 69)) }
                }
                val initial = label(person.name.trim().take(1).uppercase(Locale.ROOT), 17, blue, true).apply {
                    gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(40, 31, 67)) }
                }
                item.addView(initial, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(12) })
                val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                details.addView(label(person.name, 15, ink, true))
                val secondary = listOf(person.role, person.team).filter { it.isNotBlank() }.joinToString(" • ").ifBlank { person.pixType }
                details.addView(label(secondary, 12, muted, false).apply { setPadding(0, dp(3), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                item.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
                item.addView(label("›", 26, muted, false).apply { setPadding(dp(8), 0, 0, 0) })
                item.setOnClickListener { showCollaboratorActions(person) }
                list.addView(item, spaced())
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { render(s?.toString().orEmpty()) }
        })
        render("")
    }

    private fun showCollaboratorActions(person: LocalCollaborator) {
        val options = arrayOf("Editar dados", "Excluir colaborador")
        AlertDialog.Builder(this)
            .setTitle(person.name)
            .setMessage("Função: " + person.role + "\nEquipe: " + person.team + "\nChave Pix: " + person.pixKey + "\nTitular: " + person.pixHolder)
            .setItems(options) { _, which ->
                if (which == 0) openCollaboratorDialog(person) else {
                    AlertDialog.Builder(this)
                        .setTitle("Excluir colaborador?")
                        .setMessage("Deseja excluir " + person.name + "? Para proteger o histórico, só é possível excluir quem ainda não possui despesas registradas.")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Excluir") { _, _ ->
                            try { database.deleteCollaborator(person.id); toast("Colaborador excluído."); showScreen("people") }
                            catch (error: Exception) { toast(error.message ?: "Não foi possível excluir o colaborador.") }
                        }.show()
                }
            }.setNegativeButton("Fechar", null).show()
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
        val photoUris = selectedPhotoUris.toList()

        if (selectedCollaborator == null || amountText.isBlank() || description.isBlank() || date.isBlank()) {
            toast(if (selectedCollaborator == null) "Cadastre um colaborador antes de registrar a despesa." else "Preencha todos os campos.")
            return
        }
        if (expectedPhotoCount < 1) {
            toast("Informe antes quantas fotos terá esta despesa.")
            return
        }
        if (photoUris.size != expectedPhotoCount) {
            toast("Você definiu " + expectedPhotoCount + " foto(s), mas selecionou " + photoUris.size + ". Selecione exatamente essa quantidade.")
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
            val attachments = photoUris.mapIndexed { index, uri ->
                val attachment = File(attachmentDir, "comprovante_" + System.currentTimeMillis() + "_" + index + ".img")
                contentResolver.openInputStream(uri).use { input ->
                    if (input == null) error("Não foi possível abrir a foto " + (index + 1) + ".")
                    attachment.outputStream().use { output -> input.copyTo(output) }
                }
                attachment
            }
            val receiptId = database.addReceipt(selectedCollaborator.id, selectedCollaborator.name, amountCents, description, date, attachments.first().absolutePath)
            database.addReceiptAttachments(receiptId, attachments.map { it.absolutePath })
            amountInput.text.clear()
            descriptionInput.text.clear()
            dateInput.setText(java.time.LocalDate.now().toString())
            selectedPhotoUris.clear()
            expectedPhotoCount = 0
            photoButton.text = "Definir quantidade de fotos"
            statusLabel.text = "Informe a quantidade de fotos antes de salvar."
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

    private fun askPhotoCount() {
        val countInput = EditText(this).apply { hint = "Quantidade de fotos (1 a 50)"; inputType = InputType.TYPE_CLASS_NUMBER }
        AlertDialog.Builder(this).setTitle("Quantas fotos terá esta despesa?")
            .setMessage("Informe a quantidade antes de anexar. Só será possível salvar quando todas estiverem selecionadas.")
            .setView(countInput).setNegativeButton("Cancelar", null)
            .setPositiveButton("Continuar") { _, _ ->
                val count = countInput.text.toString().toIntOrNull()
                if (count == null || count !in 1..50) { toast("Informe uma quantidade entre 1 e 50."); return@setPositiveButton }
                expectedPhotoCount = count
                selectedPhotoUris.clear()
                showPhotoOptions()
            }.show()
    }

    private fun updatePhotoSelectionStatus() {
        photoButton.text = "Fotos: " + selectedPhotoUris.size + "/" + expectedPhotoCount + " • Adicionar/alterar"
        statusLabel.text = if (selectedPhotoUris.size == expectedPhotoCount) "Quantidade completa. Pode salvar." else "Faltam " + (expectedPhotoCount - selectedPhotoUris.size) + " foto(s)."
        statusLabel.setTextColor(if (selectedPhotoUris.size == expectedPhotoCount) green else muted)
    }

    private fun showPhotoOptions() {
        AlertDialog.Builder(this).setTitle("Adicionar fotos (" + selectedPhotoUris.size + "/" + expectedPhotoCount + ")")
            .setItems(arrayOf("📷 Fotografar uma por vez", "🖼️ Selecionar várias da galeria")) { _, which ->
                if (which == 0) { cameraCaptureMode = true; takeReceiptPhoto() }
                else { cameraCaptureMode = false; openPhotoPicker() }
            }.setNegativeButton("Cancelar", null).show()
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
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, REQUEST_PHOTO)
    }

    private fun chooseExpenseSpreadsheet() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        }
        startActivityForResult(intent, REQUEST_IMPORT_EXPENSES)
    }

    private fun chooseAttachmentForReceipt(receiptId: Long) {
        pendingAttachmentReceiptId = receiptId
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, REQUEST_ATTACH_RECEIPT)
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
            val captured = pendingCameraUri
            pendingCameraUri = null
            if (captured != null) selectedPhotoUris += captured
            if (cameraCaptureMode && selectedPhotoUris.size < expectedPhotoCount) {
                AlertDialog.Builder(this).setTitle("Foto " + selectedPhotoUris.size + " de " + expectedPhotoCount)
                    .setMessage("Deseja fotografar a próxima?")
                    .setNegativeButton("Parar") { _, _ -> updatePhotoSelectionStatus() }
                    .setPositiveButton("Próxima foto") { _, _ -> takeReceiptPhoto() }.show()
            } else updatePhotoSelectionStatus()
            return
        }
        if (data?.data == null) return
        if (requestCode == REQUEST_ATTACH_RECEIPT) {
            val receiptId = pendingAttachmentReceiptId
            pendingAttachmentReceiptId = null
            if (receiptId == null) {
                toast("Não foi possível identificar a despesa.")
                return
            }
            try {
                val attachmentDir = File(filesDir, "comprovantes").apply { mkdirs() }
                val attachment = File(attachmentDir, "comprovante_importado_${receiptId}_${System.currentTimeMillis()}.img")
                contentResolver.openInputStream(data.data!!).use { input ->
                    if (input == null) error("Não foi possível abrir a imagem.")
                    attachment.outputStream().use { output -> input.copyTo(output) }
                }
                database.setReceiptAttachment(receiptId, attachment.absolutePath)
                toast("Comprovante anexado à despesa.")
                showScreen("receipts")
            } catch (error: Exception) {
                toast(error.message ?: "Não foi possível anexar o comprovante.")
            }
            return
        }
        if (requestCode == REQUEST_IMPORT_EXPENSES) {
            try {
                val uri = data.data!!
                val fileName = uri.lastPathSegment ?: "despesas.xlsx"
                val preview = contentResolver.openInputStream(uri).use { input ->
                    if (input == null) error("Não foi possível abrir a planilha.")
                    ExpenseSpreadsheetImporter.read(input, fileName)
                }
                val valid = preview.validRows.size
                val invalid = preview.invalidRows.size
                val examples = preview.rows.take(8).joinToString("\n") { row ->
                    val status = if (row.errors.isEmpty()) "OK" else "ERRO: ${row.errors.joinToString(", ")}"
                    "${row.sheetName} • linha ${row.rowNumber}: ${row.recipient} — ${money(row.amountCents)} — ${row.date.ifBlank { "sem data" }} [$status]"
                }
                val message = "Arquivo: ${preview.fileName}\nLinhas válidas: $valid\nLinhas com erro: $invalid\n\n${examples}\n\nA importação ignora duplicatas exatas e cria despesas sem foto quando necessário. Registros com erro serão ignorados."
                AlertDialog.Builder(this)
                    .setTitle("Conferir importação")
                    .setMessage(message)
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Importar válidas") { _, _ ->
                        try {
                            var imported = 0
                            var duplicates = 0
                            val collaborators = database.listCollaborators()
                            preview.validRows.forEach { row ->
                                val person = collaborators.firstOrNull { it.name.equals(row.recipient, ignoreCase = true) }
                                val details = mutableListOf<String>()
                                if (row.sourceStatus.isNotBlank()) details += "Status original: ${row.sourceStatus}"
                                if (row.pixKey.isNotBlank()) details += "Chave Pix: ${row.pixKey}"
                                if (row.pixType.isNotBlank()) details += "Tipo de chave: ${row.pixType}"
                                if (row.observations.isNotBlank() && !row.observations.equals(row.description, ignoreCase = true)) details += "Observações: ${row.observations}"
                                val description = (listOf(row.description) + details).joinToString(" · ")
                                val added = database.importSpreadsheetExpense(
                                    person?.id, row.recipient, row.amountCents, description, row.date
                                )
                                if (added) imported++ else duplicates++
                            }
                            toast("Importação concluída: $imported nova(s), $duplicates duplicada(s), $invalid com erro.")
                            showScreen("receipts")
                        } catch (error: Exception) {
                            toast(error.message ?: "Não foi possível importar a planilha.")
                        }
                    }
                    .show()
            } catch (error: Exception) {
                toast(error.message ?: "Planilha inválida. Use um arquivo .xlsx com cabeçalhos na primeira linha.")
            }
            return
        }
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
        selectedPhotoUris.clear()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) selectedPhotoUris += clip.getItemAt(i).uri
        } else data.data?.let { selectedPhotoUris += it }
        if (selectedPhotoUris.size > expectedPhotoCount) {
            selectedPhotoUris.subList(expectedPhotoCount, selectedPhotoUris.size).clear()
            toast("Foram mantidas apenas as " + expectedPhotoCount + " fotos definidas.")
        }
        updatePhotoSelectionStatus()
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
        content.addView(label("Despesas", 25, ink, true))
        content.addView(label("Toque em um registro para ver as opções.", 13, muted, false).apply { setPadding(0, dp(4), 0, dp(16)) })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(actionButton("+ Nova despesa").apply { setOnClickListener { showScreen("new") } }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(5) })
        actions.addView(actionButton("Importar Excel").apply { setOnClickListener { chooseExpenseSpreadsheet() } }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(5) })
        content.addView(actions, spaced())
        val search = field("Buscar pessoa ou descrição")
        content.addView(search, spaced())
        val filter = Spinner(this)
        val options = listOf("Todas", "Pendente", "Parcial", "Pago")
        filter.adapter = themedSpinnerAdapter(options); filter.background = fieldBackground()
        filter.setPadding(dp(10), dp(8), dp(10), dp(8)); content.addView(filter, spaced())
        val resultsLabel = label(receipts.size.toString() + " despesas", 12, muted, false).apply { setPadding(0, 0, 0, dp(8)) }
        content.addView(resultsLabel)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)
        fun render(queryRaw: String, statusRaw: String) {
            list.removeAllViews()
            val query = queryRaw.trim().lowercase(Locale.ROOT); val status = statusRaw.lowercase(Locale.ROOT)
            val filtered = receipts.filter { receipt ->
                val matchesText = query.isBlank() || receipt.collaborator.lowercase(Locale.ROOT).contains(query) ||
                    receipt.description.lowercase(Locale.ROOT).contains(query) || receipt.expenseDate.contains(query)
                val current = database.paymentStatus(receipt.externalId).status.lowercase(Locale.ROOT)
                matchesText && (status == "todas" || current == status)
            }
            resultsLabel.text = filtered.size.toString() + " despesa(s)"
            if (filtered.isEmpty()) {
                val empty = card()
                empty.addView(label(if (receipts.isEmpty()) "Nenhuma despesa por aqui ainda." else "Nenhum resultado encontrado.", 15, ink, true))
                empty.addView(label(if (receipts.isEmpty()) "Use “Nova despesa” ou importe um Excel." else "Tente mudar a busca ou o filtro.", 13, muted, false).apply { setPadding(0, dp(5), 0, 0) })
                list.addView(empty, spaced()); return
            }
            filtered.forEach { receipt -> list.addView(expenseSummaryRow(receipt) { showReceiptActions(receipt) }, spaced()) }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { render(s?.toString().orEmpty(), filter.selectedItem?.toString().orEmpty()) }
        })
        filter.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) { render(search.text.toString(), options[position]) }
        }
        render("", options.first())
    }

    private fun showReceiptActions(receipt: LocalReceipt) {
        val payment = database.paymentStatus(receipt.externalId)
        val hasProof = receipt.attachmentPath.isNotBlank() && File(receipt.attachmentPath).exists()
        val options = mutableListOf<String>(); val actions = mutableListOf<String>()
        options += if (hasProof) "Ver comprovante" else "Adicionar comprovante"
        actions += if (hasProof) "view" else "attach"
        if (payment.paidCents == 0L) { options += "Editar despesa"; actions += "edit" }
        options += "Excluir despesa"; actions += "delete"
        val proofText = if (hasProof) "Comprovante anexado" else "Comprovante ainda não anexado"
        AlertDialog.Builder(this)
            .setTitle(receipt.collaborator + " • " + money(receipt.amountCents))
            .setMessage(receipt.description + "\nData: " + receipt.expenseDate + "\nStatus: " + payment.status +
                "\nPago: " + money(payment.paidCents) + " • Saldo: " + money(payment.balanceCents) + "\n" + proofText)
            .setItems(options.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "view" -> openReceiptImage(receipt)
                    "attach" -> chooseAttachmentForReceipt(receipt.id)
                    "edit" -> openReceiptEditDialog(receipt)
                    "delete" -> confirmDeleteReceipt(receipt)
                }
            }.setNegativeButton("Fechar", null).show()
    }

    private fun openReceiptImage(receipt: LocalReceipt) {
        try {
            val file = File(receipt.attachmentPath)
            if (!file.exists()) error("O arquivo do comprovante não foi encontrado.")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "image/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
        } catch (error: Exception) { toast(error.message ?: "Não foi possível abrir o comprovante.") }
    }

    private fun confirmDeleteReceipt(receipt: LocalReceipt) {
        AlertDialog.Builder(this).setTitle("Excluir despesa?")
            .setMessage(receipt.description + " — " + money(receipt.amountCents) + "\n\nSe houver pagamentos vinculados, a exclusão será bloqueada para proteger o histórico.")
            .setNegativeButton("Manter", null)
            .setPositiveButton("Excluir") { _, _ ->
                try { database.deleteReceipt(receipt.id); toast("Despesa excluída."); showScreen("receipts") }
                catch (error: Exception) { toast(error.message ?: "Não foi possível excluir esta despesa.") }
            }.show()
    }

    private fun fieldBackground() = GradientDrawable().apply {
        setColor(Color.rgb(17, 18, 30)); cornerRadius = dp(10).toFloat(); setStroke(dp(1), Color.rgb(49, 47, 69))
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
        private const val REQUEST_IMPORT_EXPENSES = 1047
        private const val REQUEST_ATTACH_RECEIPT = 1048
        private const val SUPERVISOR_ID = "00000000-0000-4000-8000-000000000001"
    }
}
