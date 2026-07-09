package com.ahmetsudeys.rotauygulama.ui.ledger

import android.app.DatePickerDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.ahmetsudeys.rotauygulama.R
import com.ahmetsudeys.rotauygulama.data.customer.CustomerStorage
import com.ahmetsudeys.rotauygulama.data.ledger.LedgerCalculator
import com.ahmetsudeys.rotauygulama.data.ledger.LedgerStorage
import com.ahmetsudeys.rotauygulama.data.quote.QuoteStorage
import com.ahmetsudeys.rotauygulama.databinding.BottomsheetPaymentBinding
import com.ahmetsudeys.rotauygulama.databinding.DialogEditAgreementBinding
import com.ahmetsudeys.rotauygulama.databinding.FragmentLedgerDetailBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Currency
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LedgerDetailFragment : Fragment() {

    private var _binding: FragmentLedgerDetailBinding? = null
    private val binding: FragmentLedgerDetailBinding
        get() = requireNotNull(_binding)

    private lateinit var paymentsAdapter: PaymentsAdapter
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var refreshToken: Int = 0

    private var customerId: Long = 0L
    private var currentRow: LedgerCalculator.LedgerRow? = null

    private val money: NumberFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("tr-TR")).apply {
        currency = Currency.getInstance("TRY")
        maximumFractionDigits = 0
    }
    private val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale("tr", "TR"))
    private val longDateFmt = SimpleDateFormat("d MMMM yyyy", Locale("tr", "TR"))

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLedgerDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        customerId = requireArguments().getLong("customerId", 0L)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        paymentsAdapter = PaymentsAdapter(onDelete = { payment -> confirmDeletePayment(payment) })
        binding.recyclerPayments.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerPayments.adapter = paymentsAdapter
        binding.recyclerPayments.itemAnimator = null

        binding.buttonAddPayment.setOnClickListener { showPaymentSheet() }
        binding.buttonEditAgreement.setOnClickListener { showEditAgreementDialog() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val token = ++refreshToken
        val appCtx = requireContext().applicationContext
        ioExecutor.execute {
            val customer = CustomerStorage.getCustomers(appCtx).firstOrNull { it.createdAtMillis == customerId }
            val account = LedgerStorage.getAccount(appCtx, customerId)
            val quotes = QuoteStorage.getQuotes(appCtx)
            val row = when {
                customer != null -> LedgerCalculator.buildRow(customer, account, quotes)
                account != null -> LedgerCalculator.buildOrphanRow(account) // kept "silinmiş müşteri" record
                else -> null
            }
            mainHandler.post {
                if (_binding == null || token != refreshToken) return@post
                if (row == null) {
                    // Neither a customer nor a kept ledger record; nothing to show.
                    findNavController().navigateUp()
                    return@post
                }
                currentRow = row
                bindRow(row)
            }
        }
    }

    private fun bindRow(row: LedgerCalculator.LedgerRow) {
        val ctx = requireContext()
        binding.textName.text = row.customer.name?.takeIf { it.isNotBlank() } ?: "-"
        binding.textAgreed.text = money.format(row.agreedAmount)
        binding.textCollected.text = money.format(row.collected)
        binding.textRemaining.text = money.format(row.remaining)

        // Phone: auto-filled from the customer record when present, otherwise a subtle placeholder.
        binding.textPhone.text = row.phone ?: ctx.getString(R.string.ledger_no_phone)

        val dateMillis = row.agreedDateMillis
        binding.textDate.text = if (dateMillis != null) {
            ctx.getString(R.string.ledger_agreed_date, dateFmt.format(Date(dateMillis)))
        } else {
            ctx.getString(R.string.ledger_no_date)
        }

        val dueMillis = row.dueDateMillis
        binding.textDue.text = if (dueMillis != null) {
            ctx.getString(R.string.ledger_due_date, dateFmt.format(Date(dueMillis)))
        } else {
            ctx.getString(R.string.ledger_no_due)
        }

        when {
            row.isDeletedCustomer -> {
                binding.badgeStatus.isVisible = true
                binding.badgeStatus.text = ctx.getString(R.string.ledger_deleted_customer)
                binding.badgeStatus.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                binding.badgeStatus.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.outline_light)
            }
            row.isOverdue -> {
                binding.badgeStatus.isVisible = true
                binding.badgeStatus.text = ctx.getString(R.string.ledger_overdue)
                binding.badgeStatus.setTextColor(ContextCompat.getColor(ctx, R.color.error_red))
                binding.badgeStatus.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.error_red_soft)
            }
            row.isFullyPaid -> {
                binding.badgeStatus.isVisible = true
                binding.badgeStatus.text = ctx.getString(R.string.ledger_paid)
                binding.badgeStatus.setTextColor(ContextCompat.getColor(ctx, R.color.success_green))
                binding.badgeStatus.backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.success_green_soft)
            }
            else -> binding.badgeStatus.isVisible = false
        }

        val archived = row.account?.archivedCollected ?: 0.0
        binding.textArchived.isVisible = archived > 0.0
        if (archived > 0.0) {
            binding.textArchived.text = ctx.getString(R.string.ledger_archived_note, money.format(archived))
        }

        val payments = row.account?.payments.orEmpty().sortedByDescending { it.dateMillis }
        binding.textEmptyPayments.isVisible = payments.isEmpty() && archived <= 0.0
        binding.recyclerPayments.isVisible = payments.isNotEmpty()
        paymentsAdapter.submitList(payments)
    }

    // --- Add payment ------------------------------------------------------

    private fun showPaymentSheet() {
        val dialog = MaterialAlertDialogBuilder(requireContext()).create()
        val sheet = BottomsheetPaymentBinding.inflate(layoutInflater)
        dialog.setView(sheet.root)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        // Default to today (normalised to noon so the picker and display always agree).
        var selectedDate = todayNoonMillis()
        fun updateDateButton() {
            sheet.buttonDate.text = longDateFmt.format(Date(selectedDate))
        }
        updateDateButton()

        sheet.buttonDate.setOnClickListener {
            pickDate(selectedDate) { picked ->
                selectedDate = picked
                updateDateButton()
            }
        }

        sheet.buttonSave.setOnClickListener {
            val amount = parseAmount(sheet.editAmount.text?.toString())
            if (amount == null || amount <= 0.0) {
                sheet.inputAmount.error = getString(R.string.error_payment_amount_required)
                return@setOnClickListener
            }
            sheet.inputAmount.error = null

            val method = when (sheet.chipGroupMethod.checkedChipId) {
                R.id.chip_card -> LedgerStorage.PaymentMethod.CARD
                R.id.chip_transfer -> LedgerStorage.PaymentMethod.TRANSFER
                else -> LedgerStorage.PaymentMethod.CASH
            }
            val note = sheet.editNote.text?.toString().orEmpty().trim().takeIf { it.isNotBlank() }
            val payment = LedgerStorage.Payment(
                id = System.currentTimeMillis(),
                amount = amount,
                method = method,
                dateMillis = selectedDate,
                note = note
            )

            val appCtx = requireContext().applicationContext
            ioExecutor.execute {
                LedgerStorage.addPayment(appCtx, customerId, payment)
                mainHandler.post {
                    if (!isAdded) return@post
                    dialog.dismiss()
                    Toast.makeText(requireContext(), R.string.payment_added, Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
        }

        dialog.show()
    }

    private fun confirmDeletePayment(payment: LedgerStorage.Payment) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.payment_delete_title)
            .setMessage(R.string.payment_delete_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val appCtx = requireContext().applicationContext
                ioExecutor.execute {
                    LedgerStorage.deletePayment(appCtx, customerId, payment.id)
                    mainHandler.post {
                        if (!isAdded) return@post
                        Toast.makeText(requireContext(), R.string.payment_deleted, Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
            }
            .show()
    }

    // --- Edit agreement ---------------------------------------------------

    private fun showEditAgreementDialog() {
        val row = currentRow ?: return
        val content = DialogEditAgreementBinding.inflate(layoutInflater)

        // Pre-fill with the effective agreed amount so the user can tweak it.
        if (row.agreedAmount > 0.0) {
            content.editAmount.setText(trimAmount(row.agreedAmount))
        }
        var agreementDate: Long? = row.agreedDateMillis
        var dueDate: Long? = row.dueDateMillis

        fun updateButtons() {
            content.buttonDate.text = agreementDate?.let {
                getString(R.string.ledger_agreed_date_pick) + ": " + dateFmt.format(Date(it))
            } ?: getString(R.string.ledger_agreed_date_pick)
            content.buttonDueDate.text = dueDate?.let {
                getString(R.string.ledger_due_date_pick) + ": " + dateFmt.format(Date(it))
            } ?: getString(R.string.ledger_due_date_pick)
            content.buttonClearDue.isVisible = dueDate != null
        }
        updateButtons()

        content.buttonDate.setOnClickListener {
            pickDate(agreementDate ?: System.currentTimeMillis()) { picked ->
                agreementDate = picked
                updateButtons()
            }
        }
        content.buttonDueDate.setOnClickListener {
            pickDate(dueDate ?: System.currentTimeMillis()) { picked ->
                dueDate = picked
                updateButtons()
            }
        }
        content.buttonClearDue.setOnClickListener {
            dueDate = null
            updateButtons()
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ledger_edit_agreement)
            .setView(content.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val amount = parseAmount(content.editAmount.text?.toString())
                val finalAgreement = agreementDate
                val finalDue = dueDate
                val appCtx = requireContext().applicationContext
                ioExecutor.execute {
                    LedgerStorage.setAgreement(appCtx, customerId, amount, finalAgreement, finalDue)
                    mainHandler.post {
                        if (!isAdded) return@post
                        Toast.makeText(requireContext(), R.string.updated, Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
            }
            .show()
    }

    // --- Helpers ----------------------------------------------------------

    private fun todayNoonMillis(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun pickDate(initialMillis: Long, onPicked: (Long) -> Unit) {
        val cal = Calendar.getInstance().apply { timeInMillis = initialMillis }
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                val picked = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                    set(Calendar.HOUR_OF_DAY, 12)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                onPicked(picked.timeInMillis)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    /** Accepts both "1.234,56" (tr) and "1234.56" inputs. */
    private fun parseAmount(raw: String?): Double? {
        val s = raw?.trim().orEmpty()
        if (s.isBlank()) return null
        val normalized = when {
            s.contains(',') -> s.replace(".", "").replace(',', '.')
            else -> s
        }
        return normalized.toDoubleOrNull()
    }

    private fun trimAmount(value: Double): String {
        return if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
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
