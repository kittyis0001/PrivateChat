package com.privatechat.app.notes.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.lifecycle.lifecycleScope
import com.privatechat.app.R
import com.privatechat.app.notes.calculator.CalculatorEvaluator
import com.privatechat.app.notes.calculator.CalculatorFormat
import com.privatechat.app.notes.data.NotesRepository
import com.privatechat.app.notes.sync.CalculatorHistorySync
import com.privatechat.app.notes.sync.NotesSyncManager
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A normal calculator (dark UI, like the reference screenshots):
 * live result while typing, + - × ÷ with precedence, postfix %,
 * AC/C, backspace, and a saved history. Nothing is ever saved as a
 * note. The Converter tab converts length, weight, temperature,
 * area and volume offline (no currency — that needs live rates).
 *
 * History lives in Room (Room v8) on this phone. When the user is
 * signed in with Google it is also synced to the Realtime Database
 * (see CalculatorHistorySync); without the Firebase rules for that
 * node the sync quietly does nothing and history still works locally.
 */
class CalculatorActivity : AppCompatActivity() {

    private lateinit var repo: NotesRepository
    private lateinit var expressionView: TextView
    private lateinit var resultView: TextView
    private lateinit var acButton: AppCompatButton

    /** The expression as typed (no grouping commas; '-' for minus). */
    private var expression = ""
    /** True right after '=': the expression now holds the result. */
    private var justEvaluated = false
    /** True after '=' on an invalid expression, until the next key. */
    private var showError = false
    private var showingConverter = false

