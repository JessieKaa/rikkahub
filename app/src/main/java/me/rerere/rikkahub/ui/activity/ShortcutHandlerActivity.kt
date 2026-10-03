package me.rerere.rikkahub.ui.activity

import android.Manifest
import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.RouteActivity
import java.io.File

/**
 * Handles the `rikkahub://shortcut` custom URI and the camera launcher shortcut.
 *
 * The captured image is handed back to [RouteActivity] via ACTION_SEND, so the root worker can
 * normalize the destination to the lawful family chat. This activity never selects an assistant
 * and only validates that a usable content URI was produced before forwarding it.
 */
class ShortcutHandlerActivity : ComponentActivity() {
    private var photoURI: Uri? = null
    private var capturedFile: File? = null

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            launchCamera()
        } else {
            finish()
        }
    }

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = photoURI
        if (success && uri != null && capturedFile?.exists() == true) {
            val intent = Intent(this, RouteActivity::class.java).apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, uri.toString())
                // Preserve the read grant for the FileProvider content URI.
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("shortcut_camera_image", uri)
            }
            startActivity(intent)
        }
        photoURI = null
        capturedFile = null
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun launchCamera() {
        val imageFile = File(cacheDir, "shortcut_camera_image.jpg")
        val uri = runCatching {
            FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", imageFile)
        }.getOrNull()
        if (uri == null || uri.scheme != ContentResolver.SCHEME_CONTENT) {
            finish()
            return
        }
        capturedFile = imageFile
        photoURI = uri
        takePictureLauncher.launch(uri)
    }
}
