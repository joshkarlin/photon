package app.photon.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.photon.data.model.Platform
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A new message alerts, even over the conversation's existing notification;
 * a correction of that notification (after a delete-for-everyone) replaces it
 * silently.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class MessageNotificationAlertTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val chat = "alice@s.whatsapp.net"

    private fun posted(): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        return shadowOf(manager).allNotifications.single()
    }

    private val Notification.alertsOnlyOnce get() = flags and Notification.FLAG_ONLY_ALERT_ONCE != 0

    @Test
    fun newMessagesAlertAndCorrectionsDoNot() {
        NotificationHelper.showMessageNotification(context, "Alice", "first", Platform.WHATSAPP, chat)
        assertFalse(posted().alertsOnlyOnce)

        NotificationHelper.showMessageNotification(context, "Alice", "second", Platform.WHATSAPP, chat)
        assertFalse(posted().alertsOnlyOnce, "a new message must alert over the existing notification")

        NotificationHelper.showMessageNotification(context, "Alice", "first", Platform.WHATSAPP, chat, update = true)
        assertTrue(posted().alertsOnlyOnce)
        assertEquals("first", posted().extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun theSharedFormatterTitlesGroupsWithTheGroupAndNamesTheSender() {
        assertEquals(
            MessageNotificationText("Climbing", "Alice: see you at 8"),
            messageNotificationText("Climbing", isGroup = true, senderName = "Alice", preview = "see you at 8"),
        )
        assertEquals(
            MessageNotificationText("Climbing", "Alice: [ image ]"),
            messageNotificationText("Climbing", true, "Alice", notificationPreview(null, "image")),
        )
        assertEquals(
            MessageNotificationText("Alice", "hi"),
            messageNotificationText("Alice", isGroup = false, senderName = "Alice", preview = "hi"),
        )
        assertEquals(null, notificationPreview("", "text"))
    }
}
