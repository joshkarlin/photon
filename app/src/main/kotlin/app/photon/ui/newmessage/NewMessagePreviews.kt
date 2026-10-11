package app.photon.ui.newmessage

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.photon.chat.KnownChat
import app.photon.chat.Person
import app.photon.chat.Reach
import app.photon.chat.RecipientIndex
import app.photon.data.KeyboardStyle
import app.photon.data.model.Conversation
import app.photon.data.model.Platform
import app.photon.data.names.LocalContact
import app.photon.ui.theme.PhotonTheme

/** New message's sample people and chats, for previews and tests. Numbers are Ofcom's drama range. */
internal object NewMessageSamples {
    private fun chat(platform: Platform, jid: String, name: String, ts: Long, phone: String?, group: Boolean = false) =
        KnownChat(platform, Conversation(jid, name, group, null, ts, 0, false, null, ts), phone)

    val moWhatsApp = chat(Platform.WHATSAPP, "447700900123@s.whatsapp.net", "Mo Salah", 1_900_000_300, "+447700900123")
    val moSms = chat(Platform.SMS, "+447700900123", "Mo Salah", 1_900_000_200, "+447700900123")
    val moe = chat(Platform.WHATSAPP, "447700900555@s.whatsapp.net", "Moe", 1_900_000_100, "+447700900555")
    val ramona = chat(Platform.SIGNAL, "00000000-0000-4000-8000-0000000000a1", "Ramona Flowers", 1_900_000_050, "+447700900789")
    val climbing = chat(Platform.WHATSAPP, "120363000000000001@g.us", "Monday climbing", 1_900_000_400, null, group = true)

    val contacts = listOf(
        LocalContact("Mo Salah", listOf("+44 7700 900123")),
        LocalContact("Monica Geller", listOf("+44 7700 900456")),
        LocalContact("Amos Burton", listOf("+44 7700 900987")),
    )

    /** Built when asked, so it reads numbers for the country set by then. */
    fun index() = RecipientIndex(contacts, listOf(moWhatsApp, moSms, moe, ramona, climbing))

    /** Mo Salah: chats on WhatsApp and SMS, so Send via asks. */
    fun mo(): Person = index().people.first { it.name == "Mo Salah" }

    /** Signal doesn't know anyone here; WhatsApp knows every number; SMS takes any. */
    val reach: suspend (Platform, String) -> Reach = { platform, phone ->
        when (platform) {
            Platform.WHATSAPP -> Reach.Reachable("${phone.filter(Char::isDigit)}@s.whatsapp.net", existing = false)
            Platform.SIGNAL -> Reach.Unreachable("not known to Signal")
            Platform.SMS -> Reach.Reachable(phone, existing = false)
        }
    }
}

private const val LP3_WIDTH_DP = 360
private const val LP3_HEIGHT_DP = 413

@Preview(name = "New message – matches", widthDp = LP3_WIDTH_DP, heightDp = LP3_HEIGHT_DP, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun NewMessageMatchesPreview() = PhotonTheme {
    NewMessageContent(NewMessageSamples.index(), KeyboardStyle.SYSTEM, NewMessageSamples.reach, { _, _ -> }, {}, initialQuery = "mo")
}

@Preview(name = "New message – LP3 keyboard", widthDp = LP3_WIDTH_DP, heightDp = LP3_HEIGHT_DP, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun NewMessageKeyboardPreview() = PhotonTheme {
    NewMessageContent(NewMessageSamples.index(), KeyboardStyle.LIGHT, NewMessageSamples.reach, { _, _ -> }, {}, initialQuery = "mo")
}

@Preview(name = "New message – Send via", widthDp = LP3_WIDTH_DP, heightDp = LP3_HEIGHT_DP, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun SendViaPreview() = PhotonTheme {
    SendViaPage(NewMessageSamples.mo(), NewMessageSamples.reach, { _, _ -> }, {})
}
