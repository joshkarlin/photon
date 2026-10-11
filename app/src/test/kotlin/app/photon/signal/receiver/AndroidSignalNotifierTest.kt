package app.photon.signal.receiver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A delete only corrects a message notification that is still up. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AndroidSignalNotifierTest {

    private val notifier = AndroidSignalNotifier(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun onlyAPostedMessageNotificationCountsAsShowing() {
        notifier.showMessage("Alice", "hi", "alice")
        notifier.showIncomingCall("Bob", "bob", isVideo = false)

        assertTrue(notifier.isShowingMessage("alice"))
        assertFalse(notifier.isShowingMessage("bob"))

        notifier.cancelConversation("alice")
        assertFalse(notifier.isShowingMessage("alice"))
    }
}
