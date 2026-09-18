package com.eko.dcvisits.app.data.repo

import android.os.Build
import com.eko.dcvisits.app.data.db.AppDatabase
import com.eko.dcvisits.app.data.net.ApiClient
import com.eko.dcvisits.app.data.net.C2Api
import com.eko.dcvisits.app.data.net.DeviceDto
import com.eko.dcvisits.app.data.net.HardwareDto
import com.eko.dcvisits.app.data.net.OtpRequestBody
import com.eko.dcvisits.app.data.net.OtpVerifyBody
import com.eko.dcvisits.app.data.net.RefreshBody
import com.eko.dcvisits.app.data.net.bodyOrThrow
import com.eko.dcvisits.app.data.crypto.DeviceKey
import com.eko.dcvisits.app.data.session.Session
import com.eko.dcvisits.app.data.session.SessionStore
import kotlinx.coroutines.flow.Flow

class AuthRepository(
    private val session: SessionStore,
    private val db: AppDatabase,
    private val api: C2Api = ApiClient.api,
) {
    val sessionFlow: Flow<Session?> = session.sessionFlow

    suspend fun current(): Session? = session.current()

    /** POST /auth/otp/request — dev gateway stubs OTP 000000 (PILOT_OTP overrides). */
    suspend fun requestOtp(phone: String) {
        api.requestOtp(OtpRequestBody(phone)).let {
            if (!it.isSuccessful && it.code() != 204) it.bodyOrThrow()
        }
    }

    /**
     * POST /auth/otp/verify — establishes the session and binds this device.
     * [name] is only required the first time a brand-new number logs in (the
     * server self-registers it as a DC); omit it for a returning user. A 422
     * ([com.eko.dcvisits.app.data.net.ApiException.status]) means the server
     * needs a name — the caller should re-invoke with one.
     */
    suspend fun verifyOtp(phone: String, otp: String, name: String? = null): Session {
        val device = DeviceDto(
            hardware = HardwareDto(
                manufacturer = Build.MANUFACTURER ?: "unknown",
                model = Build.MODEL ?: "unknown",
                os_version = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            ),
            public_key = DeviceKey.publicKeyBase64(),
        )
        val res = api.verifyOtp(OtpVerifyBody(phone, otp, name, device)).bodyOrThrow()
        val s = Session(res.access_token, res.refresh_token, res.device_id, res.user)
        session.save(s)
        return s
    }

    /**
     * Silent re-auth for the OkHttp Authenticator (~1h access token expiry).
     * Rotates the refresh token. Returns the fresh access token, or null when
     * refresh itself fails — in which case the session is cleared and the app
     * falls back to the login screen. Never throws.
     */
    suspend fun tryRefresh(): String? {
        val s = session.current() ?: return null
        return try {
            val res = api.refresh(RefreshBody(s.refreshToken)).bodyOrThrow()
            session.save(s.copy(accessToken = res.access_token, refreshToken = res.refresh_token))
            res.access_token
        } catch (_: Exception) {
            session.clear()
            null
        }
    }

    /** Local sign-out. The outbox journal is kept — unsynced evidence is never dropped. */
    suspend fun signOut() {
        session.clear()
        db.cspCacheDao().deleteMissing(emptyList())
    }
}
