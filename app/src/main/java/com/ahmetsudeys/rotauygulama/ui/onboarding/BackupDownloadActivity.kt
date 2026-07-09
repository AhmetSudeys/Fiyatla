package com.ahmetsudeys.rotauygulama.ui.onboarding

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.ahmetsudeys.rotauygulama.R
import com.ahmetsudeys.rotauygulama.data.backup.BackupManager
import java.io.ByteArrayOutputStream

/**
 * Invisible helper activity used as a "Telefona İndir" target inside the backup share sheet.
 *
 * It opens the system "create document" picker so the user can choose where to save the .xlsx
 * (Downloads / Documents / SD card ...), writes the backup there, then finishes. No storage
 * permission is needed since it goes through the Storage Access Framework.
 */
class BackupDownloadActivity : AppCompatActivity() {

    private val createDocument =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            )
        ) { uri ->
            if (uri != null) writeBackupTo(uri) else finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only launch once; a config change while the picker is open must not re-trigger it.
        if (savedInstanceState == null) {
            createDocument.launch(BackupManager.defaultBackupFileName())
        }
    }

    private fun writeBackupTo(uri: Uri) {
        val appContext = applicationContext
        Thread {
            val ok = runCatching {
                // Build the whole backup in memory first, then write it in one shot + flush. This
                // avoids ever leaving a half-written / 0-byte document if anything goes wrong midway.
                val bytes = ByteArrayOutputStream().use { bos ->
                    BackupManager.writeBackup(appContext, bos)
                    bos.toByteArray()
                }
                require(bytes.isNotEmpty()) { "Yedek içeriği boş" }
                appContext.contentResolver.openOutputStream(uri, "w")?.use { out ->
                    out.write(bytes)
                    out.flush()
                } ?: error("Yedek dosyası açılamadı")
            }.isSuccess
            runOnUiThread {
                Toast.makeText(
                    appContext,
                    if (ok) R.string.backup_download_success else R.string.backup_download_failed,
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }.start()
    }
}
