package app.photon.service

import app.photon.data.model.ContentTypes
import org.junit.Test
import kotlin.test.assertEquals

/** What notifications say of the 0.5.0 content types, on every platform. */
class NotificationPreviewTest {

    @Test
    fun viewOnceSaysWhatKindItIsNeverItsText() {
        assertEquals("view-once photo", notificationPreview("photo", ContentTypes.VIEW_ONCE))
        assertEquals("view-once video", notificationPreview("video", ContentTypes.VIEW_ONCE))
        assertEquals("view-once voice message", notificationPreview("voice", ContentTypes.VIEW_ONCE))
        assertEquals("view-once message", notificationPreview(null, ContentTypes.VIEW_ONCE))
        assertEquals(
            MessageNotificationText("Climbing", "Alice: view-once photo"),
            messageNotificationText("Climbing", true, "Alice", notificationPreview("photo", ContentTypes.VIEW_ONCE)),
        )
    }

    @Test
    fun unavailableAndLocationRead() {
        assertEquals("waiting for this message", notificationPreview(null, ContentTypes.UNAVAILABLE))
        assertEquals("Location · Buckingham Palace", notificationPreview("Buckingham Palace", ContentTypes.LOCATION))
        assertEquals("Location", notificationPreview(null, ContentTypes.LOCATION))
    }

    @Test
    fun otherTypesAreUnchanged() {
        assertEquals("hi", notificationPreview("hi", "text"))
        assertEquals("[ image ]", notificationPreview(null, "image"))
        assertEquals(null, notificationPreview(" ", "text"))
        assertEquals("Disappearing messages · 1 week", notificationPreview("Disappearing messages · 1 week", ContentTypes.SYSTEM))
    }
}
