package app.photon

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import app.photon.data.PhotonPreferences
import app.photon.data.ScrollDial
import app.photon.nav.ChatDeepLink
import app.photon.nav.PhotonNavGraph
import app.photon.service.PhotonService
import app.photon.ui.shared.ScrollDialEvent
import app.photon.ui.shared.ScrollDialState
import app.photon.ui.theme.PhotonTheme
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* results don't need handling — permissions are checked at point of use */ }

    private val deepLink = mutableStateOf<ChatDeepLink?>(null)

    // Cached so dispatchKeyEvent never waits on DataStore.
    @Volatile
    private var scrollDial = ScrollDial.DEFAULT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated activity has already handled its launch intent.
        if (savedInstanceState == null) deepLink.value = ChatDeepLink.from(intent)
        lifecycleScope.launch {
            PhotonPreferences(this@MainActivity).scrollDial.collect { scrollDial = it }
        }
        startPhotonService()
        requestPermissionsIfNeeded()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(Color.BLACK),
        )
        setContent {
            PhotonTheme {
                val navController = rememberNavController()
                PhotonNavGraph(
                    navController = navController,
                    deepLink = deepLink.value,
                    onDeepLinkHandled = { deepLink.value = null },
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LightThemeTokens.colors.background)
                        .systemBarsPadding(),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ChatDeepLink.from(intent)?.let { deepLink.value = it }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // LP3 scroll dial: Pixart pat9126ja → WHEEL_CW (318) / WHEEL_CCW (317).
        // With the dial OFF the events fall through to the system, which
        // LightOS uses for screen brightness.
        val dial = scrollDial
        if (dial.consumesDial && (event.keyCode == WHEEL_CW || event.keyCode == WHEEL_CCW)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val direction = if (event.keyCode == WHEEL_CW) 1 else -1 // CW = scroll down
                ScrollDialState.emit(ScrollDialEvent(direction, dial))
            }
            return true
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
        val needed = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private companion object {
        const val WHEEL_CCW = 317
        const val WHEEL_CW = 318
    }
}
