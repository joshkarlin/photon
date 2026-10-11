package app.photon.nav

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.photon.service.Sessions
import app.photon.ui.home.PlatformPick
import app.photon.ui.home.PlatformsScreen
import app.photon.ui.contact.ContactScreen
import app.photon.ui.settings.SettingsScreen
import app.photon.ui.all.AllChatsScreen
import app.photon.data.model.Platform
import app.photon.ui.signal.SignalChatListScreen
import app.photon.ui.sms.SmsChatListScreen
import app.photon.ui.signal.SignalPairingScreen
import app.photon.ui.signal.SignalScreen
import app.photon.ui.whatsapp.ChatListScreen
import app.photon.ui.chat.ChatScreen
import app.photon.ui.whatsapp.PairingScreen

@Composable
fun PhotonNavGraph(
    navController: NavHostController,
    deepLink: ChatDeepLink? = null,
    onDeepLinkHandled: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // All Chats is the start destination — kept at the bottom of the back stack
    // so lateral provider switches always collapse to a single chat-list screen.
    val rootRoute = Routes.ALL_CHATS

    fun lateralSwitch(target: String) {
        navController.navigate(target) {
            popUpTo(rootRoute) { inclusive = true }
            launchSingleTop = true
        }
    }

    fun openChat(platform: Platform, jid: String) = navController.navigate(Routes.chat(platform, jid))

    LaunchedEffect(deepLink) {
        val link = deepLink ?: return@LaunchedEffect
        navController.navigate(link.route) {
            popUpTo(rootRoute) { inclusive = false }
            launchSingleTop = true
        }
        onDeepLinkHandled()
    }

    NavHost(
        navController = navController,
        startDestination = rootRoute,
        modifier = modifier,
        enterTransition = { androidx.compose.animation.EnterTransition.None },
        exitTransition = { androidx.compose.animation.ExitTransition.None },
        popEnterTransition = { androidx.compose.animation.EnterTransition.None },
        popExitTransition = { androidx.compose.animation.ExitTransition.None },
    ) {
        composable(Routes.PLATFORMS) {
            PlatformsScreen(
                onPick = { pick ->
                    val target = when (pick) {
                        PlatformPick.ALL -> Routes.ALL_CHATS
                        PlatformPick.SMS -> Routes.SMS_CHATS
                        PlatformPick.WHATSAPP -> whatsappRoute(Sessions.whatsapp?.bridge?.whatsappState?.value)
                        PlatformPick.SIGNAL -> Routes.SIGNAL
                    }
                    lateralSwitch(target)
                },
            )
        }
        composable(Routes.WHATSAPP_PAIRING) {
            PairingScreen(
                onPaired = {
                    navController.navigate(Routes.WHATSAPP_CHATS) {
                        popUpTo(Routes.WHATSAPP_PAIRING) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.WHATSAPP_CHATS) {
            ChatListScreen(
                onChat = { jid -> openChat(Platform.WHATSAPP, jid) },
                onSwitch = { navController.navigate(Routes.PLATFORMS) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SIGNAL) {
            SignalScreen(
                onPaired = {
                    navController.navigate(Routes.SIGNAL_CHATS) {
                        popUpTo(Routes.SIGNAL) { inclusive = true }
                    }
                },
                onNotPaired = {
                    navController.navigate(Routes.SIGNAL_PAIRING) {
                        popUpTo(Routes.SIGNAL) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SIGNAL_PAIRING) {
            SignalPairingScreen(
                onPaired = {
                    navController.navigate(Routes.SIGNAL_CHATS) {
                        popUpTo(Routes.SIGNAL_PAIRING) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SIGNAL_CHATS) {
            SignalChatListScreen(
                onChat = { jid -> openChat(Platform.SIGNAL, jid) },
                onSwitch = { navController.navigate(Routes.PLATFORMS) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("platform") { type = NavType.StringType },
                navArgument("jid") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val platform = Routes.platformOf(backStackEntry.arguments?.getString("platform")) ?: return@composable
            val jid = Uri.decode(backStackEntry.arguments?.getString("jid") ?: "")
            ChatScreen(
                platform = platform,
                jid = jid,
                onContact = { phone, name ->
                    navController.navigate(Routes.contact(Uri.encode(phone), Uri.encode(name)))
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ALL_CHATS) {
            AllChatsScreen(
                onChat = ::openChat,
                onSwitch = { navController.navigate(Routes.PLATFORMS) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SMS_CHATS) {
            SmsChatListScreen(
                onChat = { address -> openChat(Platform.SMS, address) },
                onSwitch = { navController.navigate(Routes.PLATFORMS) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = Routes.CONTACT,
            arguments = listOf(
                navArgument("phone") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val phone = Uri.decode(backStackEntry.arguments?.getString("phone") ?: "")
            val name = Uri.decode(backStackEntry.arguments?.getString("name") ?: "")
            ContactScreen(
                phone = phone,
                displayName = name,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onResetWhatsApp = {
                    navController.navigate(Routes.WHATSAPP_PAIRING) {
                        popUpTo(rootRoute) { inclusive = false }
                    }
                },
                onResetSignal = {
                    navController.navigate(Routes.SIGNAL_PAIRING) {
                        popUpTo(rootRoute) { inclusive = false }
                    }
                },
            )
        }
    }
}

/**
 * Where picking WhatsApp goes. Pairing only once the bridge reports there's
 * no session ("logged_out"): a paired account that is connecting, reconnecting
 * or not yet reported on belongs in its chats, which show the connection state.
 */
internal fun whatsappRoute(state: String?): String =
    if (state == "logged_out") Routes.WHATSAPP_PAIRING else Routes.WHATSAPP_CHATS
