package com.eko.dcvisits.app.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey

/**
 * The device's non-exportable signing key (C3 §2: "device Keystore key over the
 * canonical envelope"). Generated once at enrollment, lives in the Android
 * Keystore, never leaves the phone. The public key is registered with the
 * server on OTP verify; the server records it in M0 and enforces signatures
 * later — signing now means no back-fill when it does.
 */
object DeviceKey {

    private const val ALIAS = "eko-dc-device-key"
    private const val PROVIDER = "AndroidKeyStore"

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun ensureKeyPair() {
        val ks = keyStore()
        if (ks.containsAlias(ALIAS)) return
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    /** X.509/SPKI DER of the public key, base64 — the value sent as `device.public_key`. */
    fun publicKeyBase64(): String? = runCatching {
        ensureKeyPair()
        val cert = keyStore().getCertificate(ALIAS) ?: return null
        val pub = cert.publicKey as? ECPublicKey ?: return null
        Base64.encodeToString(pub.encoded, Base64.NO_WRAP)
    }.getOrNull()

    /** base64 SHA256withECDSA signature over [data], or null if signing is unavailable. */
    fun sign(data: ByteArray): String? = runCatching {
        ensureKeyPair()
        val entry = keyStore().getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry ?: return null
        val privateKey: PrivateKey = entry.privateKey
        val sig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(data)
        }
        Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
    }.getOrNull()
}
