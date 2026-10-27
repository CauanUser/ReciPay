package br.com.recipay.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import androidx.core.content.FileProvider
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.graphics.Paint
import android.graphics.RectF
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
    private var newExpensePdfUri: Uri? = null
    private var newExpensePdfPhotoUris: List<Uri> = emptyList()
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
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(Color.rgb(22, 23, 38)); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Color.rgb(49, 47, 69)) }

            val header = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val details = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            details.addView(label(receipt.collaborator, 14, ink, true))
            details.addView(label(receipt.description, 12, muted, false).apply {
                setPadding(0, dp(3), 0, 0); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val registrationComplete = isRegistrationComplete(receipt)
            details.addView(label(receipt.expenseDate.ifBlank { "Data não informada" } + " • " + if (registrationComplete) "Cadastro concluído" else "Cadastro pendente", 11, if (registrationComplete) green else Color.rgb(251, 191, 36), true).apply {
                setPadding(0, dp(4), 0, 0)
            })
            header.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(label(money(receipt.amountCents), 14, ink, true).apply {
                gravity = Gravity.END
                setPadding(dp(8), 0, 0, 0)
            }, LinearLayout.LayoutParams(-2, -2))
            header.setOnClickListener { onClick() }
            addView(header, LinearLayout.LayoutParams(-1, -2))

            val actions = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            val edit = actionButton("Editar despesa").apply {
                setOnClickListener {
                    if (payment.paidCents > 0L) {
                        toast("Esta despesa já tem pagamentos vinculados; não é possível alterar seus dados sem afetar o histórico.")
                    } else {
                        openReceiptEditDialog(receipt)
                    }
                }
            }
            val delete = actionButton("Excluir").apply {
                setOnClickListener { confirmDeleteReceipt(receipt) }
            }
            actions.addView(edit, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(5) })
            actions.addView(delete, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(5) })
            addView(actions, LinearLayout.LayoutParams(-1, -2))
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
        dateInput = field("AAAA-MM-DD (obrigatória)")
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
        val pdfButton = actionButton("Anexar PDF ou juntar fotos em PDF")
        pdfButton.setOnClickListener { chooseNewExpenseProof() }
        form.addView(pdfButton, fieldParams())
        statusLabel = label("Anexe fotos ou um PDF como comprovante antes de salvar.", 13, muted, false).apply { setPadding(0, dp(3), 0, dp(10)) }
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
        content.addView(actionButton("Importar colaboradores da planilha (.xlsx)").apply { setOnClickListener { chooseCollaboratorSpreadsheet() } }, spaced())
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
        val hasPdfProof = newExpensePdfUri != null || newExpensePdfPhotoUris.isNotEmpty()
        if (!hasPdfProof && expectedPhotoCount < 1) {
            toast("Anexe fotos ou um PDF como comprovante.")
            return
        }
        if (!hasPdfProof && photoUris.size != expectedPhotoCount) {
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
            }.toMutableList()
            if (newExpensePdfUri != null) {
                val pdf = File(attachmentDir, "comprovante_" + System.currentTimeMillis() + ".pdf")
                contentResolver.openInputStream(newExpensePdfUri!!).use { input ->
                    if (input == null) error("Não foi possível abrir o PDF selecionado.")
                    pdf.outputStream().use { output -> input.copyTo(output) }
                }
                attachments.add(pdf)
            } else if (newExpensePdfPhotoUris.isNotEmpty()) {
                val pdf = createPhotoPdfFile(newExpensePdfPhotoUris)
                attachments.add(pdf)
            }
            if (attachments.isEmpty()) error("Anexe um comprovante à despesa.")
            val receiptId = database.addReceipt(selectedCollaborator.id, selectedCollaborator.name, amountCents, description, date, attachments.first().absolutePath)
            database.addReceiptAttachments(receiptId, attachments.map { it.absolutePath })
            amountInput.text.clear()
            descriptionInput.text.clear()
            dateInput.setText(java.time.LocalDate.now().toString())
            selectedPhotoUris.clear()
            newExpensePdfUri = null
            newExpensePdfPhotoUris = emptyList()
            expectedPhotoCount = 0
            photoButton.text = "Definir quantidade de fotos"
            statusLabel.text = "Anexe fotos ou um PDF como comprovante antes de salvar."
            statusLabel.setTextColor(muted)
            toast("Cadastro concluído. Despesa salva neste celular.")
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
        form.addView(actionButton("Adicionar/substituir comprovante (foto ou PDF)").apply {
            setOnClickListener { chooseAttachmentForReceipt(receipt.id) }
        }, fieldParams())
        form.addView(label("O comprovante atual será mantido se você não anexar outro. Despesas com pagamentos vinculados não podem ser editadas.", 12, muted, false).apply {
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
                    toast("Cadastro concluído: alterações salvas. O comprovante foi mantido.")
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

    private fun chooseNewExpenseProof() {
        AlertDialog.Builder(this).setTitle("Comprovante da nova despesa")
            .setItems(arrayOf("Selecionar PDF pronto", "Juntar várias fotos em PDF")) { _, which ->
                if (which == 0) openDocumentPicker("application/pdf", REQUEST_NEW_EXPENSE_PDF, false)
                else openDocumentPicker("image/*", REQUEST_NEW_EXPENSE_PDF_PHOTOS, true)
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun createPhotoPdfFile(uris: List<Uri>): File {
        if (uris.isEmpty()) error("Selecione pelo menos uma foto.")
        val document = PdfDocument()
        try {
            uris.forEachIndexed { index, uri ->
                val bitmap = contentResolver.openInputStream(uri).use { input ->
                    if (input == null) error("Não foi possível abrir uma foto.")
                    BitmapFactory.decodeStream(input) ?: error("Imagem inválida.")
                }
                try {
                    val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                    page.canvas.drawColor(Color.WHITE)
                    val margin = 24f; val maxW = 547f; val maxH = 770f
                    val scale = minOf(maxW / bitmap.width, maxH / bitmap.height)
                    val w = bitmap.width * scale; val h = bitmap.height * scale
                    val left = (595f - w) / 2f; val top = 48f + (maxH - h) / 2f
                    page.canvas.drawText("Comprovante " + (index + 1) + "/" + uris.size, margin, 24f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 12f })
                    page.canvas.drawBitmap(bitmap, null, RectF(left, top, left + w, top + h),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    document.finishPage(page)
                } finally { bitmap.recycle() }
            }
            val dir = File(filesDir, "comprovantes").apply { mkdirs() }
            val target = File(dir, "comprovante_nova_despesa_" + System.currentTimeMillis() + ".pdf")
            target.outputStream().use { document.writeTo(it) }
            return target
        } finally { document.close() }
    }

    private fun choosePhotosForPdf() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, REQUEST_PDF_PHOTOS)
    }

    private fun chooseReadyPdf() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        startActivityForResult(intent, REQUEST_READY_PDF)
    }

    private fun createAndSharePhotoPdf(uris: List<Uri>) {
        if (uris.isEmpty()) error("Selecione pelo menos uma foto.")
        val document = PdfDocument()
        try {
            val pageWidth = 595
            val pageHeight = 842
            uris.forEachIndexed { index, uri ->
                val bitmap = contentResolver.openInputStream(uri).use { input ->
                    if (input == null) error("Não foi possível abrir uma das fotos.")
                    android.graphics.BitmapFactory.decodeStream(input) ?: error("Uma das imagens não pôde ser lida.")
                }
                try {
                    val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                    val page = document.startPage(pageInfo)
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    val margin = 24f
                    val maxWidth = pageWidth - margin * 2
                    val maxHeight = pageHeight - margin * 2 - 24f
                    val scale = minOf(maxWidth / bitmap.width, maxHeight / bitmap.height)
                    val drawWidth = bitmap.width * scale
                    val drawHeight = bitmap.height * scale
                    val left = (pageWidth - drawWidth) / 2f
                    val top = margin + 24f + (maxHeight - drawHeight) / 2f
                    canvas.drawText("Comprovante • ${index + 1}/${uris.size}", margin, 20f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.DKGRAY; textSize = 12f
                    })
                    canvas.drawBitmap(bitmap, null, RectF(left, top, left + drawWidth, top + drawHeight), paint)
                    document.finishPage(page)
                } finally { bitmap.recycle() }
            }
            val out = File(cacheDir, "recipay_comprovantes_${System.currentTimeMillis()}.pdf")
            out.outputStream().use { document.writeTo(it) }
            sharePdf(out)
        } finally { document.close() }
    }

    private fun sharePdf(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Comprovantes de recibos - ReciPay")
            putExtra(Intent.EXTRA_TEXT, "Segue o PDF com os comprovantes.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Enviar comprovante em PDF"))
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
        AlertDialog.Builder(this).setTitle("Comprovante desta despesa")
            .setItems(arrayOf("Anexar foto(s)", "Juntar fotos em PDF", "Anexar PDF pronto")) { _, which ->
                when (which) {
                    0 -> openDocumentPicker("image/*", REQUEST_ATTACH_RECEIPT, true)
                    1 -> openDocumentPicker("image/*", REQUEST_ATTACH_PDF_PHOTOS, true)
                    2 -> openDocumentPicker("application/pdf", REQUEST_ATTACH_READY_PDF, false)
                }
            }.setNegativeButton("Cancelar", null).show()
    }
    private fun openDocumentPicker(mime: String, request: Int, multiple: Boolean) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = mime; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        }, request)
    }
    private fun chooseCollaboratorSpreadsheet() = openDocumentPicker("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", REQUEST_IMPORT_COLLABORATORS, false)
    private fun persistPdf(uri: Uri, receiptId: Long) {
        val dir = File(filesDir, "comprovantes").apply { mkdirs() }
        val target = File(dir, "comprovante_" + receiptId + "_" + System.currentTimeMillis() + ".pdf")
        contentResolver.openInputStream(uri).use { input ->
            if (input == null) error("Não foi possível abrir o PDF.")
            target.outputStream().use { output -> input.copyTo(output) }
        }
        database.addReceiptAttachments(receiptId, listOf(target.absolutePath))
    }
    private fun persistPhotosAsPdf(uris: List<Uri>, receiptId: Long) {
        if (uris.isEmpty()) error("Selecione pelo menos uma foto.")
        val document = PdfDocument()
        try {
            uris.forEachIndexed { index, uri ->
                val bitmap = contentResolver.openInputStream(uri).use { input ->
                    if (input == null) error("Não foi possível abrir uma foto.")
                    android.graphics.BitmapFactory.decodeStream(input) ?: error("Imagem inválida.")
                }
                try {
                    val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                    page.canvas.drawColor(Color.WHITE)
                    val margin = 24f; val maxW = 547f; val maxH = 770f
                    val scale = minOf(maxW / bitmap.width, maxH / bitmap.height)
                    val w = bitmap.width * scale; val h = bitmap.height * scale
                    val left = (595f - w) / 2f; val top = 48f + (maxH - h) / 2f
                    page.canvas.drawText("Comprovante " + (index + 1) + "/" + uris.size, margin, 24f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 12f })
                    page.canvas.drawBitmap(bitmap, null, RectF(left, top, left + w, top + h), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    document.finishPage(page)
                } finally { bitmap.recycle() }
            }
            val dir = File(filesDir, "comprovantes").apply { mkdirs() }
            val target = File(dir, "comprovante_" + receiptId + "_" + System.currentTimeMillis() + ".pdf")
            target.outputStream().use { document.writeTo(it) }
            database.addReceiptAttachments(receiptId, listOf(target.absolutePath))
        } finally { document.close() }
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
        if (requestCode == REQUEST_NEW_EXPENSE_PDF || requestCode == REQUEST_NEW_EXPENSE_PDF_PHOTOS) {
            try {
                if (requestCode == REQUEST_NEW_EXPENSE_PDF) {
                    newExpensePdfUri = data?.data ?: error("Nenhum PDF foi selecionado.")
                    newExpensePdfPhotoUris = emptyList()
                    toast("PDF selecionado. Ele será anexado ao salvar a despesa.")
                } else {
                    val uris = mutableListOf<Uri>()
                    data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri }
                    if (uris.isEmpty()) data?.data?.let { uris += it }
                    if (uris.isEmpty()) error("Selecione pelo menos uma foto.")
                    newExpensePdfPhotoUris = uris
                    newExpensePdfUri = null
                    toast(uris.size.toString() + " foto(s) selecionada(s). Um PDF será criado ao salvar a despesa.")
                }
            } catch (error: Exception) { toast(error.message ?: "Não foi possível selecionar o comprovante.") }
            return
        }
        if (requestCode == REQUEST_PDF_PHOTOS) {
            try {
                val uris = mutableListOf<Uri>()
                data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri }
                if (uris.isEmpty()) data?.data?.let { uris += it }
                if (uris.isEmpty()) error("Selecione pelo menos uma foto.")
                createAndSharePhotoPdf(uris)
            } catch (error: Exception) { toast(error.message ?: "Não foi possível criar o PDF.") }
            return
        }
        if (requestCode == REQUEST_READY_PDF) {
            try {
                val uri = data?.data ?: error("Nenhum PDF foi selecionado.")
                val file = File(cacheDir, "recipay_comprovante_pronto_${System.currentTimeMillis()}.pdf")
                contentResolver.openInputStream(uri).use { input ->
                    if (input == null) error("Não foi possível abrir o PDF.")
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                sharePdf(file)
            } catch (error: Exception) { toast(error.message ?: "Não foi possível compartilhar o PDF.") }
            return
        }
        if (requestCode == REQUEST_ATTACH_READY_PDF || requestCode == REQUEST_ATTACH_PDF_PHOTOS) {
            val receiptId = pendingAttachmentReceiptId
            pendingAttachmentReceiptId = null
            try {
                if (receiptId == null) error("Não foi possível identificar a despesa.")
                if (requestCode == REQUEST_ATTACH_READY_PDF) {
                    persistPdf(data?.data ?: error("Nenhum PDF foi selecionado."), receiptId)
                    toast("PDF anexado à despesa.")
                } else {
                    val uris = mutableListOf<Uri>()
                    data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri }
                    if (uris.isEmpty()) data?.data?.let { uris += it }
                    persistPhotosAsPdf(uris, receiptId)
                    toast("PDF com " + uris.size + " foto(s) anexado à despesa.")
                }
                showScreen("receipts")
            } catch (error: Exception) { toast(error.message ?: "Não foi possível anexar o comprovante.") }
            return
        }
        if (requestCode == REQUEST_IMPORT_COLLABORATORS) {
            try {
                val uri = data?.data ?: error("Nenhuma planilha foi selecionada.")
                val preview = contentResolver.openInputStream(uri).use { input ->
                    if (input == null) error("Não foi possível abrir a planilha.")
                    CollaboratorSpreadsheetImporter.read(input, uri.lastPathSegment ?: "colaboradores.xlsx")
                }
                val valid = preview.rows.filter { it.errors.isEmpty() }
                val existing = database.listCollaborators().map { it.name.trim().lowercase(Locale.ROOT) }.toMutableSet()
                val newCount = valid.count { it.name.trim().lowercase(Locale.ROOT) !in existing }
                val invalid = preview.rows.count { it.errors.isNotEmpty() }
                AlertDialog.Builder(this).setTitle("Conferir colaboradores")
                    .setMessage("Novos: " + newCount + "\\nJá cadastrados: " + (valid.size - newCount) + "\\nCom erro: " + invalid +
                        "\\n\\n" + preview.rows.take(8).joinToString("\\n") { it.name + if (it.errors.isEmpty()) "" else " • ERRO: " + it.errors.joinToString(", ") })
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Importar novos") { _, _ ->
                        try {
                            var imported = 0; var skipped = 0
                            val known = database.listCollaborators().map { it.name.trim().lowercase(Locale.ROOT) }.toMutableSet()
                            valid.forEach { p ->
                                if (!known.add(p.name.trim().lowercase(Locale.ROOT))) skipped++
                                else { database.addCollaborator(p.name, p.team, p.role, p.pixType, p.pixKey, p.pixHolder); imported++ }
                            }
                            toast("Importados: " + imported + " • Duplicados: " + skipped + " • Com erro: " + invalid)
                            showScreen("people")
                        } catch (error: Exception) { toast(error.message ?: "Falha ao importar colaboradores.") }
                    }.show()
            } catch (error: Exception) { toast(error.message ?: "Planilha inválida. Confira a aba Colaboradores.") }
            return
        }
        if (data?.data == null && data?.clipData == null) return
        if (requestCode == REQUEST_ATTACH_RECEIPT) {
            val receiptId = pendingAttachmentReceiptId
            pendingAttachmentReceiptId = null
            if (receiptId == null) { toast("Não foi possível identificar a despesa."); return }
            try {
                val uris = mutableListOf<Uri>()
                val clip = data?.clipData
                if (clip != null) for (i in 0 until clip.itemCount) uris += clip.getItemAt(i).uri
                else data?.data?.let { uris += it }
                if (uris.isEmpty()) error("Selecione pelo menos uma foto.")
                val attachmentDir = File(filesDir, "comprovantes").apply { mkdirs() }
                val attachments = uris.mapIndexed { index, uri ->
                    val attachment = File(attachmentDir, "comprovante_importado_" + receiptId + "_" + System.currentTimeMillis() + "_" + index + ".img")
                    contentResolver.openInputStream(uri).use { input ->
                        if (input == null) error("Não foi possível abrir uma das imagens.")
                        attachment.outputStream().use { output -> input.copyTo(output) }
                    }
                    attachment
                }
                database.addReceiptAttachments(receiptId, attachments.map { it.absolutePath })
                toast(attachments.size.toString() + " foto(s) anexada(s) à despesa.")
                showScreen("receipts")
            } catch (error: Exception) { toast(error.message ?: "Não foi possível anexar as fotos.") }
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

    private fun isRegistrationComplete(receipt: LocalReceipt): Boolean {
        val validDate = try { java.time.LocalDate.parse(receipt.expenseDate); true } catch (_: Exception) { false }
        val hasSavedProof = database.receiptAttachments(receipt.id).any { path ->
            val file = File(path)
            file.isFile && file.length() > 0L
        }
        return validDate && hasSavedProof
    }

    private fun showReceiptActions(receipt: LocalReceipt) {
        val payment = database.paymentStatus(receipt.externalId)
        val hasProof = database.receiptAttachments(receipt.id).any { File(it).exists() }
        val options = mutableListOf<String>(); val actions = mutableListOf<String>()
        options += if (hasProof) "Ver comprovante" else "Adicionar comprovante"
        actions += if (hasProof) "view" else "attach"
        if (payment.paidCents == 0L) { options += "Editar despesa"; actions += "edit" }
        options += "Excluir despesa"; actions += "delete"
        val proofText = if (hasProof) "Comprovante anexado" else "Comprovante ainda não anexado"
        AlertDialog.Builder(this)
            .setTitle(receipt.collaborator + " • " + money(receipt.amountCents))
            .setMessage(receipt.description + "\nData: " + receipt.expenseDate.ifBlank { "Não informada" } + "\nCadastro: " + (if (isRegistrationComplete(receipt)) "Concluído" else "Pendente — informe a data e anexe o comprovante") +
                "\nPagamento: " + payment.status + "\nPago: " + money(payment.paidCents) + " • Saldo: " + money(payment.balanceCents) + "\n" + proofText)
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
            val files = database.receiptAttachments(receipt.id).filter { File(it).exists() }
            if (files.isEmpty()) error("Nenhum comprovante foi encontrado.")
            fun open(path: String) {
                val file = File(path); val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val mime = if (file.extension.equals("pdf", true)) "application/pdf" else "image/*"
                startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, mime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
            }
            if (files.size == 1) open(files.first())
            else AlertDialog.Builder(this).setTitle("Escolha um comprovante (" + files.size + ")")
                .setItems(files.mapIndexed { i, path -> (if (File(path).extension.equals("pdf", true)) "PDF " else "Foto ") + (i + 1) }.toTypedArray()) { _, which -> open(files[which]) }
                .setNegativeButton("Fechar", null).show()
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
        private const val REQUEST_PDF_PHOTOS = 1049
        private const val REQUEST_READY_PDF = 1050
        private const val REQUEST_IMPORT_COLLABORATORS = 1051
        private const val REQUEST_ATTACH_PDF_PHOTOS = 1052
        private const val REQUEST_ATTACH_READY_PDF = 1053
        private const val REQUEST_NEW_EXPENSE_PDF = 1054
        private const val REQUEST_NEW_EXPENSE_PDF_PHOTOS = 1055
        private const val SUPERVISOR_ID = "00000000-0000-4000-8000-000000000001"
    }
}
