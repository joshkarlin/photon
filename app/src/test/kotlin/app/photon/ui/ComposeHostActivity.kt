package app.photon.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.ExternalResource
import org.robolectric.Shadows.shadowOf

/**
 * The compose rule hosts content in a bare [ComponentActivity]. Register it
 * with Robolectric rather than shipping ui-test-manifest's activity in an
 * APK, so UI tests run against both debug and release variants. Put it
 * outside the compose rule in a `RuleChain`.
 */
class RegisterComposeHostActivity : ExternalResource() {
    override fun before() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val host = ComponentName(app, ComponentActivity::class.java)
        shadowOf(app.packageManager).apply {
            addActivityIfNotPresent(host)
            addIntentFilterForActivity(
                host,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
            )
        }
    }
}
