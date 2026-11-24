package io.github.sma.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.sma.core.crypto.Secrets
import io.github.sma.core.crypto.SteamTotp
import io.github.sma.core.mafile.MaFile
import io.github.sma.core.mafile.SteamAccount
import io.github.sma.core.steam.AuthApi
import io.github.sma.core.steam.EnrollApi
import io.github.sma.core.steam.EnrollException
import io.github.sma.core.steam.NeedsSignInException
import io.github.sma.core.steam.SteamApiException
import io.github.sma.core.steam.SteamTime
import io.github.sma.core.steam.TransferApi
import io.github.sma.core.steam.TransferException
import io.github.sma.core.steam.userMessage
import io.github.sma.data.AccountRepository
import io.github.sma.data.SecureClipboard
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Steam guard code types (also the allowedConfirmations values).
private const val EMAIL_CODE_TYPE = 2
private const val DEVICE_CODE_TYPE = 3
private const val CONFIRM_ON_DEVICE_TYPE = 4
private const val CONFIRM_ON_EMAIL_TYPE = 5
private const val ERESULT_INVALID_PASSWORD = 5
private const val ERESULT_RATE_LIMITED_A = 84
private const val ERESULT_RATE_LIMITED_B = 87

private const val AUTO_SIGN_IN_TIMEOUT_SECONDS = 30

private const val STEPS_WITH_PHONE = 6
private const val STEPS_REVOCATION = 8

private val REVOCATION_CODE_REGEX = Regex("^R\\d{5}$")

private const val SENT_FAILURE_TEXT =
    "The request may have reached Steam. Do NOT retry. Check the Steam app: if it no longer shows codes, " +
        "use your revocation code at https://store.steampowered.com/twofactor/manage or contact Steam Support."

private const val WITHOUT_PHONE_NOT_OFFERED =
    "Steam did not offer this option for your account. Use option A, or option C with your revocation code."

private const val WITHOUT_PHONE_REJECTED =
    "Steam did not accept moving without the old device. Use option A, or option C: remove the authenticator " +
        "with your revocation code (15-day trade hold)."

private const val REVOCATION_NO_WEAK_TOKEN =
    "Steam did not offer this option for your account."

private const val MANUAL_SIGN_IN_NOTE =
    "Authenticator moved. Sign in once from the account screen to enable confirmations."

private enum class Step {
    INTRO,
    CHOOSE,
    SIGN_IN,
    SEND_SMS,
    CONFIRM,
    DONE,
    REVOKE_WARNING,
    REVOKE_CODE,
    REVOKE_LINK,
}

/**
 * WITH_PHONE: full login with the Steam app (A).
 * WITHOUT_PHONE: password and a code from SMS or email only (B).
 * REVOCATION: remove with the revocation code, then link a new authenticator (C).
 */
private enum class MoveMethod { WITH_PHONE, WITHOUT_PHONE, REVOCATION }

private fun requiredCodeType(allowed: List<Int>): Int? = when {
    DEVICE_CODE_TYPE in allowed -> DEVICE_CODE_TYPE
    EMAIL_CODE_TYPE in allowed -> EMAIL_CODE_TYPE
    else -> null
}

/** Step number shown in "Step N of M". Variant C has more steps than A/B. */
private fun stepNumber(step: Step, method: MoveMethod?): Int {
    if (method != MoveMethod.REVOCATION) return step.ordinal + 1
    return when (step) {
        Step.INTRO -> 1
        Step.CHOOSE -> 2
        Step.REVOKE_WARNING -> 3
        Step.SIGN_IN -> 4
        Step.REVOKE_CODE -> 5
        Step.REVOKE_LINK -> 6
        Step.SEND_SMS -> 6
        Step.CONFIRM -> 7
        Step.DONE -> 8
    }
}

/**
 * Best-effort sign-in after a WITHOUT_PHONE move, using the new secrets.
 * Returns the refresh token when sign-in succeeded, null otherwise.
 */
