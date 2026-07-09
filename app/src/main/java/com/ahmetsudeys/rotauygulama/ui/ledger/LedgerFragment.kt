package com.ahmetsudeys.rotauygulama.ui.ledger

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.ahmetsudeys.rotauygulama.R
import com.ahmetsudeys.rotauygulama.data.customer.CustomerStorage
import com.ahmetsudeys.rotauygulama.data.ledger.LedgerCalculator
import com.ahmetsudeys.rotauygulama.data.ledger.LedgerStorage
import com.ahmetsudeys.rotauygulama.data.quote.QuoteStorage
import com.ahmetsudeys.rotauygulama.databinding.FragmentLedgerBinding
import com.ahmetsudeys.rotauygulama.databinding.ItemReportDebtorBinding
import com.google.android.material.chip.Chip
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LedgerFragment : Fragment() {

    private var _binding: FragmentLedgerBinding? = null
    private val binding: FragmentLedgerBinding
        get() = requireNotNull(_binding)

    private lateinit var adapter: LedgerAdapter
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var refreshToken: Int = 0

    private var allRows: List<LedgerCalculator.LedgerRow> = emptyList()
    private var currentQuery: String = ""

    private enum class BookFilter { ALL, DEBTORS, PAID }
    private var bookFilter: BookFilter = BookFilter.ALL

    private var recentMonths: List<LedgerCalculator.YearMonth> = emptyList()
    private var selectedMonth: LedgerCalculator.YearMonth? = null
    private val monthChipMap = HashMap<Int, LedgerCalculator.YearMonth>()

    private val money: NumberFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("tr-TR")).apply {
        currency = Currency.getInstance("TRY")
        maximumFractionDigits = 0
    }

    private val monthNames = arrayOf(
        "Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran",
        "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık"
    )
    private val monthShort = arrayOf(
        "Oca", "Şub", "Mar", "Nis", "May", "Haz",
        "Tem", "Ağu", "Eyl", "Eki", "Kas", "Ara"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLedgerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        adapter = LedgerAdapter(onRowClick = { row -> openDetail(row.customerId) })
        binding.recyclerLedger.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerLedger.adapter = adapter
        binding.recyclerLedger.setHasFixedSize(true)
        binding.recyclerLedger.itemAnimator = null

        // Top-right segmented switch between Defter and Rapor.
        binding.toggleGroup.check(R.id.btn_book)
        binding.toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val showReport = checkedId == R.id.btn_report
            binding.viewBook.isVisible = !showReport
            binding.viewReport.isVisible = showReport
        }

        binding.autoSearch.doAfterTextChanged { editable ->
            currentQuery = editable?.toString().orEmpty()
            applyFilter()
        }
        binding.autoSearch.setOnItemClickListener { parent, _, position, _ ->
            val name = parent.getItemAtPosition(position) as? String ?: return@setOnItemClickListener
            val row = allRows.firstOrNull { (it.customer.name ?: "") == name }
            if (row != null) {
                binding.autoSearch.setText("", false)
                currentQuery = ""
                openDetail(row.customerId)
            }
        }

        binding.chipGroupFilters.setOnCheckedStateChangeListener { _, checkedIds ->
            bookFilter = when (checkedIds.firstOrNull()) {
                R.id.chip_filter_debtors -> BookFilter.DEBTORS
                R.id.chip_filter_paid -> BookFilter.PAID
                else -> BookFilter.ALL
            }
            applyFilter()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val token = ++refreshToken
        val appCtx = requireContext().applicationContext
        ioExecutor.execute {
            // Keep only the last 3 months of detailed payments; older ones are folded into an
            // archived total so the store stays small and the app stays fast long-term.
            LedgerStorage.pruneOlderThan(appCtx, LedgerCalculator.retentionCutoffMillis())

            val customers = CustomerStorage.getCustomers(appCtx).sortedByDescending { it.createdAtMillis }
            val accounts = LedgerStorage.getAccounts(appCtx)
            val quotes = QuoteStorage.getQuotes(appCtx)
            val rows = LedgerCalculator.buildRows(customers, accounts, quotes)
            mainHandler.post {
                if (_binding == null || token != refreshToken) return@post
                allRows = rows
                bindSummary()
                setupSearchAdapter()
                applyFilter()
                buildReport()
            }
        }
    }

    private fun bindSummary() {
        val s = LedgerCalculator.summarize(allRows)
        binding.textTotalAgreed.text = money.format(s.totalAgreed)
        binding.textTotalCollected.text = money.format(s.totalCollected)
        binding.textTotalRemaining.text = money.format(s.totalRemaining)
    }

    private fun setupSearchAdapter() {
        val names = allRows.mapNotNull { it.customer.name?.takeIf { n -> n.isNotBlank() } }
        binding.autoSearch.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, names))
    }

    private fun applyFilter() {
        val q = currentQuery.trim()
        var list = when (bookFilter) {
            BookFilter.ALL -> allRows
            BookFilter.DEBTORS -> allRows.filter { it.remaining > 0.009 }
            BookFilter.PAID -> allRows.filter { it.isFullyPaid }
        }
        if (q.isNotBlank()) {
            list = list.filter { it.customer.name.orEmpty().contains(q, ignoreCase = true) }
        }
        binding.textEmpty.isVisible = list.isEmpty()
        binding.recyclerLedger.isVisible = list.isNotEmpty()
        adapter.submitList(list)
    }

    private fun openDetail(customerId: Long) {
        findNavController().navigate(
            R.id.action_ledgerFragment_to_ledgerDetailFragment,
            bundleOf("customerId" to customerId)
        )
    }

    // --- Report -----------------------------------------------------------

    private fun buildReport() {
        buildMonthChips()
        renderSelectedMonth()
        buildDebtors()
    }

    /** Rebuilds the 3-month selector. Recomputed from today so it advances as months pass. */
    private fun buildMonthChips() {
        recentMonths = LedgerCalculator.recentMonths(3) // newest first
        // Keep the current selection if it is still inside the window, else default to this month.
        if (selectedMonth == null || recentMonths.none { it.key == selectedMonth?.key }) {
            selectedMonth = recentMonths.firstOrNull()
        }

        val group = binding.chipGroupMonths
        group.setOnCheckedStateChangeListener(null)
        group.removeAllViews()
        monthChipMap.clear()

        recentMonths.forEach { ym ->
            val chip = Chip(requireContext()).apply {
                text = "${monthShort[(ym.month - 1).coerceIn(0, 11)]} ${ym.year}"
                isCheckable = true
                isClickable = true
                id = View.generateViewId()
                isChecked = ym.key == selectedMonth?.key
            }
            monthChipMap[chip.id] = ym
            group.addView(chip)
        }
        group.setOnCheckedStateChangeListener { _, checkedIds ->
            val ym = checkedIds.firstOrNull()?.let { monthChipMap[it] } ?: return@setOnCheckedStateChangeListener
            selectedMonth = ym
            renderSelectedMonth()
        }
    }

    /** Every report figure below the month selector is scoped to [selectedMonth]. */
    private fun renderSelectedMonth() {
        // Column chart in chronological order (oldest -> newest, left to right).
        val chrono = recentMonths.reversed()
        val columns = chrono.map { ym ->
            val total = LedgerCalculator.collectedInMonth(allRows, ym)
            ColumnChartView.Column(
                label = monthShort[(ym.month - 1).coerceIn(0, 11)],
                value = total.toFloat(),
                valueLabel = shortMoney(total),
                color = color(R.color.accent_blue),
                highlight = ym.key == selectedMonth?.key
            )
        }
        binding.chartMonthly.setColumns(columns)

        val ym = selectedMonth
        val monthLabel = ym?.let { "${monthNames[(it.month - 1).coerceIn(0, 11)]} ${it.year}" }.orEmpty()
        binding.textMethodMonthHint.text = getString(R.string.report_method_month_hint, monthLabel)
        binding.textOverviewMonthHint.text = getString(R.string.report_method_month_hint, monthLabel)

        val breakdown = ym?.let { LedgerCalculator.methodBreakdownInMonth(allRows, it) }
            ?: LedgerCalculator.MethodBreakdown(0.0, 0.0, 0.0)
        val ciro = ym?.let { LedgerCalculator.collectedInMonth(allRows, it) } ?: 0.0

        // Monthly ciro detail row
        binding.textMonthCiro.text = money.format(ciro)
        binding.textMonthCash.text = "${getString(R.string.payment_method_cash)} ${money.format(breakdown.cash)}"
        binding.textMonthCard.text = "${getString(R.string.payment_method_card)} ${money.format(breakdown.card)}"
        binding.textMonthTransfer.text = "${getString(R.string.payment_method_transfer)} ${money.format(breakdown.transfer)}"

        // Method distribution donut + legend (selected month)
        binding.chartMethods.setSlices(
            listOf(
                DonutChartView.Slice(breakdown.cash.toFloat(), color(R.color.accent_green)),
                DonutChartView.Slice(breakdown.card.toFloat(), color(R.color.accent_blue)),
                DonutChartView.Slice(breakdown.transfer.toFloat(), color(R.color.accent_amber))
            )
        )
        val legend = binding.containerMethodLegend
        legend.removeAllViews()
        addLegendRow(legend, R.color.accent_green, getString(R.string.payment_method_cash), breakdown.cash)
        addLegendRow(legend, R.color.accent_blue, getString(R.string.payment_method_card), breakdown.card)
        addLegendRow(legend, R.color.accent_amber, getString(R.string.payment_method_transfer), breakdown.transfer)

        // Overview stats (selected month)
        val stats = ym?.let { LedgerCalculator.monthStats(allRows, it) }
            ?: LedgerCalculator.MonthStats(0.0, 0, 0, 0.0)
        binding.textStatCollected.text = money.format(stats.collected)
        binding.textStatCount.text = stats.paymentCount.toString()
        binding.textStatPayers.text = stats.payingCustomers.toString()
        binding.textStatAvg.text = money.format(stats.average)
    }

    private fun addLegendRow(container: ViewGroup, colorRes: Int, label: String, amount: Double) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        val dot = View(requireContext()).apply {
            setBackgroundResource(R.drawable.bg_dot)
            backgroundTintList = ContextCompat.getColorStateList(requireContext(), colorRes)
            layoutParams = LinearLayout.LayoutParams(dp(11), dp(11))
        }
        val labelView = TextView(requireContext()).apply {
            text = label
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
        }
        val amountView = TextView(requireContext()).apply {
            text = money.format(amount)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        row.addView(dot)
        row.addView(labelView)
        row.addView(amountView)
        container.addView(row)
    }

    private fun buildDebtors() {
        val container = binding.containerDebtors
        container.removeAllViews()
        val debtors = LedgerCalculator.topDebtors(allRows, limit = 5)
        if (debtors.isEmpty()) {
            val hint = TextView(requireContext()).apply {
                text = getString(R.string.ledger_no_debtors)
                alpha = 0.7f
                textSize = 13f
                setPadding(0, dp(8), 0, dp(8))
            }
            container.addView(hint)
            return
        }
        debtors.forEachIndexed { index, row ->
            val item = ItemReportDebtorBinding.inflate(layoutInflater, container, false)
            item.textRank.text = (index + 1).toString()
            item.textName.text = row.customer.name?.takeIf { it.isNotBlank() } ?: "-"
            val phone = row.phone
            item.textPhone.isVisible = phone != null
            item.textPhone.text = phone.orEmpty()
            item.textRemaining.text = money.format(row.remaining)
            container.addView(item.root)
        }
    }

    // --- Helpers ----------------------------------------------------------

    private fun color(res: Int): Int = ContextCompat.getColor(requireContext(), res)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Compact currency for chart labels: ₺42B (bin), ₺1,2M (milyon). */
    private fun shortMoney(v: Double): String = when {
        v >= 1_000_000 -> "₺" + trim1(v / 1_000_000) + "M"
        v >= 1_000 -> "₺" + trim1(v / 1_000) + "B"
        v <= 0 -> "₺0"
        else -> "₺" + v.toInt()
    }

    private fun trim1(x: Double): String {
        val r = Math.round(x * 10) / 10.0
        return if (r % 1.0 == 0.0) r.toInt().toString() else r.toString().replace('.', ',')
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
    }
}
