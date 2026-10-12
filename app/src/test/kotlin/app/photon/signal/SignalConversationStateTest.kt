package app.photon.signal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.photon.data.model.ContentTypes
import app.photon.service.notificationPreview
import app.photon.signal.db.SignalMessageDatabase
import app.photon.signal.db.StorageMute
import app.photon.signal.db.applyStorageManifest
import app.photon.signal.db.getConversationWithContact
import app.photon.signal.db.getConversationsWithContacts
import app.photon.signal.db.insertMessage
import app.photon.signal.db.updateDmTimer
import app.photon.signal.db.upsertConversation
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * What the Signal backend hands the UI about a chat: its archive state and
 * timer survive name resolution, and a notification names view-once media.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SignalConversationStateTest {

    private val self = "00000000-0000-4000-8000-00000000000a"
    private val mum = "00000000-0000-4000-8000-0000000000a1"

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = SignalMessageDatabase(context)
    private val names = SignalNames(db, { null }) { self }

    @After
    fun tearDown() = db.close()

    @Test
    fun aResolvedConversationKeepsItsArchiveAndTimer() {
        db.upsertConversation(jid = mum, name = null, isGroup = false)
        db.insertMessage("${mum}_1_a", mum, mum, 1, "text", "hi")
        db.updateDmTimer(mum, 1) { ChatTimer(3_600, 2) }
        db.applyStorageManifest(1, emptyList(), listOf(StorageMute("s1", mum, 0, archived = true)))

        val listed = db.getConversationsWithContacts().map { (c, contact) -> names.resolve(c, contact) }.single()
        val single = db.getConversationWithContact(mum)!!.let { (c, contact) -> names.resolve(c, contact) }

        for (conversation in listOf(listed, single)) {
            assertEquals(true, conversation.isArchived)
            assertEquals(3_600, conversation.disappearingSeconds)
            assertEquals("Signal contact", conversation.name)
        }
    }

    @Test
    fun aViewOnceNotificationNamesItsKind() {
        assertEquals("view-once photo", notificationPreview("photo", ContentTypes.VIEW_ONCE))
        assertEquals("view-once video", notificationPreview("video", ContentTypes.VIEW_ONCE))
        assertEquals("view-once voice message", notificationPreview("voice", ContentTypes.VIEW_ONCE))
        assertEquals("hi", notificationPreview("hi", "text"))
        assertEquals("[ image ]", notificationPreview(null, "image"))
    }
}
