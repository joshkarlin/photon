package app.photon.nav

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.photon.service.PhotonService
import app.photon.ui.home.PlatformPick
import app.photon.ui.home.PlatformsScreen
import app.photon.ui.contact.ContactScreen
import app.photon.ui.settings.SettingsScreen
import app.photon.ui.all.AllChatsScreen
import app.photon.ui.all.Platform
import app.photon.ui.signal.SignalChatListScreen
import app.photon.ui.signal.SignalChatScreen
import app.photon.ui.sms.SmsChatListScreen
import app.photon.ui.sms.SmsChatScreen
import app.photon.ui.signal.SignalPairingScreen
import app.photon.ui.signal.SignalScreen
import app.photon.ui.whatsapp.ChatListScreen
import app.photon.ui.whatsapp.ChatScreen
import app.photon.ui.whatsapp.PairingScreen

@Composable
fun PhotonNavGraph(
    navController: NavHostController,
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
                        PlatformPick.WHATSAPP -> {
                            val state = PhotonService._wsClient?.whatsappState?.value
                            if (state == "connected") Routes.WHATSAPP_CHATS else Routes.WHATSAPP_PAIRING
                        }
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
                onChat = { jid ->
                    navController.navigate(Routes.chat(Uri.encode(jid)))
                },
                onSwitch = { navController.navigate(Routes.PLATFORMS) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = Routes.WHATSAPP_CHAT,
            arguments = listOf(navArgument("jid") { type = NavType.StringType }),
        ) { backStackEntry ->
            val jid = Uri.decode(backStackEntry.arguments?.getString("jid") ?: "")
            ChatScreen(
                jid = jid,
                onContact = { phone, name ->
                    navController.navigate(Routes.contact(Uri.encode(phone), Uri.encode(name)))
                },
                onBack = { navController.popBackStack() },
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
                onChat = { jid ->
                    navController.navigate(Routes.signalChat(Uri.encode(jid)))
                },
                onSwitch = { navController.navigate(Routes.PLATFORMS) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = Routes.SIGNAL_CHAT,
            arguments = listOf(navArgument("jid") { type = NavType.StringType }),
        ) { backStackEntry ->
            val jid = Uri.decode(backStackEntry.arguments?.getString("jid") ?: "")
            SignalChatScreen(
                jid = jid,
                onContact = { phone, name ->
                    navController.navigate(Routes.contact(Uri.encode(phone), Uri.encode(name)))
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ALL_CHATS) {
            AllChatsScreen(
                onChat = { platform, jid ->
                    when (platform) {
                        Platform.WHATSAPP -> navController.navigate(Routes.chat(Uri.encode(jid)))
                        Platform.SIGNAL -> navController.navigate(Routes.signalChat(Uri.encode(jid)))
                        Platform.SMS -> navController.navigate(Routes.smsChat(Uri.encode(jid)))
                    }
                },
                onSwitch = { navController.navigate(Routes.PLATFORMS) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SMS_CHATS) {
            SmsChatListScreen(
                onChat = { address ->
                    navController.navigate(Routes.smsChat(Uri.encode(address)))
                },
                onSwitch = { navController.navigate(Routes.PLATFORMS) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = Routes.SMS_CHAT,
            arguments = listOf(navArgument("address") { type = NavType.StringType }),
        ) { backStackEntry ->
            val address = Uri.decode(backStackEntry.arguments?.getString("address") ?: "")
            SmsChatScreen(
                address = address,
                onContact = { phone, name ->
                    navController.navigate(Routes.contact(Uri.encode(phone), Uri.encode(name)))
                },
                onBack = { navController.popBackStack() },
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
