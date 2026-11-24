package io.github.sma.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.sma.R
import io.github.sma.background.ConfirmationMonitor
import io.github.sma.data.AccountRepository
import io.github.sma.data.AppLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

object Routes {
    const val ACCOUNTS = "accounts"
    const val IMPORT = "import"
    const val ACCOUNT = "account/{steamId}"
    const val SIGN_IN = "signin/{steamId}"
    const val CONFIRMATIONS = "confirmations/{steamId}"
    const val CONFIRMATION_DETAILS = "confdetails/{steamId}/{confId}"
    const val QR = "qr/{steamId}"
    const val LOGIN_REQUESTS = "loginrequests/{steamId}?clientId={clientId}&version={version}"
    const val BROWSER = "browser/{steamId}?url={url}"
    const val ABOUT = "about"
    const val TRANSFER = "transfer"
    const val SETTINGS = "settings"
    const val LICENSES = "licenses"

    const val DEFAULT_BROWSER_URL = "https://steamcommunity.com/my/tradeoffers/"
    const val INVENTORY_URL = "https://steamcommunity.com/my/inventory/"
    const val MARKET_URL = "https://steamcommunity.com/market/"

    fun account(steamId: String): String = "account/$steamId"

    fun signIn(steamId: String): String = "signin/$steamId"

    fun confirmations(steamId: String): String = "confirmations/$steamId"

    fun confirmationDetails(steamId: String, confId: String): String = "confdetails/$steamId/$confId"

    fun qr(steamId: String): String = "qr/$steamId"

    /** Optional query args are omitted when null so the nullable nav args fall back to their default. */
    fun loginRequests(steamId: String, clientId: Long? = null, version: Int? = null): String {
        val query = buildList {
            clientId?.let { add("clientId=$it") }
            version?.let { add("version=$it") }
        }
        return if (query.isEmpty()) {
            "loginrequests/$steamId"
        } else {
            "loginrequests/$steamId?" + query.joinToString("&")
        }
    }

    fun browser(steamId: String, url: String = DEFAULT_BROWSER_URL): String =
        "browser/$steamId?url=${Uri.encode(url)}"
}

