package com.tacresq.app.ui.auth

import android.util.Patterns
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tacresq.app.BuildConfig
import com.tacresq.app.R
import com.tacresq.app.data.repo.TacRepository
import com.tacresq.app.security.PasswordBreachChecker
import com.tacresq.app.security.CompromisedPasswordException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val nameError: String? = null,
    val emailError: String? = null,
    val phoneError: String? = null,
    val passwordError: String? = null,
)

class AuthViewModel(private val repo: TacRepository) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun login(email: String, password: String) {
        val emailError = when {
            email.isBlank() -> "Enter your email address."
            !Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() -> "Enter a valid email address."
            else -> null
        }
        val passwordError = if (password.isBlank()) "Enter your password." else null
        if (emailError != null || passwordError != null) {
            _state.value = AuthUiState(emailError = emailError, passwordError = passwordError)
            return
        }
        submit { repo.login(email.trim(), password) }
    }

    fun register(name: String, email: String, phone: String, password: String) {
        val nameError = if (name.isBlank()) "Enter your full name." else null
        val emailError = when {
            email.isBlank() -> "Enter your email address."
            !Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() -> "Enter a valid email address."
            else -> null
        }
        val phoneDigits = phone.count { it.isDigit() }
        val phoneError = when {
            phone.isBlank() -> "Enter your mobile number so responders can reach you."
            phone.any { !it.isDigit() && it !in "+- ()" } ->
                "Use digits and optional +, spaces, hyphens, or parentheses."
            phoneDigits !in 7..15 -> "Enter a valid mobile number with 7–15 digits."
            else -> null
        }
        val passwordError = when {
            password.isBlank() -> "Enter a password."
            password.length < 8 -> "Use at least 8 characters."
            else -> null
        }
        if (nameError != null || emailError != null || phoneError != null || passwordError != null) {
            _state.value = AuthUiState(
                nameError = nameError,
                emailError = emailError,
                phoneError = phoneError,
                passwordError = passwordError,
            )
            return
        }
        submit {
            if (!BuildConfig.USE_FAKE_API && PasswordBreachChecker.isCompromised(password)) {
                throw CompromisedPasswordException()
            }
            repo.register(name.trim(), email.trim(), phone.trim(), password)
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(
            error = null,
            nameError = null,
            emailError = null,
            phoneError = null,
            passwordError = null,
        )
    }

    fun clearFieldError(field: String) {
        _state.value = when (field) {
            "name" -> _state.value.copy(error = null, nameError = null)
            "email" -> _state.value.copy(error = null, emailError = null)
            "phone" -> _state.value.copy(error = null, phoneError = null)
            "password" -> _state.value.copy(error = null, passwordError = null)
            else -> _state.value.copy(error = null)
        }
    }

    /** On success the session flips to LoggedIn and AppRoot swaps this screen out by itself. */
    private fun submit(call: suspend () -> Result<Unit>) {
        if (_state.value.loading) return
        // Claim the operation synchronously so rapid repeated taps cannot launch
        // duplicate login/signup requests before the coroutine starts running.
        _state.value = AuthUiState(loading = true)
        viewModelScope.launch {
            val result = try {
                call()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }
            val failure = result.exceptionOrNull()
            _state.value = AuthUiState(
                error = when {
                    failure is CompromisedPasswordException -> null
                    failure != null -> failure.message?.takeIf { it.isNotBlank() }
                        ?: "Something went wrong. Please try again."
                    else -> null
                },
                passwordError = if (failure is CompromisedPasswordException) {
                    "This password appears in known data breaches. Choose a different password."
                } else null,
            )
        }
    }
}

@Composable
fun AuthScreen(
    vm: AuthViewModel,
    infoMessage: String? = null,
    onInfoMessageDismissed: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var registering by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_tacresq_emergency_launcher),
                contentDescription = "TACResQ app icon",
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "TACResQ",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Emergency GPS and response coordination",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    if (registering) "Create a requester account" else "Welcome back",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (registering) {
                        "Enter your details to request emergency assistance."
                    } else {
                        "Sign in to access your role-specific emergency tools."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                infoMessage?.let { message ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            TextButton(
                                onClick = onInfoMessageDismissed,
                                modifier = Modifier.align(Alignment.End),
                            ) { Text("Dismiss") }
                        }
                    }
                }

                if (BuildConfig.USE_FAKE_API && !registering) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "DEMO ACCOUNTS",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "Debug build only · demo data resets when the app process restarts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TextButton(
                                onClick = { email = "requester@demo.com"; password = "demo123" },
                                modifier = Modifier.weight(1f),
                            ) { Text("Requester") }
                            TextButton(
                                onClick = { email = "responder@demo.com"; password = "demo123" },
                                modifier = Modifier.weight(1f),
                            ) { Text("Responder") }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TextButton(
                                onClick = { email = "driver@demo.com"; password = "demo123" },
                                modifier = Modifier.weight(1f),
                            ) { Text("Driver") }
                            TextButton(
                                onClick = { email = "dispatcher@demo.com"; password = "demo123" },
                                modifier = Modifier.weight(1f),
                            ) { Text("Dispatcher") }
                        }
                    }
                }

                if (registering) {
                    Text(
                        "All four fields are required. Staff and administrator accounts are provisioned separately; public sign-up creates requester accounts only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; vm.clearFieldError("name") },
                        label = { Text("Full name (required)") },
                        singleLine = true,
                        isError = state.nameError != null,
                        supportingText = { state.nameError?.let { Text(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it; vm.clearFieldError("phone") },
                        label = { Text("Mobile number (required)") },
                        singleLine = true,
                        isError = state.phoneError != null,
                        supportingText = { state.phoneError?.let { Text(it) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; vm.clearFieldError("email") },
                    label = { Text("Email address (required)") },
                    singleLine = true,
                    isError = state.emailError != null,
                    supportingText = { state.emailError?.let { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; vm.clearFieldError("password") },
                    label = { Text("Password (required)") },
                    singleLine = true,
                    isError = state.passwordError != null,
                    supportingText = { state.passwordError?.let { Text(it) } },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )

                state.error?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Button(
                    onClick = {
                        if (registering) vm.register(name, email, phone, password) else vm.login(email, password)
                    },
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    if (state.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(if (registering) "Create account" else "Sign in")
                    }
                }

                TextButton(
                    onClick = {
                        registering = !registering
                        vm.clearError()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (registering) "I already have an account" else "New requester? Create an account")
                }
            }
        }
    }
}
