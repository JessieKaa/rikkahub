package me.rerere.rikkahub.data.familymode

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN 派生与校验。使用 PBKDF2-HMAC-SHA256 + 随机盐。
 *
 * 依赖注入 [defaultIterations] 便于测试使用低成本参数；生产默认按目标设备校准。
 */
open class FamilyPinCrypto(
    private val defaultIterations: () -> Int = { calibrateIterations() },
) {    companion object {
        const val ALGORITHM = "PBKDF2WithHmacSHA256"
        const val MIN_PIN_LENGTH = 6
        const val DEFAULT_KEY_LENGTH_BITS = 256
        const val MIN_ITERATIONS = 10_000
        const val MAX_ITERATIONS = 600_000
        const val DEFAULT_TARGET_MILLIS = 250L
        private const val SALT_BYTES = 16
        private val secureRandom = SecureRandom()

        /** 至少 6 位纯数字 PIN。 */
        fun isValidPin(pin: CharArray): Boolean =
            pin.size >= MIN_PIN_LENGTH && pin.all { it in '0'..'9' }

        /**
         * 按目标设备校准工作参数：逐步加倍迭代次数直到单次派生耗时达到目标。
         * 结果夹在 [MIN_ITERATIONS] 与 [MAX_ITERATIONS] 之间。
         */
        fun calibrateIterations(targetMillis: Long = DEFAULT_TARGET_MILLIS): Int {
            var iterations = MIN_ITERATIONS
            val salt = ByteArray(SALT_BYTES)
            while (iterations < MAX_ITERATIONS) {
                val started = System.nanoTime()
                derive("000000".toCharArray(), salt, iterations, DEFAULT_KEY_LENGTH_BITS)
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                if (elapsedMs >= targetMillis) break
                iterations = (iterations * 2).coerceAtMost(MAX_ITERATIONS)
            }
            return iterations
        }

        private fun derive(
            pin: CharArray,
            salt: ByteArray,
            iterations: Int,
            keyLengthBits: Int,
        ): ByteArray {
            val spec = PBEKeySpec(pin, salt, iterations, keyLengthBits)
            return try {
                SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }
    }

    /** 使用默认（可能已校准）工作参数创建校验记录。 */
    fun createPinRecord(pin: CharArray): FamilyPinRecord = createPinRecord(pin, defaultIterations())

    /** 使用显式迭代次数创建校验记录。 */
    fun createPinRecord(pin: CharArray, iterations: Int): FamilyPinRecord {
        require(iterations >= 1) { "iterations must be positive" }
        val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        val hash = derive(pin, salt, iterations, DEFAULT_KEY_LENGTH_BITS)
        return FamilyPinRecord(
            algorithm = ALGORITHM,
            salt = Base64.getEncoder().encodeToString(salt),
            iterations = iterations,
            keyLengthBits = DEFAULT_KEY_LENGTH_BITS,
            hash = Base64.getEncoder().encodeToString(hash),
        )
    }

    /** 恒定时间比较；记录损坏或算法不匹配时返回 false。 */
    open fun verify(pin: CharArray, record: FamilyPinRecord): Boolean {
        if (record.algorithm != ALGORITHM) return false
        if (record.iterations < 1 || record.keyLengthBits < 1) return false
        return try {
            val salt = Base64.getDecoder().decode(record.salt)
            val expected = Base64.getDecoder().decode(record.hash)
            val actual = derive(pin, salt, record.iterations, record.keyLengthBits)
            MessageDigest.isEqual(expected, actual)
        } catch (_: Exception) {
            false
        }
    }
}
