package com.ahmetsudeys.rotauygulama.ui.ledger

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
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
import com.ahmetsudeys.rotauygulama.databinding.ItemReportBarBinding
import com.ahmetsudeys.rotauygulama.databinding.ItemReportDebtorBinding
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

    private val money: NumberFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("tr-TR")).apply {
        currency = Currency.getInstance("TRY")
        maximumFractionDigits = 0
    }

    private val monthNames = arrayOf(
        "Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran",
        "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık"
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

        // Typing filters the book list.
        binding.autoSearch.doAfterTextChanged { editable ->
            currentQuery = editable?.toString().orEmpty()
            applyFilter()
        }
        // Picking a name from the dropdown opens that customer's detail.
        binding.autoSearch.setOnItemClickListener { parent, _, position, _ ->
            val name = parent.getItemAtPosition(position) as? String ?: return@setOnItemClickListener
            val row = allRows.firstOrNull { (it.customer.name ?: "") == name }
            if (row != null) {
                binding.autoSearch.setText("", false)
                currentQuery = ""
                openDetail(row.customerId)
            }
        }

        binding.chipGroupTabs.setOnCheckedStateChangeListener { _, checkedIds ->
            val showReport = checkedIds.contains(R.id.chip_tab_report)
            binding.viewBook.isVisible = !showReport
            binding.viewReport.isVisible = showReport
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
        val dropdownAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, names)
        binding.autoSearch.setAdapter(dropdownAdapter)
    }

    private fun applyFilter() {
        val q = currentQuery.trim()
        val filtered = if (q.isBlank()) {
            allRows
        } else {
            allRows.filter { it.customer.name.orEmpty().contains(q, ignoreCase = true) }
        }
        binding.textEmpty.isVisible = filtered.isEmpty()
        binding.recyclerLedger.isVisible = filtered.isNotEmpty()
        adapter.submitList(filtered)
    }

    private fun openDetail(customerId: Long) {
        findNavController().navigate(
            R.id.action_ledgerFragment_to_ledgerDetailFragment,
            bundleOf("customerId" to customerId)
        )
    }

    // --- Report -----------------------------------------------------------

    private fun buildReport() {
        buildMonthly()
        buildMethods()
        buildDebtors()
    }

    private fun buildMonthly() {
        val container = binding.containerMonthly
        container.removeAllViews()
        val monthly = LedgerCalculator.monthlyRevenue(allRows)
        if (monthly.isEmpty()) {
            container.addView(emptyHint())
            return
        }
        val max = monthly.maxOf { it.total }.coerceAtLeast(1.0)
        monthly.take(12).forEach { m ->
            val row = ItemReportBarBinding.inflate(layoutInflater, container, false)
            row.textLabel.text = "${monthNames[(m.month - 1).coerceIn(0, 11)]} ${m.year}"
            row.textValue.text = money.format(m.total)
            setBarFraction(row, m.total / max)
            container.addView(row.root)
        }
    }

    private fun buildMethods() {
        val container = binding.containerMethods
        container.removeAllViews()
        val b = LedgerCalculator.methodBreakdown(allRows)
        if (b.total <= 0.0) {
            container.addView(emptyHint())
            return
        }
        val max = b.total.coerceAtLeast(1.0)
        addMethodRow(container, getString(R.string.payment_method_cash), b.cash, max)
        addMethodRow(container, getString(R.string.payment_method_card), b.card, max)
        addMethodRow(container, getString(R.string.payment_method_transfer), b.transfer, max)
    }

    private fun addMethodRow(container: ViewGroup, label: String, value: Double, max: Double) {
        val row = ItemReportBarBinding.inflate(layoutInflater, container, false)
        row.textLabel.text = label
        row.textValue.text = money.format(value)
        setBarFraction(row, value / max)
        container.addView(row.root)
    }

    private fun buildDebtors() {
        val container = binding.containerDebtors
        container.removeAllViews()
        val debtors = LedgerCalculator.topDebtors(allRows, limit = 5)
        if (debtors.isEmpty()) {
            val hint = emptyHint()
            hint.text = getString(R.string.ledger_no_debtors)
            container.addView(hint)
            return
        }
        debtors.forEach { row ->
            val item = ItemReportDebtorBinding.inflate(layoutInflater, container, false)
            item.textName.text = row.customer.name?.takeIf { it.isNotBlank() } ?: "-"
            item.textRemaining.text = money.format(row.remaining)
            container.addView(item.root)
        }
    }

    private fun setBarFraction(row: ItemReportBarBinding, fraction: Double) {
        val f = fraction.coerceIn(0.0, 1.0).toFloat()
        (row.barFill.layoutParams as android.widget.LinearLayout.LayoutParams).weight = f
        (row.barEmpty.layoutParams as android.widget.LinearLayout.LayoutParams).weight = 1f - f
        row.barFill.requestLayout()
        row.barEmpty.requestLayout()
    }

    private fun emptyHint(): android.widget.TextView {
        return android.widget.TextView(requireContext()).apply {
            text = getString(R.string.report_no_data)
            alpha = 0.7f
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }
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
