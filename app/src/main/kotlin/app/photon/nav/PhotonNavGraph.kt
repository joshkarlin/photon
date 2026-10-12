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
import app.photon.data.model.Platform
import app.photon.service.Sessions
import app.photon.ui.chat.ArchivedChatsScreen
import app.photon.ui.chat.ChatListScreen
import app.photon.ui.chat.ChatScreen
import app.photon.ui.contact.ContactScreen
import app.photon.ui.home.PlatformsScreen
import app.photon.ui.newmessage.NewMessageScreen
import app.photon.ui.settings.SettingsScreen
import app.photon.ui.signal.SignalPairingScreen
import app.photon.ui.whatsapp.PairingScreen

@Composable
fun PhotonNavGraph(
    navController: NavHostController,
    deepLink: ChatDeepLink? = null,
    onDeepLinkHandled: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // The chat list is the start destination (All Chats) and stays at the
    // bottom of the back stack, so switching platform always collapses to a
    // single chat list.
    val rootRoute = Routes.CHATS

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
        composable(
            route = Routes.CHATS,
            arguments = listOf(
                navArgument("platform") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            ChatListScreen(
                filter = Routes.platformOf(backStackEntry.arguments?.getString("platform")),
                onChat = ::openChat,
                onSwitch = { navController.navigate(Routes.PLATFORMS) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onNewMessage = { navController.openNewMessage() },
            )
        }
        composable(Routes.PLATFORMS) {
            PlatformsScreen(
                onPick = { filter ->
                    lateralSwitch(
                        pickedRoute(
                            filter,
                            whatsappState = Sessions.whatsapp?.bridge?.whatsappState?.value,
                            signalLinked = Sessions.signal?.credentials?.isRegistered() == true,
                        ),
                    )
                },
                onArchived = { navController.openArchived() },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ARCHIVED) {
            ArchivedChatsScreen(
                onChat = ::openChat,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.WHATSAPP_PAIRING) {
            PairingScreen(
                onPaired = {
                    navController.navigate(Routes.chats(Platform.WHATSAPP)) {
                        popUpTo(Routes.WHATSAPP_PAIRING) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SIGNAL_PAIRING) {
            SignalPairingScreen(
                onPaired = {
                    navController.navigate(Routes.chats(Platform.SIGNAL)) {
                        popUpTo(Routes.SIGNAL_PAIRING) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
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
        composable(Routes.NEW_MESSAGE) {
            NewMessageScreen(
                onOpenChat = navController::openChatFromNewMessage,
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
