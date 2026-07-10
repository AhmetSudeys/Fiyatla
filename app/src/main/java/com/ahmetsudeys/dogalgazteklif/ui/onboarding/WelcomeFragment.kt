package com.ahmetsudeys.dogalgazteklif.ui.onboarding

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.ahmetsudeys.dogalgazteklif.R
import com.ahmetsudeys.dogalgazteklif.data.Prefs
import com.ahmetsudeys.dogalgazteklif.data.backup.BackupManager
import com.ahmetsudeys.dogalgazteklif.databinding.FragmentWelcomeBinding
import com.ahmetsudeys.dogalgazteklif.ui.util.setOnSingleClickListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class WelcomeFragment : Fragment() {

    private var _binding: FragmentWelcomeBinding? = null
    private val binding: FragmentWelcomeBinding
        get() = requireNotNull(_binding)

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var progressDialog: androidx.appcompat.app.AlertDialog? = null

    private val pickBackup =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) confirmAndRestore(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWelcomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val companyName = Prefs.getCompanyName(requireContext())
        binding.textWelcome.text = getString(R.string.welcome_with_company, companyName)

        Prefs.getCompanyLogoFile(requireContext())?.let { file ->
            binding.imageLogo.setImageURI(Uri.fromFile(file))
        }

        binding.buttonLogin.setOnClickListener {
            findNavController().navigate(R.id.action_welcomeFragment_to_mainShellFragment)
        }

        binding.buttonBackup.setOnSingleClickListener { startBackup() }
        binding.buttonRestore.setOnSingleClickListener {
            // Accept any file type; some providers (WhatsApp/Drive) report a generic MIME.
            pickBackup.launch(arrayOf("*/*"))
        }

        binding.textEditCompany.setOnSingleClickListener {
            findNavController().navigate(R.id.action_welcomeFragment_to_companySetupFragment)
        }
    }

    private fun startBackup() {
        showProgress(getString(R.string.backup_in_progress))
        ioExecutor.execute {
            val result = runCatching { BackupManager.buildBackup(requireContext()) }
            mainHandler.post {
                if (_binding == null) return@post
                dismissProgress()
                result.onSuccess { uri ->
                    BackupManager.startShare(requireContext(), uri, getString(R.string.backup_share_title))
                }.onFailure {
                    Toast.makeText(requireContext(), R.string.backup_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun confirmAndRestore(uri: Uri) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.restore_confirm_title)
            .setMessage(R.string.restore_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.restore_data) { _, _ -> performRestore(uri) }
            .show()
    }

    private fun performRestore(uri: Uri) {
        showProgress(getString(R.string.restore_in_progress))
        ioExecutor.execute {
            val result = runCatching { BackupManager.restore(requireContext(), uri) }
                .getOrDefault(BackupManager.RestoreResult.Error)
            mainHandler.post {
                if (_binding == null) return@post
                dismissProgress()
                when (result) {
                    BackupManager.RestoreResult.Success -> showRestoreSuccess()
                    BackupManager.RestoreResult.InvalidFile ->
                        Toast.makeText(requireContext(), R.string.restore_invalid, Toast.LENGTH_LONG).show()
                    BackupManager.RestoreResult.Error ->
                        Toast.makeText(requireContext(), R.string.restore_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showRestoreSuccess() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.restore_success)
            .setCancelable(false)
            .setPositiveButton(R.string.restart_now) { _, _ ->
                // Re-inflate so the restored company name/logo (and everything else) is picked up.
                if (isAdded) requireActivity().recreate()
            }
            .show()
    }

    private fun showProgress(message: String) {
        dismissProgress()
        progressDialog = MaterialAlertDialogBuilder(requireContext())
            .setView(
                android.widget.LinearLayout(requireContext()).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    val pad = (24 * resources.displayMetrics.density).toInt()
                    setPadding(pad, pad, pad, pad)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(android.widget.ProgressBar(requireContext()))
                    addView(
                        android.widget.TextView(requireContext()).apply {
                            text = message
                            val lp = android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            lp.marginStart = pad
                            layoutParams = lp
                        }
                    )
                }
            )
            .setCancelable(false)
            .show()
    }

    private fun dismissProgress() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dismissProgress()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
    }
}
