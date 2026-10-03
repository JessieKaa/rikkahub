package me.rerere.rikkahub.data.familymode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyPinCryptoCoreTest {

    private val crypto = FamilyPinCrypto { 500 }

    @Test
    fun `valid pin has at least six digits`() {
        assertTrue(FamilyPinCrypto.isValidPin("123456".toCharArray()))
        assertTrue(FamilyPinCrypto.isValidPin("000000".toCharArray()))
        assertFalse(FamilyPinCrypto.isValidPin("12345".toCharArray()))
        assertFalse(FamilyPinCrypto.isValidPin("12345a".toCharArray()))
        assertFalse(FamilyPinCrypto.isValidPin("".toCharArray()))
    }

    @Test
    fun `create and verify round trip`() {
        val record = crypto.createPinRecord("123456".toCharArray())
        assertTrue(crypto.verify("123456".toCharArray(), record))
        assertFalse(crypto.verify("654321".toCharArray(), record))
    }

    @Test
    fun `salt and hash differ between records for same pin`() {
        val first = crypto.createPinRecord("123456".toCharArray())
        val second = crypto.createPinRecord("123456".toCharArray())
        assertNotEquals(first.salt, second.salt)
        assertNotEquals(first.hash, second.hash)
        assertTrue(crypto.verify("123456".toCharArray(), first))
        assertTrue(crypto.verify("123456".toCharArray(), second))
    }

    @Test
    fun `tampered or incompatible records never verify`() {
        val record = crypto.createPinRecord("123456".toCharArray())
        assertFalse(crypto.verify("123456".toCharArray(), record.copy(hash = "AAAA")))
        assertFalse(crypto.verify("123456".toCharArray(), record.copy(algorithm = "MD5")))
        assertFalse(crypto.verify("123456".toCharArray(), record.copy(salt = "!!not-base64!!")))
        assertFalse(crypto.verify("123456".toCharArray(), record.copy(iterations = 0)))
    }

    @Test
    fun `pin record never contains plaintext pin`() {
        val record = crypto.createPinRecord("987654".toCharArray())
        assertFalse(record.hash.contains("987654"))
        assertFalse(record.salt.contains("987654"))
        assertTrue(record.hash.isNotBlank())
        assertTrue(record.salt.isNotBlank())
    }
}
