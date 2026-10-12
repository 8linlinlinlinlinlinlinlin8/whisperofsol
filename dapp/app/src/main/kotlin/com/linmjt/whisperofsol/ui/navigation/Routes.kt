package com.linmjt.whisperofsol.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * WhisperOfSol — In-app route table.
 *
 * Five top-level screens, no nested graphs. Each route carries an optional
 * title shown in the top-bar. We're using a state-based router instead of
 * androidx.navigation to keep the dep tree small and side-step Activity
 * lifecycle surprises during V1 development.
 */
sealed class Route(val route: String, val title: String) {
    data object Wallet        : Route("wallet",        "Whisper of Sol")
    data object Main          : Route("main",          "Whisper of Sol")
    data object SendWhisper   : Route("send",          "Send Whisper")
    data object Inbox         : Route("inbox",         "Inbox")
    data object Rules         : Route("rules",         "Lottery Rules")
    // v3.0.0: Settings screen (language switcher + about)
    data object Settings      : Route("settings",      "Settings")

    companion object {
        val all = listOf(Wallet, Main, SendWhisper, Inbox, Rules, Settings)
    }
}

/**
 * AppRouter — state holder for the current screen.
 *
 * Holds the active route in a [rememberSaveable] so it survives rotation
 * and process-death (the V1 mock data in HomeViewModel is also resilient).
 */
@Composable
fun rememberAppRouter(initial: Route = Route.Wallet): AppRouterState {
    var current by rememberSaveable(stateSaver = RouteSaver) {
        mutableStateOf(initial)
    }
    return remember(current) { AppRouterState(current) { current = it } }
}

class AppRouterState(
    val current: Route,
    val navigate: (Route) -> Unit,
)

private val RouteSaver = androidx.compose.runtime.saveable.Saver<Route, String>(
    save = { it.route },
    restore = { key -> Route.all.firstOrNull { it.route == key } ?: Route.Wallet },
)