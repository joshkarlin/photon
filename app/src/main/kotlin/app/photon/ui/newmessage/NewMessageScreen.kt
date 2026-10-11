package app.photon.ui.newmessage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import app.photon.chat.KnownChat
import app.photon.chat.Person
import app.photon.chat.Reach
import app.photon.chat.RecipientIndex
import app.photon.chat.RecipientMatch
import app.photon.chat.SendViaOption
import app.photon.chat.directChat
import app.photon.chat.groupDetail
import app.photon.chat.personDetail
import app.photon.chat.reachOn
import app.photon.chat.sendViaDetail
import app.photon.data.KeyboardStyle
import app.photon.data.PhotonPreferences
import app.photon.data.model.Conversation
import app.photon.data.model.Platform
import app.photon.data.names.DisplayNames
import app.photon.data.names.LocalContact
import app.photon.data.names.LocalContacts
import app.photon.service.Sessions
import app.photon.ui.shared.ScrollDialEffect
import app.photon.ui.shared.components.WithoutSystemKeyboard
import app.photon.ui.shared.components.backButton
import app.photon.ui.shared.components.rememberLightKeyboard
import app.photon.ui.shared.listDialTarget
import app.photon.ui.theme.PhotonColors
import app.photon.ui.theme.lightTextStyle
import com.thelightphone.lp3Keyboard.ui.SpecialKey
import com.thelightphone.lp3Keyboard.ui.layout.EnShared
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3KeyboardViewModel
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.designVerticalPxToDp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * New message: a "To" field that finds people and groups as you type
 * ([RecipientIndex]), over the LP3 contacts and every platform's chats.
 * A group opens; a person opens their chat when they have one on one
 * platform only, else "Send via" asks which platform to write on.
 *
 * Nothing typed shows nothing: the chat list behind this already shows the
 * recent chats, Back is one press away, and on the LP3's square screen the
 * space is the keyboard's while the name is typed.
 *
 * @param onOpenChat opens a platform's chat, new or existing
 */
