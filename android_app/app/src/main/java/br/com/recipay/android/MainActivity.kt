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

    private val blue = Color.rgb(30, 90, 168)
    private val ink = Color.rgb(30, 41, 59)
    private val muted = Color.rgb(100, 116, 139)
    private val green = Color.rgb(22, 128, 72)
    private val red = Color.rgb(185, 28, 28)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = ReceiptDatabase(this)
        renderHome()
        renderReceipts()
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
        header.addView(label("Controle de despesas do Supervisor", 14, Color.rgb(225, 237, 255), false).apply {
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

        content.addView(label("Cadastrar despesa", 22, ink, true))
        content.addView(label("Registre a despesa do colaborador e anexe uma foto ou imagem do comprovante. Os dados ficam salvos neste celular.", 14, muted, false).apply {
            setPadding(0, dp(6), 0, dp(16))
        })

        val form = card()
        collaboratorSpinner = Spinner(this)
        refreshCollaboratorSpinner()
        amountInput = field("Valor (ex.: 125,50)").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        descriptionInput = field("Descrição da despesa")
        dateInput = field("Data (AAAA-MM-DD)").apply {
            setText(java.time.LocalDate.now().toString())
        }
        form.addView(fieldLabel("Colaborador cadastrado"))
        form.addView(collaboratorSpinner, fieldParams())
        form.addView(fieldLabel("Valor em reais"))
        form.addView(amountInput, fieldParams())
        form.addView(fieldLabel("Descrição"))
        form.addView(descriptionInput, fieldParams())
        form.addView(fieldLabel("Data da despesa"))
        form.addView(dateInput, fieldParams())

        photoButton = actionButton("Anexar foto do comprovante")
        photoButton.setOnClickListener { openPhotoPicker() }
        form.addView(photoButton, fieldParams())
        statusLabel = label("Comprovante obrigatório.", 13, muted, false)
        statusLabel.setPadding(0, dp(2), 0, dp(10))
        form.addView(statusLabel)

        val saveButton = actionButton("Salvar despesa")
        saveButton.setOnClickListener { saveReceipt() }
        form.addView(saveButton, matchWrap())
        content.addView(form, spaced())

        val collaboratorButton = actionButton("Cadastrar colaborador e chave Pix")
        collaboratorButton.setOnClickListener { openCollaboratorDialog() }
        content.addView(collaboratorButton, spaced())

        val exportButton = actionButton("Exportar para o Gestor (.xlsx)")
        exportButton.setOnClickListener { exportReceipts() }
        content.addView(exportButton, spaced())

        content.addView(label("Despesas salvas neste celular", 20, ink, true).apply {
            setPadding(dp(2), dp(8), 0, dp(10))
        })
    }

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
                    renderReceipts()
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
            renderReceipts()
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
        if (!::content.isInitialized) return
        // Keep the title and form, replacing only the list cards below them.
        val listStart = 5
        if (content.childCount > listStart) {
            content.removeViews(listStart, content.childCount - listStart)
        }
        val collaborators = database.listCollaborators()
        refreshCollaboratorSpinner()
        content.addView(label("Colaboradores e chaves Pix", 20, ink, true).apply {
            setPadding(dp(2), dp(8), 0, dp(10))
        })
        if (collaborators.isEmpty()) {
            val empty = card()
            empty.addView(label("Nenhum colaborador cadastrado ainda.", 14, muted, false))
            content.addView(empty, spaced())
        } else {
            collaborators.forEach { collaborator ->
                val item = card()
                item.addView(label(collaborator.name, 16, ink, true))
                if (collaborator.team.isNotBlank()) {
                    item.addView(label("Equipe: ${collaborator.team}", 13, muted, false).apply { setPadding(0, dp(3), 0, 0) })
                }
                item.addView(label("${collaborator.pixType}: ${collaborator.pixKey}", 14, blue, true).apply { setPadding(0, dp(5), 0, 0) })
                item.addView(label("Titular: ${collaborator.pixHolder}", 13, muted, false).apply { setPadding(0, dp(3), 0, 0) })
                content.addView(item, spaced())
            }
        }

        content.addView(label("Despesas", 20, ink, true).apply {
            setPadding(dp(2), dp(8), 0, dp(10))
        })
        val receipts = database.listReceipts()
        if (receipts.isEmpty()) {
            val empty = card()
            empty.addView(label("Nenhuma despesa cadastrada ainda.", 14, muted, false))
            content.addView(empty, spaced())
            return
        }
        receipts.forEach { receipt ->
            val item = card()
            item.addView(label(receipt.collaborator, 16, ink, true))
            item.addView(label(money(receipt.amountCents), 20, blue, true).apply {
                setPadding(0, dp(5), 0, 0)
            })
            item.addView(label(receipt.description, 14, ink, false).apply { setPadding(0, dp(4), 0, 0) })
            item.addView(label("Data: ${receipt.expenseDate}", 12, muted, false).apply { setPadding(0, dp(5), 0, 0) })
            item.addView(label("Comprovante anexado", 12, green, true).apply { setPadding(0, dp(5), 0, 0) })
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
    }
}