@Composable
fun SmaApp(activity: FragmentActivity, pendingRoute: MutableStateFlow<String?>) {
    val unlocked by AppLock.unlocked.collectAsState()

    if (!unlocked) {
        UnlockScreen(activity)
    } else {
        val nav = rememberNavController()

        // Foreground refresh of confirmations and sign-in request counts while unlocked.
        // Runs only while RESUMED: no network calls (and no background errors) while the app is minimized.
        val ctx = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(lifecycleOwner) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    runCatching { ConfirmationMonitor.refresh(ctx, notify = false, reportErrors = false) }
                    delay(30_000)
                }
            }
        }

        // Fast foreground poll of sign-in requests only, so a new request appears within a few seconds.
        LaunchedEffect(lifecycleOwner) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    runCatching { ConfirmationMonitor.refreshLoginRequests(ctx) }
                    delay(4_000)
                }
            }
        }

        // Opens the sign-in requests screen as soon as a new request appears, without a menu tap.
        val loginRequests by ConfirmationMonitor.loginRequests.collectAsState()
        val lastLoginCounts = remember { HashMap<String, Int>() }
        LaunchedEffect(loginRequests) {
            val target = loginRequests.entries
                .firstOrNull { (steamId, count) -> count > (lastLoginCounts[steamId] ?: 0) }
                ?.key
            lastLoginCounts.clear()
            lastLoginCounts.putAll(loginRequests)
            val onLoginRequests = nav.currentBackStackEntry?.destination?.route
                ?.startsWith("loginrequests") == true
            if (target != null && !onLoginRequests) {
                nav.navigate(Routes.loginRequests(target))
            }
        }
        NavHost(navController = nav, startDestination = Routes.ACCOUNTS) {
            composable(Routes.ACCOUNTS) {
                AccountsScreen(nav)
            }
            composable(Routes.IMPORT) {
                ImportScreen(nav)
            }
            composable(Routes.ABOUT) {
                AboutScreen(nav)
            }
            composable(Routes.TRANSFER) {
                TransferScreen(nav)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(nav)
            }
            composable(Routes.LICENSES) {
                LicensesScreen(nav)
            }
            composable(
                route = Routes.ACCOUNT,
                arguments = listOf(navArgument("steamId") { type = NavType.StringType }),
            ) { entry ->
                AccountScreen(steamId = entry.arg("steamId"), nav = nav)
            }
            composable(
                route = Routes.SIGN_IN,
                arguments = listOf(navArgument("steamId") { type = NavType.StringType }),
            ) { entry ->
                SignInScreen(steamId = entry.arg("steamId"), nav = nav)
            }
            composable(
                route = Routes.CONFIRMATIONS,
                arguments = listOf(navArgument("steamId") { type = NavType.StringType }),
            ) { entry ->
                ConfirmationsScreen(steamId = entry.arg("steamId"), nav = nav)
            }
            composable(
                route = Routes.CONFIRMATION_DETAILS,
                arguments = listOf(
                    navArgument("steamId") { type = NavType.StringType },
                    navArgument("confId") { type = NavType.StringType },
                ),
            ) { entry ->
                ConfirmationDetailsScreen(
                    steamId = entry.arg("steamId"),
                    confId = entry.arg("confId"),
                    nav = nav,
                )
            }
            composable(
                route = Routes.QR,
                arguments = listOf(navArgument("steamId") { type = NavType.StringType }),
            ) { entry ->
                QrScanScreen(steamId = entry.arg("steamId"), nav = nav)
            }
            composable(
                route = Routes.LOGIN_REQUESTS,
                arguments = listOf(
                    navArgument("steamId") { type = NavType.StringType },
                    navArgument("clientId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("version") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                LoginRequestsScreen(
                    steamId = entry.arg("steamId"),
                    nav = nav,
                    clientId = entry.arg("clientId").toLongOrNull(),
                    version = entry.arg("version").toIntOrNull(),
                )
            }
            composable(
                route = Routes.BROWSER,
                arguments = listOf(
                    navArgument("steamId") { type = NavType.StringType },
                    navArgument("url") {
                        type = NavType.StringType
                        defaultValue = Routes.DEFAULT_BROWSER_URL
                    },
                ),
            ) { entry ->
                BrowserScreen(
                    steamId = entry.arg("steamId"),
                    startUrl = entry.arg("url").ifBlank { Routes.DEFAULT_BROWSER_URL },
                    nav = nav,
                )
            }
        }

        // Deep link from a notification: runs once the NavHost graph exists (after unlock).
        val route by pendingRoute.collectAsState()
        LaunchedEffect(route) {
            val target = route ?: return@LaunchedEffect
            nav.navigate(target)
            pendingRoute.value = null
        }

        StartupPermissions()
    }
}

private fun NavBackStackEntry.arg(name: String): String = arguments?.getString(name).orEmpty()

@Composable
private fun UnlockScreen(activity: FragmentActivity) {
    val context = LocalContext.current
    val loadError by AccountRepository.loadError.collectAsState()
    val canAuthenticate = AppLock.canAuthenticate(context)

    // Show the biometric prompt automatically when the lock screen appears; the button stays for retries.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (canAuthenticate) AppLock.unlock(activity, "Unlock SMA")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(96.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "SMA",
            style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (!canAuthenticate) {
            Text(
                text = "Set up a screen lock (PIN, pattern or fingerprint) to use SMA",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
        loadError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
        Button(
            onClick = { AppLock.unlock(activity, "Unlock SMA") },
            enabled = canAuthenticate,
        ) {
            Text("Unlock")
        }
    }
}
