package com.hanzilock.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** The master PIN: salted PBKDF2 hash, with a growing wait after repeated wrong guesses. */
class PinManager(private val settings: Settings) {
    sealed interface Result {
        data object Ok : Result
        data class Wrong(val attemptsLeft: Int) : Result
        data class LockedOut(val untilMs: Long) : Result
    }

    val isSet: Boolean get() = settings.pinHash != null && settings.pinSalt != null

    fun setPin(pin: String) {
        require(pin.length >= 4) { "PIN must have at least 4 digits" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        settings.pinSalt = Base64.getEncoder().encodeToString(salt)
        settings.pinHash = Base64.getEncoder().encodeToString(hash(pin, salt))
        settings.pinFailures = 0
        settings.pinLockedUntil = 0
    }

    fun verify(pin: String, now: Long = System.currentTimeMillis()): Result {
        if (settings.pinLockedUntil > now) return Result.LockedOut(settings.pinLockedUntil)
        val salt = settings.pinSalt?.let { Base64.getDecoder().decode(it) } ?: return Result.Wrong(0)
        val expected = settings.pinHash?.let { Base64.getDecoder().decode(it) } ?: return Result.Wrong(0)
        if (MessageDigest.isEqual(hash(pin, salt), expected)) {
            settings.pinFailures = 0
            return Result.Ok
        }
        val failures = settings.pinFailures + 1
        settings.pinFailures = failures
        if (failures >= FREE_ATTEMPTS) {
            val wait = 30_000L shl (failures - FREE_ATTEMPTS).coerceAtMost(5)   // 30 s ... 16 min
            settings.pinLockedUntil = now + wait
            return Result.LockedOut(now + wait)
        }
        return Result.Wrong(FREE_ATTEMPTS - failures)
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 60_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private companion object {
        const val FREE_ATTEMPTS = 5
    }
}