    private val historyLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val expr = result.data
                    ?.getStringExtra(CalculatorHistoryActivity.EXTRA_EXPRESSION)
                    ?: return@registerForActivityResult
                expression = expr
                justEvaluated = false
                showError = false
                refreshDisplay()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calculator)
        repo = NotesRepository(this)

        expressionView = findViewById(R.id.calcExpression)
        resultView = findViewById(R.id.calcResult)
        acButton = findViewById(R.id.calcAc)

        // ---- tabs ----
        findViewById<View>(R.id.calcTab).setOnClickListener { showCalculator() }
        findViewById<View>(R.id.converterTab).setOnClickListener { showConverter() }
        findViewById<View>(R.id.calcMenu).setOnClickListener { showCalcMenu(it) }

        // ---- keypad ----
        val digits = mapOf(
            R.id.calc0 to '0', R.id.calc1 to '1', R.id.calc2 to '2',
            R.id.calc3 to '3', R.id.calc4 to '4', R.id.calc5 to '5',
            R.id.calc6 to '6', R.id.calc7 to '7', R.id.calc8 to '8',
            R.id.calc9 to '9'
        )
        digits.forEach { (id, d) ->
            findViewById<View>(id).setOnClickListener { appendDigit(d) }
        }
        findViewById<View>(R.id.calcDot).setOnClickListener { appendDot() }
        findViewById<View>(R.id.calcPlus).setOnClickListener { appendOperator('+') }
        findViewById<View>(R.id.calcMinus).setOnClickListener { appendOperator('-') }
        findViewById<View>(R.id.calcMultiply).setOnClickListener { appendOperator('×') }
        findViewById<View>(R.id.calcDivide).setOnClickListener { appendOperator('÷') }
        findViewById<View>(R.id.calcPercent).setOnClickListener { appendPercent() }
        findViewById<View>(R.id.calcBackspace).setOnClickListener { backspace() }
        findViewById<View>(R.id.calcAc).setOnClickListener { clearAll() }
        findViewById<View>(R.id.calcEquals).setOnClickListener { equals() }
        findViewById<View>(R.id.calcHistoryBtn).setOnClickListener { openHistory() }

        setupConverter()

        // Signed in: pull any calculator history saved on another device.
        if (NotesSyncManager.isSignedIn()) {
            lifecycleScope.launch { CalculatorHistorySync.downloadAndMerge(repo) }
        }
        refreshDisplay()
    }

    override fun onPause() {
        super.onPause()
        // Signed in: push the history up, so it follows the account.
        if (NotesSyncManager.isSignedIn()) {
            lifecycleScope.launch { CalculatorHistorySync.uploadNow(repo) }
        }
    }

    // ---------------- keypad ----------------

    private fun appendDigit(d: Char) {
        if (justEvaluated) {
            expression = ""
            justEvaluated = false
        }
        clearIfError()
        expression += d
        refreshDisplay()
    }

    private fun appendDot() {
        if (justEvaluated) {
            expression = ""
            justEvaluated = false
        }
        clearIfError()
        // One dot per number: look at the segment after the last operator.
        val segment = expression.substringAfterLast('+')
            .substringAfterLast('-').substringAfterLast('×').substringAfterLast('÷')
        if (segment.contains('.')) return
        expression += if (segment.isEmpty()) "0." else "."
        refreshDisplay()
    }

    private fun appendOperator(op: Char) {
        clearIfError()
        if (expression.isEmpty()) {
            // A leading minus starts a negative number.
            if (op == '-') {
                expression = "-"
                refreshDisplay()
            }
            return
        }
        justEvaluated = false
        val last = expression.last()
        expression = if (last == '+' || last == '-' || last == '×' || last == '÷') {
            // Replace the trailing operator instead of stacking two.
            expression.dropLast(1) + op
        } else {
            expression + op
        }
        refreshDisplay()
    }

    private fun appendPercent() {
        if (expression.isEmpty()) return
        clearIfError()
        if (expression.isEmpty()) return
        val last = expression.last()
        if (last == '%' || last == '+' || last == '-' || last == '×' || last == '÷') return
        justEvaluated = false
        expression += '%'
        refreshDisplay()
    }

    private fun backspace() {
        if (expression.isEmpty()) return
        showError = false
        justEvaluated = false
        expression = expression.dropLast(1)
        refreshDisplay()
    }

    private fun clearAll() {
        expression = ""
        justEvaluated = false
        showError = false
        refreshDisplay()
    }

    private fun equals() {
        if (expression.isEmpty()) return
        val shownExpr = CalculatorFormat.formatExpression(expression)
        val value = CalculatorEvaluator.evaluate(expression)
        if (value == null) {
            showError = true
            refreshDisplay()
            val errorText = getString(R.string.calculator_error)
            lifecycleScope.launch { repo.addCalculatorHistory(shownExpr, errorText) }
            return
        }
        val resultText = CalculatorFormat.formatResult(value)
        lifecycleScope.launch { repo.addCalculatorHistory(shownExpr, resultText) }
        expression = resultText.replace(",", "")
        justEvaluated = true
        showError = false
        refreshDisplay()
    }

    /** After an error, the next key starts a fresh calculation. */
    private fun clearIfError() {
        if (showError) {
            expression = ""
            showError = false
        }
    }

    private fun refreshDisplay() {
        expressionView.text = CalculatorFormat.formatExpression(expression)
        acButton.text = if (expression.isEmpty()) "AC" else "C"
        resultView.text = when {
            showError -> "= ${getString(R.string.calculator_error)}"
            justEvaluated -> ""
            expression.isEmpty() -> "0"
            else -> {
                val v = CalculatorEvaluator.evaluate(expression)
                if (v == null) "" else "= ${CalculatorFormat.formatResult(v)}"
            }
        }
    }

    // ---------------- tabs, menu, history ----------------

    private fun showCalculator() {
        showingConverter = false
        findViewById<View>(R.id.calcView).visibility = View.VISIBLE
        findViewById<View>(R.id.converterView).visibility = View.GONE
        paintTabs()
    }

    private fun showConverter() {
        showingConverter = true
        findViewById<View>(R.id.calcView).visibility = View.GONE
        findViewById<View>(R.id.converterView).visibility = View.VISIBLE
        paintTabs()
    }

    private fun paintTabs() {
        val calcTab = findViewById<TextView>(R.id.calcTab)
        val convTab = findViewById<TextView>(R.id.converterTab)
        calcTab.setTextColor(if (showingConverter) 0xFF8E8E93.toInt() else 0xFFFFFFFF.toInt())
        convTab.setTextColor(if (showingConverter) 0xFFFFFFFF.toInt() else 0xFF8E8E93.toInt())
        calcTab.paint.isFakeBoldText = !showingConverter
        convTab.paint.isFakeBoldText = showingConverter
    }

    private fun showCalcMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.apply {
            add(0, 1, 0, getString(R.string.calculator_history))
            add(0, 2, 1, getString(R.string.calculator_clear_history))
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    openHistory()
                    true
                }
                2 -> {
                    confirmClearHistory()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun openHistory() {
        historyLauncher.launch(Intent(this, CalculatorHistoryActivity::class.java))
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.calculator_clear_history))
            .setMessage(getString(R.string.calculator_clear_history_confirm))
            .setPositiveButton(getString(R.string.notes_delete_short)) { _, _ ->
                lifecycleScope.launch {
                    repo.clearCalculatorHistory()
                    if (NotesSyncManager.isSignedIn()) {
                        CalculatorHistorySync.uploadNow(repo)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------- converter ----------------

    private data class ConvUnit(val symbol: String, val toBase: Double? = null)
    private data class ConvCat(val nameRes: Int, val units: List<ConvUnit>, val isTemp: Boolean)

    private val convCategories = listOf(
        ConvCat(
            R.string.converter_length,
            listOf("mm" to 0.001, "cm" to 0.01, "m" to 1.0, "km" to 1000.0,
                "in" to 0.0254, "ft" to 0.3048, "yd" to 0.9144, "mi" to 1609.344)
                .map { ConvUnit(it.first, it.second) },
            false
        ),
        ConvCat(
            R.string.converter_weight,
            listOf("mg" to 0.000001, "g" to 0.001, "kg" to 1.0, "t" to 1000.0,
                "oz" to 0.028349523125, "lb" to 0.45359237)
                .map { ConvUnit(it.first, it.second) },
            false
        ),
        ConvCat(
            R.string.converter_temperature,
            listOf(ConvUnit("°C"), ConvUnit("°F"), ConvUnit("K")),
            true
        ),
        ConvCat(
            R.string.converter_area,
            listOf("mm²" to 0.000001, "cm²" to 0.0001, "m²" to 1.0, "km²" to 1000000.0,
                "ft²" to 0.09290304, "acre" to 4046.8564224, "ha" to 10000.0)
                .map { ConvUnit(it.first, it.second) },
            false
        ),
        ConvCat(
            R.string.converter_volume,
            listOf("mL" to 0.001, "L" to 1.0, "m³" to 1000.0,
                "cup" to 0.2365882365, "gal" to 3.785411784)
                .map { ConvUnit(it.first, it.second) },
            false
        )
    )
    private var convCategory = 0

    private lateinit var convFromValue: EditText
    private lateinit var convToValue: TextView
    private lateinit var convFromUnit: Spinner
    private lateinit var convToUnit: Spinner
    private val convCatViews = mutableListOf<TextView>()

    private fun setupConverter() {
        convFromValue = findViewById(R.id.convFromValue)
        convToValue = findViewById(R.id.convToValue)
        convFromUnit = findViewById(R.id.convFromUnit)
        convToUnit = findViewById(R.id.convToUnit)
        convCatViews.addAll(
            listOf(
                R.id.convCatLength, R.id.convCatWeight, R.id.convCatTemp,
                R.id.convCatArea, R.id.convCatVolume
            ).map { findViewById<TextView>(it) }
        )
        convCatViews.forEachIndexed { index, view ->
            view.setOnClickListener { selectConvCategory(index) }
        }
        convFromValue.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { convertNow() }
        })
        val spinnerListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = convertNow()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        convFromUnit.onItemSelectedListener = spinnerListener
        convToUnit.onItemSelectedListener = spinnerListener
        findViewById<View>(R.id.convSwap).setOnClickListener {
            val from = convFromUnit.selectedItemPosition
            val to = convToUnit.selectedItemPosition
            convFromUnit.setSelection(to)
            convToUnit.setSelection(from)
            convertNow()
        }
        selectConvCategory(0)
    }

    private fun selectConvCategory(index: Int) {
        convCategory = index
        convCatViews.forEachIndexed { i, view ->
            view.setBackgroundResource(
                if (i == index) R.drawable.bg_calc_chip_selected else R.drawable.bg_calc_chip
            )
            view.setTextColor(if (i == index) 0xFFFFFFFF.toInt() else 0xFF8E8E93.toInt())
        }
        val symbols = convCategories[index].units.map { it.symbol }
        val fromAdapter = ArrayAdapter(this, R.layout.item_spinner_dark, symbols)
        fromAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val toAdapter = ArrayAdapter(this, R.layout.item_spinner_dark, symbols)
        toAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        convFromUnit.adapter = fromAdapter
        convToUnit.adapter = toAdapter
        convFromUnit.setSelection(0)
        convToUnit.setSelection(if (symbols.size > 1) 1 else 0)
        convertNow()
    }

    private fun convertNow() {
        if (!::convFromValue.isInitialized) return
        val value = convFromValue.text.toString().toDoubleOrNull()
        if (value == null) {
            convToValue.text = ""
            return
        }
        val cat = convCategories[convCategory]
        val from = cat.units[convFromUnit.selectedItemPosition]
        val to = cat.units[convToUnit.selectedItemPosition]
        val result = if (cat.isTemp) {
            val celsius = when (from.symbol) {
                "°C" -> value
                "°F" -> (value - 32.0) * 5.0 / 9.0
                else -> value - 273.15
            }
            when (to.symbol) {
                "°C" -> celsius
                "°F" -> celsius * 9.0 / 5.0 + 32.0
                else -> celsius + 273.15
            }
        } else {
            val fromFactor = from.toBase ?: return
            val toFactor = to.toBase ?: return
            value * fromFactor / toFactor
        }
        if (!result.isFinite()) {
            convToValue.text = ""
            return
        }
        convToValue.text = if (result == 0.0) {
            "0"
        } else {
            BigDecimal.valueOf(result)
                .setScale(6, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
        }
    }
}
