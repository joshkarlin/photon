package app.photon.ui.signal

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.photon.signal.store.PhotonIdentityKeyStore
import app.photon.signal.store.SignalProtocolDatabase
import app.photon.ui.RegisterComposeHostActivity
import app.photon.ui.theme.PhotonTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A changed Signal safety number: the notice under the title, and its review page. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h413dp-xxhdpi")
class SignalIdentityNoticeTest {
    private val rule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(RegisterComposeHostActivity()).around(rule)

    private lateinit var db: SignalProtocolDatabase
    private lateinit var store: PhotonIdentityKeyStore
    private val mum = SignalProtocolAddress("00000000-0000-4000-8000-000000000001", 1)

    @Before fun setUp() {
        db = SignalProtocolDatabase(ApplicationProvider.getApplicationContext())
        db.putState("aci", "00000000-0000-4000-8000-000000000002".toByteArray())
        db.putState("identity_key_pair", IdentityKeyPair.generate().serialize())
        db.putStateInt("registration_id", 123)
        store = PhotonIdentityKeyStore(db) { 100_000L }
        store.saveIdentity(mum, IdentityKeyPair.generate().publicKey)
        store.saveIdentity(mum, IdentityKeyPair.generate().publicKey)
        rule.setContent { PhotonTheme { SignalIdentityNotice(store, mapOf(mum.name to "Mum")) } }
    }

    @After fun tearDown() { db.close() }

    private fun openReview() {
        rule.onNodeWithText("Review").performClick()
        rule.onNodeWithText("Safety Number").assertExists()
    }

    @Test
    fun theReviewIsAPageWithBackAndAcceptInItsBottomBar() {
        rule.onNodeWithText("Safety number changed: Mum").assertExists()
        openReview()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onAllNodesWithText("Safety Number").assertCountEquals(0)
        assertTrue(store.getRecord(mum.name)!!.changePending, "Back accepts nothing")
    }

    @Test
    fun acceptingClearsTheNotice() {
        openReview()
        rule.onNodeWithText("ACCEPT CHANGE").performClick()
        rule.waitUntil(5_000) { !store.getRecord(mum.name)!!.changePending }
        assertFalse(store.getRecord(mum.name)!!.changePending)
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Safety number changed: Mum").fetchSemanticsNodes().isEmpty() }
    }
}
