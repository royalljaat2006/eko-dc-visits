package com.eko.dcvisits.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eko.dcvisits.app.BuildConfig
import com.eko.dcvisits.app.data.net.ApiException
import com.eko.dcvisits.app.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val phone: String = "",
    val otp: String = "",
    val step: Step = Step.PHONE,
    val loading: Boolean = false,
    val error: String? = null,
) {
    enum class Step { PHONE, OTP }
    val phoneValid: Boolean get() = phone.matches(Regex("^[6-9][0-9]{9}$"))
    val otpValid: Boolean get() = otp.length in 4..8
}

class LoginViewModel : ViewModel() {
    private val auth = ServiceLocator.authRepository

    /** Debug builds skip the OTP screen (see build.gradle SKIP_OTP). */
    val skipOtp: Boolean = BuildConfig.SKIP_OTP

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state

    fun onPhone(v: String) = _state.update { it.copy(phone = v.filter(Char::isDigit).take(10), error = null) }
    fun onOtp(v: String) = _state.update { it.copy(otp = v.filter(Char::isDigit).take(8), error = null) }
    fun back() = _state.update { it.copy(step = LoginUiState.Step.PHONE, otp = "", error = null) }

    fun requestOtp() {
        val s = _state.value
        if (!s.phoneValid || s.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { auth.requestOtp(s.phone) }
                .onSuccess { _state.update { it.copy(loading = false, step = LoginUiState.Step.OTP) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.readable()) } }
        }
    }

    /** One-step login for testing: number only, dev-stub OTP sent for you. */
    fun loginDirect(onSuccess: () -> Unit) {
        val s = _state.value
        if (!s.phoneValid || s.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                auth.requestOtp(s.phone)
                auth.verifyOtp(s.phone, "000000")
            }
                .onSuccess { _state.update { it.copy(loading = false) }; onSuccess() }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.readable()) } }
        }
    }

    fun verify(onSuccess: () -> Unit) {
        val s = _state.value
        if (!s.otpValid || s.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { auth.verifyOtp(s.phone, s.otp) }
                .onSuccess {
                    _state.update { it.copy(loading = false) }
                    onSuccess()
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.readable()) } }
        }
    }

    private fun Throwable.readable(): String = when (this) {
        is ApiException -> if (networkFailure) "Can't reach the server. Check your connection." else message
        else -> message ?: "Something went wrong"
    }
}