@Composable
fun NewMessageScreen(onOpenChat: (Platform, String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { PhotonPreferences(context) }
    // Null until the setting loads, so no keyboard flashes first.
    val keyboard by remember(prefs) { prefs.keyboardStyle }.collectAsState(initial = null)
    val localContacts = remember(context) { LocalContacts.get(context) }

    // The LP3 contacts, read again whenever the address book changes.
    val contacts by produceState<List<LocalContact>?>(null, localContacts) {
        localContacts.changes.collect { value = withContext(Dispatchers.IO) { localContacts.all() } }
    }
    // Every platform's chats; a platform whose session isn't running has none.
    val lists = Platform.entries.map { platform ->
        platform to (Sessions.backend(platform)?.conversations ?: notRunning).collectAsState().value
    }
    val chats by produceState<List<KnownChat>?>(null, lists) {
        value = withContext(Dispatchers.IO) { knownChats(lists) }
    }
    val index by produceState<RecipientIndex?>(null, contacts, chats) {
        val c = contacts ?: return@produceState
        val k = chats ?: return@produceState
        value = withContext(Dispatchers.Default) { RecipientIndex(c, k) }
    }

    NewMessageContent(
        index = index,
        keyboard = keyboard,
        reach = { platform, phone -> reachThrough(platform, phone) },
        onOpenChat = onOpenChat,
        onBack = onBack,
    )
}

/** Each platform's listed chats, with the number each DM is with when the platform knows it. */
private fun knownChats(lists: List<Pair<Platform, List<Conversation>?>>): List<KnownChat> =
    lists.flatMap { (platform, conversations) ->
        val backend = Sessions.backend(platform)
        conversations.orEmpty().map { conversation ->
            val phone = if (conversation.isGroup) null else runCatching { backend?.contactPhone(conversation.jid) }.getOrNull()
            KnownChat(platform, conversation, phone)
        }
    }

private suspend fun reachThrough(platform: Platform, phone: String): Reach {
    val backend = Sessions.backend(platform) ?: return Reach.Unreachable(NOT_RUNNING)
    return try {
        backend.reach(phone)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // The contract is never to throw; a page must not crash on it.
        Reach.Unreachable("couldn't check ${platform.label}")
    }
}

// Stable, so a list whose session hasn't started doesn't resubscribe.
private val notRunning: StateFlow<List<Conversation>?> = MutableStateFlow(emptyList())

/**
 * [NewMessageScreen] over a ready [index] (null while it loads), for tests
 * and previews. [initialQuery] starts the field with text.
 */
@Composable
internal fun NewMessageContent(
    index: RecipientIndex?,
    keyboard: KeyboardStyle?,
    reach: suspend (Platform, String) -> Reach,
    onOpenChat: (Platform, String) -> Unit,
    onBack: () -> Unit,
    initialQuery: String = "",
) {
    val query = rememberSaveable(saver = TextFieldState.Saver) { TextFieldState(initialQuery) }
    var sendVia by remember { mutableStateOf<Person?>(null) }
    // The keyboard comes up on opening, not on coming back from Send via,
    // where the matches were being read.
    var keyboardOnOpen by rememberSaveable { mutableStateOf(true) }
    val listState = rememberLazyListState()

    val chosen = sendVia
    if (chosen != null) {
        SendViaPage(
            person = chosen,
            reach = reach,
            onOpen = onOpenChat,
            onBack = { sendVia = null },
        )
        return
    }

    fun pick(person: Person) {
        val only = directChat(person)
        if (only != null) onOpenChat(only.platform, only.conversation.jid) else sendVia = person
    }

    SearchPage(
        query = query,
        index = index,
        keyboard = keyboard,
        listState = listState,
        keyboardOnOpen = keyboardOnOpen,
        onKeyboardShown = { keyboardOnOpen = false },
        onPick = { match ->
            when (match) {
                is RecipientMatch.Number -> index?.personFor(match.e164)?.let(::pick)
                is RecipientMatch.PersonMatch -> pick(match.person)
                is RecipientMatch.GroupMatch -> onOpenChat(match.chat.platform, match.chat.conversation.jid)
            }
        },
        onBack = onBack,
    )
}

/**
 * The "To" field over its matches, as LightOS searches: the keyboard is up
 * at once, matches follow every key, and Enter puts the keyboard away so
 * they can be read and picked; tapping the field brings it back. The
 * bottom bar's keypad switches to number entry.
 */
@Composable
private fun SearchPage(
    query: TextFieldState,
    index: RecipientIndex?,
    keyboard: KeyboardStyle?,
    listState: LazyListState,
    keyboardOnOpen: Boolean,
    onKeyboardShown: () -> Unit,
    onPick: (RecipientMatch) -> Unit,
    onBack: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var fieldFocused by remember { mutableStateOf(false) }
    // SYSTEM: whether the field asks for the phone keyboard. LIGHT reads the
    // LP3 keyboard's own layout instead, which its 123 key also changes.
    var systemNumbers by rememberSaveable { mutableStateOf(false) }

    val dismissKeyboard = {
        focusManager.clearFocus()
        keyboardController?.hide()
        Unit
    }
    val lightKeyboard = if (keyboard == KeyboardStyle.LIGHT) rememberLightKeyboard(query, onReturn = dismissKeyboard) else null
    val lightLayout = lightKeyboard?.layoutFlow?.collectAsState()?.value
    val numbers = if (lightKeyboard != null) lightLayout is EnShared.NumberLayout else systemNumbers

    fun showKeyboard() {
        focusRequester.requestFocus()
        if (keyboard == KeyboardStyle.SYSTEM) keyboardController?.show()
    }

    fun toggleNumbers() {
        if (lightKeyboard != null) {
            lightKeyboard.onSpecialKeyReleased(if (numbers) SpecialKey.Letters else SpecialKey.Numbers)
        } else {
            // A focused field restarts its input with the new keyboard type.
            systemNumbers = !systemNumbers
        }
        showKeyboard()
    }

    // Type at once.
    LaunchedEffect(keyboard) {
        if (keyboard != null && keyboardOnOpen) {
            showKeyboard()
            onKeyboardShown()
        }
    }
    // As with the system keyboard, Back puts the LP3 keyboard away first.
    BackHandler(enabled = lightKeyboard != null && fieldFocused) { focusManager.clearFocus() }

    // The dial scrolls the matches, putting the keyboard away first, as in a chat.
    ScrollDialEffect { event ->
        dismissKeyboard()
        listState.scrollToItem(listDialTarget(listState.firstVisibleItemIndex, event))
    }

    val text = query.text.toString()
    val matches = remember(index, text) { index?.search(text) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background)
            .let { if (keyboard == KeyboardStyle.SYSTEM) it.imePadding() else it },
    ) {
        LightTopBar(
            leftButton = backButton(onBack),
            center = LightTopBarCenter.Text(TITLE),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )
        if (keyboard == null) return@Column

        val field = @Composable {
            ToField(
                query = query,
                keyboard = keyboard,
                numbers = numbers,
                onSubmit = dismissKeyboard,
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .onFocusChanged { fieldFocused = it.isFocused },
            )
        }
        Box(modifier = Modifier.padding(horizontal = MARGIN.gridUnitsAsDp())) {
            if (keyboard == KeyboardStyle.LIGHT) WithoutSystemKeyboard(field) else field()
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                matches == null -> Unit // loading: nothing yet
                matches.isEmpty() && text.isNotBlank() -> LightText(
                    text = "No matches",
                    variant = LightTextVariant.Paragraph,
                    lighten = true,
                    align = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = MARGIN.gridUnitsAsDp(),
                        end = MARGIN.gridUnitsAsDp(),
                        top = 1f.gridUnitsAsDp(),
                    ),
                ) {
                    items(matches) { match ->
                        when (match) {
                            is RecipientMatch.Number -> MenuItem(
                                label = "Send to ${DisplayNames.number(match.e164)}",
                                onClick = { dismissKeyboard(); onPick(match) },
                            )
                            is RecipientMatch.PersonMatch -> MenuItem(
                                label = match.person.name,
                                detail = personDetail(match),
                                onClick = { dismissKeyboard(); onPick(match) },
                            )
                            is RecipientMatch.GroupMatch -> MenuItem(
                                label = match.chat.title,
                                detail = groupDetail(match),
                                onClick = { dismissKeyboard(); onPick(match) },
                            )
                        }
                    }
                }
            }
        }

        val keypad = listOf(
            if (numbers) {
                LightBarButton.Text("ABC", contentDescription = "Letters", onClick = ::toggleNumbers)
            } else {
                LightBarButton.LightIcon(LightIcons.DIALPAD, onClick = ::toggleNumbers, contentDescription = "Keypad")
            },
        )
        if (lightKeyboard != null && fieldFocused) {
            DockedKeyboard(lightKeyboard) { LightBottomBar(items = keypad) }
        } else {
            LightBottomBar(items = keypad)
        }
    }
}

