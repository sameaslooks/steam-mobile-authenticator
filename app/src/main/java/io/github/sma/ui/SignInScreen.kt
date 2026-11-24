package io.github.sma.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.sma.core.crypto.Secrets
import io.github.sma.core.crypto.SteamTotp
import io.github.sma.core.steam.AuthApi
import io.github.sma.core.steam.SessionManager
import io.github.sma.core.steam.SteamApiException
import io.github.sma.core.steam.SteamTime
import io.github.sma.core.steam.userMessage
import io.github.sma.data.AccountRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

// Steam guard code types (also the allowedConfirmations values).
private const val EMAIL_CODE_TYPE = 2
private const val DEVICE_CODE_TYPE = 3
private const val CONFIRM_ON_DEVICE_TYPE = 4
private const val CONFIRM_ON_EMAIL_TYPE = 5
private const val ERESULT_INVALID_PASSWORD = 5
private const val ERESULT_RATE_LIMITED_A = 84
private const val ERESULT_RATE_LIMITED_B = 87

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(steamId: String, nav: NavController) {
    val account = remember(steamId) { AccountRepository.find(steamId) }
    val scope = rememberCoroutineScope()

    var password by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<AuthApi.LoginSession?>(null) }
    var emailCodeRequired by remember { mutableStateOf(false) }
    var emailCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign in to Steam") },
                navigationIcon = {
                    TextButton(onClick = { nav.popBackStack() }) { Text("Back") }
                },
            )
        },
    ) { innerPadding ->
        if (account == null) {
            Column(modifier = Modifier.padding(innerPadding).padding(16.dp)) {
                Text("Account not found")
            }
        } else {
            fun submit() {
                if (busy) return
                scope.launch {
                    busy = true
                    error = null
                    info = null
                    try {
                        val current = session
                            ?: AuthApi.begin(account.accountName, password).also { session = it }

                        if (emailCodeRequired) {
                            val code = emailCode.trim()
                            if (code.isEmpty()) {
                                error = "Enter the code from email"
                                return@launch
                            }
                            AuthApi.submitCode(current, code, EMAIL_CODE_TYPE)
                        } else {
                            val allowed = current.allowedConfirmations
                            when {
                                DEVICE_CODE_TYPE in allowed -> {
                                    if (SteamTime.isStale() && !SteamTime.sync()) {
                                        throw IOException(SteamTime.lastError ?: "Time sync failed")
                                    }
                                    val code = SteamTotp.code(
                                        Secrets.decode(account.sharedSecret),
                                        SteamTime.now(),
                                    )
                                    AuthApi.submitCode(current, code, DEVICE_CODE_TYPE)
                                }
                                EMAIL_CODE_TYPE in allowed -> {
                                    emailCodeRequired = true
                                    info = "Enter the code sent to your email."
                                    return@launch
                                }
                                CONFIRM_ON_DEVICE_TYPE in allowed || CONFIRM_ON_EMAIL_TYPE in allowed -> {
                                    info = "Confirm the sign-in on the device/email that holds the authenticator."
                                }
                            }
                        }

                        val tokens = AuthApi.poll(current)
                        if (current.steamId.toString() != steamId) {
                            session = null
                            emailCodeRequired = false
                            error = "Signed in to a different account"
                            return@launch
                        }

                        AccountRepository.updateRefreshToken(steamId, tokens.refreshToken)
                        SessionManager.invalidate(steamId)
                        password = ""
                        emailCode = ""
                        nav.popBackStack()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: SteamApiException) {
                        error = when (e.eresult) {
                            ERESULT_INVALID_PASSWORD -> "Wrong password"
                            ERESULT_RATE_LIMITED_A, ERESULT_RATE_LIMITED_B -> "Too many attempts, try later"
                            else -> e.userMessage()
                        }
                    } catch (e: Exception) {
                        error = e.userMessage()
                    } finally {
                        busy = false
                    }
                }
            }

            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .padding(16.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = account.accountName,
                    onValueChange = {},
                    readOnly = true,
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
                if (emailCodeRequired) {
                    OutlinedTextField(
                        value = emailCode,
                        onValueChange = { emailCode = it },
                        enabled = !busy,
                        singleLine = true,
                        label = { Text("Code from email") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                info?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { submit() },
                        enabled = !busy && (session != null || password.isNotEmpty()),
                    ) {
                        Text(if (emailCodeRequired) "Submit code" else "Sign in")
                    }
                    if (busy) {
                        Spacer(Modifier.width(12.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
        }
    }
}