private suspend fun completeSignIn(session: AuthApi.LoginSession, account: SteamAccount): String? = try {
    AuthApi.submitCode(
        session,
        SteamTotp.code(Secrets.decode(account.sharedSecret), SteamTime.now()),
        DEVICE_CODE_TYPE,
    )
    val tokens = AuthApi.poll(session, AUTO_SIGN_IN_TIMEOUT_SECONDS)
    AccountRepository.updateRefreshToken(session.steamId.toString(), tokens.refreshToken)
    tokens.refreshToken
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(nav: NavController) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var step by remember { mutableStateOf(Step.INTRO) }
    var understood by remember { mutableStateOf(false) }
    var method by remember { mutableStateOf<MoveMethod?>(null) }

    // Sign-in state. The password is cleared after every sign-in attempt and never persisted.
    var accountName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<AuthApi.LoginSession?>(null) }
    var guardCode by remember { mutableStateOf("") }
    var tokens by remember { mutableStateOf<AuthApi.Tokens?>(null) }
    // SteamID of the account being moved; kept after the sign-in session is dropped (variant C needs it).
    var signedInSteamId by remember { mutableStateOf<String?>(null) }
    // Background poll started right after begin (approval in the Steam app or from email); null when none is running.
    var backgroundPoll by remember { mutableStateOf<Job?>(null) }
    // Set once per sign-in attempt so the step advances only once, whichever poll finishes first.
    val signInCompleted = remember { AtomicBoolean(false) }

    // Variant C state.
    var revokeUnderstood by remember { mutableStateOf(false) }
    var revocationInput by remember { mutableStateOf("") }

    // Confirm state.
    var smsCode by remember { mutableStateOf("") }
    // Where Steam sent the code for variant C ("SMS to ...1234", "email", "SMS or email"), null until linked.
    var linkSentTo by remember { mutableStateOf<String?>(null) }
    var locked by remember { mutableStateOf(false) }
    var unsavedJson by remember { mutableStateOf<String?>(null) }
    var unsavedCopied by remember { mutableStateOf(false) }

    // Done state.
    var savedAccount by remember { mutableStateOf<SteamAccount?>(null) }
    var revocationChecked by remember { mutableStateOf(false) }
    var needsManualSignIn by remember { mutableStateOf(false) }

    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var infoText by remember { mutableStateOf<String?>(null) }
    var rejectedWithoutPhone by remember { mutableStateOf(false) }

    // Which Steam Guard code the sign-in is waiting for (3 = device, 2 = email), null = none.
    val requiredType: Int? = session?.allowedConfirmations?.let { requiredCodeType(it) }
    // Confirmation types Steam allows for the current sign-in (drives the SIGN_IN hint).
    val allowedConfirmations: List<Int> = session?.allowedConfirmations.orEmpty()

    // Variant C: between removal and the confirmed link the account has no authenticator, so leaving is blocked.
    val leaveBlocked = busy || unsavedJson != null || (step == Step.DONE && !revocationChecked) ||
        (method == MoveMethod.REVOCATION && step == Step.REVOKE_LINK)

    BackHandler(enabled = !leaveBlocked) { nav.popBackStack() }

    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    DisposableEffect(Unit) {
        onDispose { backgroundPoll?.cancel() }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream") /* json MIME makes pickers append .json */,
    ) { uri ->
        val account = savedAccount
        if (uri != null && account != null) {
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        val out = context.contentResolver.openOutputStream(uri)
                            ?: error("Cannot open the selected file")
                        out.use { it.write(MaFile.export(account).toByteArray(Charsets.UTF_8)) }
                    }
                }
                val message = if (outcome.isSuccess) {
                    "Exported .maFile"
                } else {
                    "Export failed: ${outcome.exceptionOrNull()?.userMessage() ?: "unknown error"}"
                }
                snackbar.showSnackbar(message)
            }
        }
    }

    fun finishToAccounts() {
        nav.navigate(Routes.ACCOUNTS) {
            popUpTo(Routes.ACCOUNTS) { inclusive = false }
            launchSingleTop = true
        }
    }

    fun chooseMethod(chosen: MoveMethod) {
        method = chosen
        errorText = null
        infoText = null
        step = if (chosen == MoveMethod.REVOCATION) Step.REVOKE_WARNING else Step.SIGN_IN
    }

    fun backToChoice() {
        if (busy) return
        backgroundPoll?.cancel()
        backgroundPoll = null
        session = null
        tokens = null
        rejectedWithoutPhone = false
        errorText = null
        infoText = null
        method = null
        step = Step.CHOOSE
    }

    /** Shared success path of the full sign-in. Runs once per attempt, whichever poll (background or code path) finishes first. */
    fun finishSignIn(current: AuthApi.LoginSession, polled: AuthApi.Tokens) {
        if (!signInCompleted.compareAndSet(false, true)) return
        tokens = polled
        signedInSteamId = current.steamId.toString()
        session = null
        step = if (method == MoveMethod.REVOCATION) Step.REVOKE_CODE else Step.SEND_SMS
    }

    /** Polls in the background while the user types a code or approves in the Steam app (variants A and C). */
    fun startBackgroundPoll(begun: AuthApi.LoginSession) {
        backgroundPoll = scope.launch {
            try {
                val polled = AuthApi.poll(begun)
                backgroundPoll = null
                finishSignIn(begun, polled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                backgroundPoll = null
                session = null
                guardCode = ""
                errorText = "Sign-in timed out. Start again."
            }
        }
    }

    fun signIn() {
        if (busy) return
        scope.launch {
            busy = true
            errorText = null
            infoText = null
            try {
                val existing = session
                val current: AuthApi.LoginSession
                if (existing != null) {
                    current = existing
                } else {
                    val begun = AuthApi.begin(accountName.trim(), password)
                    if (AccountRepository.find(begun.steamId.toString()) != null) {
                        errorText = "This account is already in SMA"
                        return@launch
                    }
                    session = begun
                    current = begun
                    signInCompleted.set(false)
                    if (current.allowedConfirmations.any { it == CONFIRM_ON_DEVICE_TYPE || it == CONFIRM_ON_EMAIL_TYPE }) {
                        startBackgroundPoll(begun)
                    }
                }

                val required = requiredCodeType(current.allowedConfirmations)
                if (existing == null && required != null) {
                    // First click after begin: stop here so the user can type the code.
                    // A background poll (if started) keeps waiting for approval meanwhile.
                    if (required == EMAIL_CODE_TYPE) {
                        infoText = "Enter the code sent to your email."
                    }
                    return@launch
                }
                if (required != null) {
                    val entered = guardCode.trim()
                    if (entered.isEmpty()) {
                        errorText = if (required == DEVICE_CODE_TYPE) {
                            "Enter the Steam Guard code from the Steam app"
                        } else {
                            "Enter the code from email"
                        }
                        return@launch
                    }
                    AuthApi.submitCode(current, entered, required)
                    guardCode = ""
                }

                // A background poll is running: join it instead of polling again. It finishes the sign-in itself.
                val running = backgroundPoll
                if (running != null) {
                    running.join()
                    return@launch
                }
                finishSignIn(current, AuthApi.poll(current))
            } catch (e: CancellationException) {
                throw e
            } catch (e: SteamApiException) {
                errorText = when (e.eresult) {
                    ERESULT_INVALID_PASSWORD -> "Wrong password"
                    ERESULT_RATE_LIMITED_A, ERESULT_RATE_LIMITED_B -> "Too many attempts, try later"
                    else -> e.userMessage()
                }
            } catch (e: Exception) {
                errorText = e.userMessage()
            } finally {
                password = ""
                busy = false
            }
        }
    }

    /** WITHOUT_PHONE: password only. No poll and no code; the session keeps the weak token for the SMS steps. */
    fun beginWithoutPhone() {
        if (busy) return
        scope.launch {
            busy = true
            errorText = null
            infoText = null
            try {
                val begun = AuthApi.begin(accountName.trim(), password)
                if (AccountRepository.find(begun.steamId.toString()) != null) {
                    errorText = "This account is already in SMA"
                    return@launch
                }
                if (begun.weakToken == null) {
                    errorText = WITHOUT_PHONE_NOT_OFFERED
                    return@launch
                }
                session = begun
                step = Step.SEND_SMS
            } catch (e: CancellationException) {
                throw e
            } catch (e: SteamApiException) {
                errorText = when (e.eresult) {
                    ERESULT_INVALID_PASSWORD -> "Wrong password"
                    ERESULT_RATE_LIMITED_A, ERESULT_RATE_LIMITED_B -> "Too many attempts, try later"
                    else -> e.userMessage()
                }
            } catch (e: Exception) {
                errorText = e.userMessage()
            } finally {
                password = ""
                busy = false
            }
        }
    }

    /** REVOCATION, "I don't have the old phone": password only, the weak token replaces the polled tokens. */
    fun beginRevocationWithoutPhone() {
        if (busy) return
        scope.launch {
            busy = true
            errorText = null
            infoText = null
            try {
                val begun = AuthApi.begin(accountName.trim(), password)
                if (AccountRepository.find(begun.steamId.toString()) != null) {
                    errorText = "This account is already in SMA"
                    return@launch
                }
                if (begun.weakToken == null) {
                    errorText = REVOCATION_NO_WEAK_TOKEN
                    return@launch
                }
                signedInSteamId = begun.steamId.toString()
                session = begun
                step = Step.REVOKE_CODE
            } catch (e: CancellationException) {
                throw e
            } catch (e: SteamApiException) {
                errorText = when (e.eresult) {
                    ERESULT_INVALID_PASSWORD -> "Wrong password"
                    ERESULT_RATE_LIMITED_A, ERESULT_RATE_LIMITED_B -> "Too many attempts, try later"
                    else -> e.userMessage()
                }
            } catch (e: Exception) {
                errorText = e.userMessage()
            } finally {
                password = ""
                busy = false
            }
        }
    }

    fun sendSms() {
        if (busy) return
        val withoutPhone = method == MoveMethod.WITHOUT_PHONE
        val accessToken = if (withoutPhone) session?.weakToken else tokens?.accessToken
        if (accessToken == null) return
        scope.launch {
            busy = true
            errorText = null
            rejectedWithoutPhone = false
            try {
                TransferApi.startChallenge(accessToken)
                step = Step.CONFIRM
            } catch (e: CancellationException) {
                throw e
            } catch (e: SteamApiException) {
                if (withoutPhone) {
                    rejectedWithoutPhone = true
                    errorText = WITHOUT_PHONE_REJECTED
                } else {
                    errorText = "Steam did not send a verification code: ${e.userMessage()}. " +
                        "Check the account's email and phone in Steam settings and try again."
                }
            } catch (e: NeedsSignInException) {
                if (withoutPhone) {
                    rejectedWithoutPhone = true
                    errorText = WITHOUT_PHONE_REJECTED
                } else {
                    errorText = "Sign-in expired, start again"
                }
            } catch (e: Exception) {
                errorText = "Network error: ${e.userMessage()}"
            } finally {
                busy = false
            }
        }
    }

    fun moveAuthenticator() {
        val code = smsCode.trim()
        val withoutPhone = method == MoveMethod.WITHOUT_PHONE
        val accessToken = if (withoutPhone) session?.weakToken else tokens?.accessToken
        val refreshToken = if (withoutPhone) null else tokens?.refreshToken
        if (busy || locked || code.isEmpty() || accessToken == null) return
        scope.launch {
            busy = true
            errorText = null
            try {
                val draft = TransferApi.continueChallenge(accessToken, code)
                val account = draft.toAccount(refreshToken)
                // Persist before any UI change: from here on these secrets exist only in this app.
                var saved = false
                try {
                    AccountRepository.upsert(account)
                    savedAccount = account
                    tokens = null
                    smsCode = ""
                    step = Step.DONE
                    saved = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    unsavedJson = MaFile.export(account)
                    tokens = null
                    smsCode = ""
                }
                if (saved && withoutPhone) {
                    // Best effort: the account is already saved, a failed sign-in only shows a note on Done.
                    val s = session
                    session = null
                    val newRefreshToken = if (s == null) null else completeSignIn(s, account)
                    if (newRefreshToken != null) {
                        savedAccount = account.copy(refreshToken = newRefreshToken)
                    }
                    needsManualSignIn = newRefreshToken == null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransferException) {
                errorText = if (e.sent) {
                    "${e.userMessage()}\n\n$SENT_FAILURE_TEXT"
                } else {
                    e.userMessage()
                }
                if (e.sent) locked = true
            } catch (e: Exception) {
                errorText = "Transfer failed: ${e.userMessage()}"
            } finally {
                busy = false
            }
        }
    }

    /** REVOCATION step C3: RemoveAuthenticator with the revocation code. */
    fun removeWithRevocationCode() {
        val code = revocationInput.trim().uppercase()
        val accessToken = tokens?.accessToken ?: session?.weakToken
        val steamId = signedInSteamId
        if (busy || accessToken == null || steamId == null || !REVOCATION_CODE_REGEX.matches(code)) return
        scope.launch {
            busy = true
            errorText = null
            try {
                EnrollApi.removeAuthenticator(accessToken, steamId, code)
                revocationInput = ""
                // Steam accepted the removal: the account has no authenticator until the link is confirmed.
                step = Step.REVOKE_LINK
            } catch (e: CancellationException) {
                throw e
            } catch (e: EnrollException) {
                errorText = e.userMessage()
            } catch (e: NeedsSignInException) {
                errorText = "Sign-in expired, start again"
            } catch (e: SteamApiException) {
                errorText = e.userMessage()
            } catch (e: Exception) {
                errorText = "Network error: ${e.userMessage()}"
            } finally {
                busy = false
            }
        }
    }

    /** REVOCATION step C4: AddAuthenticator (Steam sends a code by SMS or email), then save the account before showing the code. */
    fun linkWithSms() {
        val accessToken = tokens?.accessToken ?: session?.weakToken
        val steamId = signedInSteamId
        if (busy || accessToken == null || steamId == null) return
        scope.launch {
            busy = true
            errorText = null
            try {
                val start = EnrollApi.addAuthenticatorWithChannel(accessToken, steamId)
                val draft = start.draft
                // Unfinished until the code is confirmed (fullyEnrolled = false).
                val account = draft.toAccount(tokens?.refreshToken).copy(fullyEnrolled = false)
                // Persist before any UI change: the new secrets exist only in this app from here on.
                try {
                    AccountRepository.upsert(account)
                    savedAccount = account
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    unsavedJson = MaFile.export(account)
                }
                smsCode = ""
                linkSentTo = start.sentTo
                step = Step.CONFIRM
            } catch (e: CancellationException) {
                throw e
            } catch (e: EnrollException) {
                errorText = e.userMessage()
            } catch (e: NeedsSignInException) {
                errorText = "Sign-in expired, start again"
            } catch (e: SteamApiException) {
                errorText = e.userMessage()
            } catch (e: Exception) {
                errorText = "Network error: ${e.userMessage()}"
            } finally {
                busy = false
            }
        }
    }

    /** REVOCATION step C5: FinalizeAddAuthenticator with the code, then mark the account fully enrolled. */
    fun finalizeWithSms() {
        val code = smsCode.trim()
        val pending = savedAccount
        val accessToken = tokens?.accessToken ?: session?.weakToken
        if (busy || code.isEmpty() || pending == null || accessToken == null) return
        scope.launch {
            busy = true
            errorText = null
            try {
                EnrollApi.finalize(accessToken, pending.steamId, pending.sharedSecret, code)
                val enrolled = pending.copy(fullyEnrolled = true)
                var saved = false
                try {
                    AccountRepository.upsert(enrolled)
                    savedAccount = enrolled
                    saved = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    unsavedJson = MaFile.export(enrolled)
                }
                tokens = null
                smsCode = ""
                val s = session
                session = null
                if (saved) step = Step.DONE
                if (saved && pending.refreshToken == null && s != null) {
                    // Weak-token path: best-effort sign-in, as in variant B. Failure only shows a note on Done.
                    val newRefreshToken = completeSignIn(s, enrolled)
                    if (newRefreshToken != null) {
                        savedAccount = enrolled.copy(refreshToken = newRefreshToken)
                    }
                    needsManualSignIn = newRefreshToken == null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: EnrollException) {
                // Re-entering the code is allowed after any failure.
                errorText = e.userMessage()
            } catch (e: NeedsSignInException) {
                errorText = "Sign-in expired, start again"
            } catch (e: SteamApiException) {
                errorText = e.userMessage()
            } catch (e: Exception) {
                errorText = "Network error: ${e.userMessage()}"
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Move from Steam app") },
                navigationIcon = {
                    TextButton(enabled = !leaveBlocked, onClick = { nav.popBackStack() }) {
                        Text("Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val totalSteps = if (method == MoveMethod.REVOCATION) STEPS_REVOCATION else STEPS_WITH_PHONE
            Text(
                text = "Step ${stepNumber(step, method)} of $totalSteps",
                style = MaterialTheme.typography.labelMedium,
            )

            when (step) {
                Step.INTRO -> {
                    Text(
                        text = "Move the authenticator from the Steam app to SMA",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "- The authenticator moves from the official Steam app to SMA.\n" +
                            "- The Steam app stops generating codes for this account.\n" +
                            "- Steam sends a verification code by SMS or email.\n" +
                            "- Moving gives a 2-day trade hold. Removing with the revocation code gives a 15-day hold.\n" +
                            "- You will get a NEW revocation code. Store it safely.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    WarningCard("This operation cannot be undone. Steam issues new secrets and the old app stops working.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = understood, onCheckedChange = { understood = it })
                        Text("I understand this cannot be undone", style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(
                        onClick = { step = Step.CHOOSE },
                        enabled = understood,
                    ) {
                        Text("Continue")
                    }
                }

                Step.CHOOSE -> {
                    Text(
                        text = "How do you want to move it?",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    ChoiceCard(
                        letter = "A",
                        cooldown = "2-day trade hold",
                        recommended = true,
                        title = "I have the phone with the Steam app",
                        subtitle = "Sign in, approve in the Steam app or enter its code, then confirm with a code from SMS or email.",
                        onClick = { chooseMethod(MoveMethod.WITH_PHONE) },
                    )
                    ChoiceCard(
                        letter = "B",
                        cooldown = "2-day trade hold",
                        recommended = false,
                        title = "I don't have the old phone",
                        subtitle = "Uses only your password and a code from SMS or email.",
                        onClick = { chooseMethod(MoveMethod.WITHOUT_PHONE) },
                    )
                    ChoiceCard(
                        letter = "C",
                        cooldown = "15-day trade hold",
                        recommended = false,
                        title = "Remove with revocation code and link again",
                        subtitle = "Use when moving is not possible. Removes the current authenticator with your " +
                            "revocation code (R#####) and links a new one to SMA.",
                        onClick = { chooseMethod(MoveMethod.REVOCATION) },
                    )
                }

                Step.REVOKE_WARNING -> {
                    Text(
                        text = "Removing the authenticator with your revocation code has these consequences:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "- 15 days without trading and Community Market\n" +
                            "- Until then the account is protected only by email codes\n" +
                            "- The Steam app stops working for this account immediately\n" +
                            "- Your old revocation code becomes invalid; a new one is issued",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    WarningCard("This operation cannot be undone.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = revokeUnderstood, onCheckedChange = { revokeUnderstood = it })
                        Text("I understand the 15-day hold", style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(
                        onClick = { step = Step.SIGN_IN },
                        enabled = revokeUnderstood,
                    ) {
                        Text("Continue")
                    }
                }

                Step.SIGN_IN -> {
                    Text(
                        text = if (method == MoveMethod.WITHOUT_PHONE) {
                            "Sign in with your password only. Steam asks for no code. The password is used once and is not stored."
                        } else {
                            "Sign in to the Steam account. The password is used once and is not stored."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = accountName,
                        onValueChange = { accountName = it },
                        enabled = session == null && !busy,
                        singleLine = true,
                        label = { Text("Account name") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        enabled = session == null && !busy,
                        singleLine = true,
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    when (requiredType) {
                        DEVICE_CODE_TYPE -> OutlinedTextField(
                            value = guardCode,
                            onValueChange = { guardCode = it },
                            enabled = !busy,
                            singleLine = true,
                            label = { Text("Steam Guard code from the Steam app") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        EMAIL_CODE_TYPE -> OutlinedTextField(
                            value = guardCode,
                            onValueChange = { guardCode = it },
                            enabled = !busy,
                            singleLine = true,
                            label = { Text("Code from email") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        else -> Unit
                    }
                    if (session != null) {
                        val deviceApproval = CONFIRM_ON_DEVICE_TYPE in allowedConfirmations
                        val emailApproval = CONFIRM_ON_EMAIL_TYPE in allowedConfirmations
                        when {
                            requiredType == DEVICE_CODE_TYPE && deviceApproval -> Text(
                                text = "Approve the sign-in in the Steam app, or enter its Steam Guard code below.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            requiredType == DEVICE_CODE_TYPE -> Text(
                                text = "Enter the Steam Guard code shown in the Steam app.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            requiredType == null && (deviceApproval || emailApproval) -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (deviceApproval) {
                                        "Approve the sign-in in the Steam app. Waiting…"
                                    } else {
                                        "Approve the sign-in from the email Steam sent. Waiting…"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                if (method == MoveMethod.WITHOUT_PHONE) beginWithoutPhone() else signIn()
                            },
                            enabled = !busy && (session != null || (accountName.isNotBlank() && password.isNotEmpty())),
                        ) {
                            Text(if (session == null) "Sign in" else "Continue")
                        }
                        if (busy) {
                            Spacer(modifier = Modifier.width(12.dp))
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                    if (method == MoveMethod.REVOCATION && session == null) {
                        TextButton(
                            onClick = { beginRevocationWithoutPhone() },
                            enabled = !busy && accountName.isNotBlank() && password.isNotEmpty(),
                        ) {
                            Text("I don't have the old phone")
                        }
                    }
                }

                Step.SEND_SMS -> {
                    Text(
                        text = "Steam sends a verification code by SMS to the phone on the account, or by email if there is no phone.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { sendSms() }, enabled = !busy) {
                            Text("Send verification code")
                        }
                        if (busy) {
                            Spacer(modifier = Modifier.width(12.dp))
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                    if (rejectedWithoutPhone) {
                        TextButton(onClick = { backToChoice() }, enabled = !busy) {
                            Text("Choose another option")
                        }
                    }
                }

                Step.REVOKE_CODE -> {
                    Text(
                        text = "Enter your revocation code. It is R followed by five digits. " +
                            "Steam removes the current authenticator with it.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = revocationInput,
                        onValueChange = { revocationInput = it.uppercase() },
                        enabled = !busy,
                        singleLine = true,
                        label = { Text("Revocation code (R#####)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { removeWithRevocationCode() },
                            enabled = !busy && REVOCATION_CODE_REGEX.matches(revocationInput.trim().uppercase()),
                        ) {
                            Text("Remove authenticator")
                        }
                        if (busy) {
                            Spacer(modifier = Modifier.width(12.dp))
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }

                Step.REVOKE_LINK -> {
                    WarningCard(
                        "The authenticator is removed from the Steam app. Until the code is confirmed, " +
                            "this account has no working authenticator. Do not leave this screen.",
                    )
                    Text(
                        text = "Link a new authenticator to SMA. Steam sends a verification code by SMS to the phone on the account, or by email if there is no phone.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { linkWithSms() }, enabled = !busy) {
                            Text("Link to SMA (sends a code)")
                        }
                        if (busy) {
                            Spacer(modifier = Modifier.width(12.dp))
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }

                Step.CONFIRM -> {
                    val dump = unsavedJson
                    if (dump != null) {
                        WarningCard("Saving failed. Copy this now, it is your only copy.")
                        SelectionContainer {
                            Text(
                                text = dump,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Button(onClick = { SecureClipboard.copy(context, "maFile", dump) }) {
                            Text("Copy")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = unsavedCopied, onCheckedChange = { unsavedCopied = it })
                            Text("I have copied this data somewhere safe", style = MaterialTheme.typography.bodyMedium)
                        }
                        Button(onClick = { finishToAccounts() }, enabled = unsavedCopied) {
                            Text("Close")
                        }
                    } else {
                        if (method == MoveMethod.REVOCATION) {
                            // Shown before the code entry so the user can write it down first.
                            Text(
                                text = "New revocation code",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            SelectionContainer {
                                Text(
                                    text = savedAccount?.revocationCode.orEmpty(),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            Text(
                                text = "Write it down now. Your old revocation code no longer works.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            WarningCard(
                                "The account is not finished until the code is accepted. " +
                                    "Enter the code from SMS or email to finish.",
                            )
                        } else {
                            WarningCard(
                                "After this step the Steam app stops working for this account. " +
                                    "Steam issues new secrets and the old ones stop working. " +
                                    "The Steam app stops generating codes for this account. " +
                                    "Moving gives a 2-day trade hold. Removing with the revocation code gives a 15-day hold.",
                            )
                        }
                        linkSentTo?.let {
                            Text(
                                text = "Code sent by $it.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        OutlinedTextField(
                            value = smsCode,
                            onValueChange = { smsCode = it },
                            enabled = !busy && !locked,
                            singleLine = true,
                            label = { Text("Verification code (SMS or email)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                if (method == MoveMethod.REVOCATION) finalizeWithSms() else moveAuthenticator()
                            },
                            enabled = !busy && !locked && smsCode.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (method == MoveMethod.REVOCATION) "Confirm code" else "Move authenticator")
                        }
                        if (busy) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                        }
                    }
                }

                Step.DONE -> {
                    val account = savedAccount
                    if (account != null) {
                        Text(
                            text = "The authenticator is now in SMA.",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (needsManualSignIn) {
                            Text(
                                text = MANUAL_SIGN_IN_NOTE,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            text = "New revocation code",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        SelectionContainer {
                            Text(
                                text = account.revocationCode.orEmpty(),
                                style = MaterialTheme.typography.headlineMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Text(
                            text = "Write it down and keep it somewhere safe, for example in a password manager such as KeePass. You need it to remove the authenticator later.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = {
                                val safeName = account.accountName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                                exportLauncher.launch("$safeName.maFile")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Export .maFile")
                        }
                        Button(
                            onClick = {
                                SecureClipboard.copy(context, "otpauth URI", MaFile.otpauth(account))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Copy otpauth URI (password managers, e.g. KeePassXC)")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = revocationChecked,
                                onCheckedChange = { revocationChecked = it },
                            )
                            Text("I saved my revocation code", style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { finishToAccounts() },
                                enabled = revocationChecked && !busy,
                            ) {
                                Text("Finish")
                            }
                            if (busy) {
                                Spacer(modifier = Modifier.width(12.dp))
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }

            infoText?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            errorText?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ChoiceCard(
    letter: String,
    cooldown: String,
    recommended: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LetterBadge(letter)
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = cooldown,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            if (recommended) {
                Text(
                    text = "Recommended",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LetterBadge(letter: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(32.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = letter,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun WarningCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp),
        )
    }
}
