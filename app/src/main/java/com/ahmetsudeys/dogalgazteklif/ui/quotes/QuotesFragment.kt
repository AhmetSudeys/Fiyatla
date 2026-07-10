package com.ahmetsudeys.dogalgazteklif.ui.quotes

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import androidx.recyclerview.widget.LinearLayoutManager
import com.ahmetsudeys.dogalgazteklif.R
import com.ahmetsudeys.dogalgazteklif.data.quote.QuoteDraftStore
import com.ahmetsudeys.dogalgazteklif.data.quote.QuoteStorage
import com.ahmetsudeys.dogalgazteklif.databinding.FragmentQuotesBinding
import com.ahmetsudeys.dogalgazteklif.ui.util.setOnSingleClickListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class QuotesFragment : Fragment() {

    private var _binding: FragmentQuotesBinding? = null
    private val binding: FragmentQuotesBinding
        get() = requireNotNull(_binding)

    private lateinit var adapter: QuotesAdapter
    private var allQuotes: List<QuoteStorage.QuoteRecord> = emptyList()
    private var currentQuery: String = ""
    private var statusFilter: StatusFilter = StatusFilter.ALL
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var refreshToken: Int = 0
    private val filterHandler = Handler(Looper.getMainLooper())
    private var filterRunnable: Runnable? = null

    private enum class StatusFilter { ALL, PENDING, APPROVED, REJECTED }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQuotesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val popped = findNavController().popBackStack(R.id.homeFragment, false)
                    if (!popped) findNavController().navigate(R.id.homeFragment)
                }
            }
        )

        adapter = QuotesAdapter(
            onRowClick = { record ->
                val args = Bundle().apply { putLong("createdAtMillis", record.createdAtMillis) }
                findNavController().navigate(R.id.action_quotesFragment_to_quoteDetailFragment, args)
            },
            onStatusChange = { record, newStatus ->
                QuoteApproval.changeStatus(this, ioExecutor, mainHandler, record, newStatus) {
                    if (isAdded) refresh()
                }
            },
            onNoteClick = { record ->
                val note = record.note.orEmpty().trim()
                if (note.isBlank()) return@QuotesAdapter
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Not")
                    .setMessage(note)
                    .setPositiveButton("Tamam", null)
                    .show()
            },
            onDeleteClick = { record ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Silinsin mi?")
                    .setMessage("Emin misin? Bu işlem geri alınamaz.")
                    .setNegativeButton("Vazgeç", null)
                    .setPositiveButton("Sil") { _, _ ->
                        ioExecutor.execute {
                            QuoteStorage.deleteQuote(requireContext(), record.createdAtMillis)
                            mainHandler.post { if (isAdded) refresh() }
                        }
                    }
                    .show()
            },
            showDelete = true
        )

        binding.recyclerQuotes.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerQuotes.adapter = adapter
        binding.recyclerQuotes.setHasFixedSize(true)
        binding.recyclerQuotes.itemAnimator = null

        binding.editSearch.doAfterTextChanged { editable ->
            currentQuery = editable?.toString().orEmpty()
            scheduleApplyFilter()
        }

        binding.chipFilterAll.setOnClickListener {
            statusFilter = StatusFilter.ALL
            scheduleApplyFilter()
        }
        binding.chipFilterPending.setOnClickListener {
            statusFilter = StatusFilter.PENDING
            scheduleApplyFilter()
        }
        binding.chipFilterApproved.setOnClickListener {
            statusFilter = StatusFilter.APPROVED
            scheduleApplyFilter()
        }
        binding.chipFilterRejected.setOnClickListener {
            statusFilter = StatusFilter.REJECTED
            scheduleApplyFilter()
        }

        binding.buttonNewQuote.setOnSingleClickListener {
            QuoteDraftStore.reset()
            findNavController().navigate(
                R.id.action_quotesFragment_to_quote_nav_graph,
                null,
                navOptions {
                    anim {
                        enter = 0
                        exit = 0
                        popEnter = 0
                        popExit = 0
                    }
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val token = ++refreshToken
        ioExecutor.execute {
            val loaded = QuoteStorage.getQuotes(requireContext())
                .sortedByDescending { it.createdAtMillis }
            mainHandler.post {
                if (_binding == null || token != refreshToken) return@post
                allQuotes = loaded
                scheduleApplyFilter(immediate = true)
            }
        }
    }

    private fun scheduleApplyFilter(immediate: Boolean = false) {
        filterRunnable?.let { filterHandler.removeCallbacks(it) }
        val r = Runnable { applyFilterNow() }
        filterRunnable = r
        if (immediate) filterHandler.post(r) else filterHandler.postDelayed(r, 140L)
    }

    private fun applyFilterNow() {
        val q = currentQuery.trim()
        val base = when (statusFilter) {
            StatusFilter.ALL -> allQuotes
            StatusFilter.PENDING -> allQuotes.filter { it.status == com.ahmetsudeys.dogalgazteklif.data.quote.QuoteStatus.PENDING }
            StatusFilter.APPROVED -> allQuotes.filter { it.status == com.ahmetsudeys.dogalgazteklif.data.quote.QuoteStatus.APPROVED }
            StatusFilter.REJECTED -> allQuotes.filter { it.status == com.ahmetsudeys.dogalgazteklif.data.quote.QuoteStatus.REJECTED }
        }

        val filtered = if (q.isBlank()) base else base.filter { it.customerName.contains(q, ignoreCase = true) }
        binding.textEmpty.isVisible = filtered.isEmpty()
        binding.recyclerQuotes.isVisible = filtered.isNotEmpty()
        adapter.submitList(filtered)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        filterRunnable?.let { filterHandler.removeCallbacks(it) }
        filterRunnable = null
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
    }
}



