package com.ahmetsudeys.rotauygulama.ui.home

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.ahmetsudeys.rotauygulama.R
import com.ahmetsudeys.rotauygulama.data.Prefs
import com.ahmetsudeys.rotauygulama.data.quote.QuoteDraftStore
import com.ahmetsudeys.rotauygulama.data.quote.QuoteStorage
import com.ahmetsudeys.rotauygulama.databinding.FragmentHomeBinding
import com.ahmetsudeys.rotauygulama.ui.quotes.QuoteApproval
import com.ahmetsudeys.rotauygulama.ui.quotes.QuotesAdapter
import com.ahmetsudeys.rotauygulama.ui.shell.MainShellFragment
import com.ahmetsudeys.rotauygulama.ui.util.setOnSingleClickListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding: FragmentHomeBinding
        get() = requireNotNull(_binding)

    private lateinit var adapter: QuotesAdapter
    private var destinationListener: NavController.OnDestinationChangedListener? = null
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var refreshToken: Int = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val company = Prefs.getCompanyName(requireContext()).ifBlank { "-" }
        // Greeting removed from UI by request (kept company value for future use if needed)
        binding.textGreeting.text = ""

        binding.buttonNewQuote.setOnSingleClickListener {
            // Starting a brand-new quote should always be clean (no leftovers from edit / unfinished drafts).
            QuoteDraftStore.reset()
            findNavController().navigate(R.id.action_homeFragment_to_quote_nav_graph)
        }

        binding.buttonViewAll.setOnSingleClickListener {
            (parentFragment?.parentFragment as? MainShellFragment)?.selectTab(R.id.quotesFragment)
                ?: findNavController().navigate(R.id.quotesFragment)
        }

        adapter = QuotesAdapter(
            onRowClick = { record ->
                val args = Bundle().apply { putLong("createdAtMillis", record.createdAtMillis) }
                findNavController().navigate(R.id.action_homeFragment_to_quoteDetailFragment, args)
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
            onDeleteClick = { },
            showDelete = false
        )
        binding.recyclerLastQuotes.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerLastQuotes.adapter = adapter
        binding.recyclerLastQuotes.setHasFixedSize(true)
        binding.recyclerLastQuotes.itemAnimator = null

        // Make sure list state is always fresh when coming back from other screens (e.g. "Tümünü görüntüle")
        val listener = NavController.OnDestinationChangedListener { _, destination: NavDestination, _ ->
            if (destination.id == R.id.homeFragment) refresh()
        }
        findNavController().addOnDestinationChangedListener(listener)
        destinationListener = listener
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val token = ++refreshToken
        ioExecutor.execute {
            val list = QuoteStorage.getQuotes(requireContext())
                .sortedByDescending { it.createdAtMillis }
                .take(5)
            mainHandler.post {
                if (_binding == null || token != refreshToken) return@post
                binding.textEmptyLastQuotes.isVisible = list.isEmpty()
                binding.recyclerLastQuotes.isVisible = list.isNotEmpty()
                adapter.submitList(list)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        destinationListener?.let { findNavController().removeOnDestinationChangedListener(it) }
        destinationListener = null
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
    }
}


