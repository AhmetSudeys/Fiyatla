package com.ahmetsudeys.rotauygulama.ui.shell

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.ahmetsudeys.rotauygulama.R
import com.ahmetsudeys.rotauygulama.databinding.FragmentMainShellBinding

class MainShellFragment : Fragment() {

    private var _binding: FragmentMainShellBinding? = null
    private val binding: FragmentMainShellBinding
        get() = requireNotNull(_binding)

    private var isNavSetupDone: Boolean = false
    private var lastBackPressAt: Long = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainShellBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupNavIfReady()

        // Handle system back inside the app:
        // - First try to navigate back within the bottom-nav graph
        // - If already at root (e.g. Home), require double-press to exit the app
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val host = (childFragmentManager.findFragmentById(R.id.main_nav_host_fragment) as? NavHostFragment)
                    val nav = host?.navController

                    if (nav != null && nav.popBackStack()) return

                    val now = System.currentTimeMillis()
                    if (now - lastBackPressAt <= 2000L) {
                        requireActivity().finish()
                    } else {
                        lastBackPressAt = now
                        Toast.makeText(requireContext(), getString(R.string.press_back_again_to_exit), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    fun selectTab(menuItemId: Int) {
        if (_binding == null) return
        binding.bottomNavigation.selectedItemId = menuItemId
    }

    private fun setupNavIfReady() {
        if (isNavSetupDone || _binding == null) return

        val host = (childFragmentManager.findFragmentById(R.id.main_nav_host_fragment) as? NavHostFragment)
            ?: run {
                childFragmentManager.executePendingTransactions()
                childFragmentManager.findFragmentById(R.id.main_nav_host_fragment) as? NavHostFragment
            }

        if (host == null) {
            view?.post { setupNavIfReady() }
            return
        }

        val navController = host.navController
        binding.bottomNavigation.setupWithNavController(navController)

        // Tabs always show their own root screen. Switching to another tab (or reselecting the
        // current one) pops any nested screen such as a quote detail, so the content and the
        // highlighted tab never get out of sync.
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val startId = navController.graph.startDestinationId
            val options = NavOptions.Builder()
                .setLaunchSingleTop(true)
                .setPopUpTo(startId, item.itemId == startId, false)
                .setRestoreState(false)
                .build()
            try {
                navController.navigate(item.itemId, null, options)
                true
            } catch (_: Exception) {
                false
            }
        }
        binding.bottomNavigation.setOnItemReselectedListener { item ->
            navController.popBackStack(item.itemId, false)
        }

        // Hide bottom nav during quote creation flow (full-screen experience)
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val shouldShowBottomNav = when (destination.id) {
                R.id.quoteCustomerFragment,
                R.id.quoteOperationSelectFragment,
                R.id.quoteMaterialsFragment,
                R.id.quoteExtrasFragment,
                R.id.quoteSummaryFragment,
                R.id.quoteOutputFragment,
                R.id.ledgerFragment,
                R.id.ledgerDetailFragment -> false
                else -> true
            }
            binding.bottomNavigation.visibility = if (shouldShowBottomNav) View.VISIBLE else View.GONE
        }

        isNavSetupDone = true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        isNavSetupDone = false
    }
}


