package app.photon

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import app.photon.ui.shared.ScrollDialState
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import app.photon.nav.PhotonNavGraph
import app.photon.service.PhotonService
import app.photon.ui.theme.PhotonTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* results don't need handling — permissions are checked at point of use */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startPhotonService()
        requestPermissionsIfNeeded()
        enableEdgeToEdge()
        setContent {
            PhotonTheme {
                val navController = rememberNavController()
                PhotonNavGraph(
                    navController = navController,
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding(),
                )
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // LP3 scroll dial: Pixart pat9126ja → WHEEL_CW (318) / WHEEL_CCW (317)
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                318 -> { ScrollDialState.emit(1f); return true }  // CW = scroll down
                317 -> { ScrollDialState.emit(-1f); return true } // CCW = scroll up
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                317, 318 -> return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun startPhotonService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, PhotonService::class.java),
        )
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_SMS)
        }
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.SEND_SMS)
        }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_CONTACTS)
        }
        if (checkSelfPermission(Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.WRITE_CONTACTS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