/** The "To" field: what's typed, in Heading type over an underline, as LightOS's text entry. */
@Composable
private fun ToField(
    query: TextFieldState,
    keyboard: KeyboardStyle,
    numbers: Boolean,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = LightThemeTokens.colors.content
    Column(modifier = Modifier.fillMaxWidth()) {
        BasicTextField(
            state = query,
            lineLimits = TextFieldLineLimits.SingleLine,
            textStyle = lightTextStyle(LightTextVariant.Heading).copy(color = content),
            cursorBrush = SolidColor(content),
            keyboardOptions = when (keyboard) {
                KeyboardStyle.SYSTEM -> KeyboardOptions(
                    keyboardType = if (numbers) KeyboardType.Phone else KeyboardType.Text,
                    capitalization = if (numbers) KeyboardCapitalization.None else KeyboardCapitalization.Words,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search,
                )
                KeyboardStyle.LIGHT -> KeyboardOptions(showKeyboardOnFocus = false)
            },
            onKeyboardAction = { onSubmit() },
            decorator = { inner ->
                Box {
                    if (query.text.isEmpty()) {
                        LightText(PLACEHOLDER, variant = LightTextVariant.Heading, color = PhotonColors.Faint, maxLines = 1)
                    }
                    inner()
                }
            },
            modifier = modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(3f.designVerticalPxToDp())
                .background(content),
        )
    }
}

/**
 * The LP3 keyboard docked under the matches with [bottomBar] beneath it,
 * as the SDK's text editor lays them out. It can't take focus, so the
 * field keeps it.
 */
@Composable
private fun DockedKeyboard(keyboard: Lp3KeyboardViewModel<*>, bottomBar: @Composable () -> Unit) {
    Box(modifier = Modifier.focusProperties { canFocus = false }) {
        LightEmbeddedLp3Keyboard(
            viewModel = keyboard,
            additionalBottomHeight = 5f.gridUnitsAsDp(),
            bottomBar = bottomBar,
        )
    }
}

/**
 * Where to write to [person]: a LightOS menu of the platforms, each saying
 * whether there's a chat already, a new one can start, or why not (greyed).
 * Each platform's answer shows as it comes ([reachOn]).
 */
@Composable
internal fun SendViaPage(
    person: Person,
    reach: suspend (Platform, String) -> Reach,
    onOpen: (Platform, String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val options = remember(person) { mutableStateMapOf<Platform, SendViaOption>() }
    LaunchedEffect(person) {
        for (platform in Platform.entries) {
            launch { options[platform] = reachOn(platform, person) { phone -> reach(platform, phone) } }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        LightTopBar(
            leftButton = backButton(onBack),
            center = LightTopBarCenter.Text(person.name),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )
        Column(modifier = Modifier.padding(horizontal = MARGIN.gridUnitsAsDp())) {
            for (platform in Platform.entries) {
                val option = options[platform] ?: SendViaOption(platform, null)
                val reachable = option.reach as? Reach.Reachable
                MenuItem(
                    label = platform.label,
                    detail = sendViaDetail(option, person),
                    enabled = reachable != null,
                    onClick = { reachable?.let { onOpen(platform, it.jid) } },
                )
            }
        }
    }
}

/**
 * A LightOS menu row: [label] in Heading type, sentence case, with an
 * optional [detail] under it in Detail type, lightened; no divider. A
 * disabled row is faint and doesn't respond.
 */
@Composable
private fun MenuItem(label: String, detail: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_UNITS.gridUnitsAsDp())
            .let { if (enabled) it.lightClickable(onClick = onClick) else it },
        verticalArrangement = Arrangement.Center,
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Heading,
            color = if (enabled) null else PhotonColors.Faint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail != null) {
            LightText(
                text = detail,
                variant = LightTextVariant.Detail,
                lighten = enabled,
                color = if (enabled) null else PhotonColors.Faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal const val TITLE = "New message"
internal const val PLACEHOLDER = "Name or number"
private const val NOT_RUNNING = "not available"

/** Side margins, in grid units, as LightOS lists. */
private const val MARGIN = 2f

/** A menu row's height, in grid units. */
private const val ROW_UNITS = 5f
