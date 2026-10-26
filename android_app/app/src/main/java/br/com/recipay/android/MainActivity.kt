package br.com.recipay.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.NumberFormat
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var database: ReceiptDatabase
    private lateinit var content: LinearLayout
    private lateinit var collaboratorInput: EditText
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
        collaboratorInput = field("Nome do colaborador")
        amountInput = field("Valor (ex.: 125,50)").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        descriptionInput = field("Descrição da despesa")
        dateInput = field("Data (AAAA-MM-DD)").apply {
            setText(java.time.LocalDate.now().toString())
        }
        form.addView(fieldLabel("Colaborador"))
        form.addView(collaboratorInput, fieldParams())
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

        content.addView(label("Despesas salvas neste celular", 20, ink, true).apply {
            setPadding(dp(2), dp(8), 0, dp(10))
        })
    }

    private fun saveReceipt() {
        val collaborator = collaboratorInput.text.toString().trim()
        val amountText = amountInput.text.toString().trim()
        val description = descriptionInput.text.toString().trim()
        val date = dateInput.text.toString().trim()
        val photoUri = selectedPhotoUri

        if (collaborator.isBlank() || amountText.isBlank() || description.isBlank() || date.isBlank()) {
            toast("Preencha todos os campos.")
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
            database.addReceipt(collaborator, amountCents, description, date, attachment.absolutePath)
            collaboratorInput.text.clear()
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

    @Deprecated("Kept compatible with the minimal native prototype.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PHOTO || resultCode != Activity.RESULT_OK) return
        selectedPhotoUri = data?.data
        if (selectedPhotoUri != null) {
            photoButton.text = "Comprovante selecionado ✓"
            statusLabel.text = "Imagem selecionada. Ela será copiada para o armazenamento privado do app ao salvar."
            statusLabel.setTextColor(green)
        }
    }

    private fun renderReceipts() {
        if (!::content.isInitialized) return
        // Keep the title and form, replacing only the list cards below them.
        val listStart = 3
        if (content.childCount > listStart) {
            content.removeViews(listStart, content.childCount - listStart)
        }
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
        singleLine = true
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
    }
}
