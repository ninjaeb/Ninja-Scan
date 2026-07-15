package com.ninja.scan.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupCryptoTest {

    @Test
    fun `bytes round-trip through encrypt and decrypt`() {
        val key = BackupCrypto.deriveKey("hunter2".toCharArray(), BackupCrypto.randomSalt())
        val plaintext = "the quick brown fox".toByteArray()

        val encrypted = BackupCrypto.encryptBytes(plaintext, key)

        assertTrue(BackupCrypto.isEncrypted(encrypted))
        assertEquals(String(plaintext), String(BackupCrypto.decryptBytes(encrypted, key)))
    }

    @Test
    fun `decrypt fails with the wrong key`() {
        val salt = BackupCrypto.randomSalt()
        val key = BackupCrypto.deriveKey("correct-password".toCharArray(), salt)
        val wrongKey = BackupCrypto.deriveKey("wrong-password".toCharArray(), salt)
        val encrypted = BackupCrypto.encryptBytes("secret".toByteArray(), key)

        assertFalse(runCatching { BackupCrypto.decryptBytes(encrypted, wrongKey) }.isSuccess)
    }

    @Test
    fun `verifier confirms the correct password and rejects a wrong one`() {
        val salt = BackupCrypto.randomSalt()
        val key = BackupCrypto.deriveKey("my-backup-password".toCharArray(), salt)
        val wrongKey = BackupCrypto.deriveKey("not-my-password".toCharArray(), salt)
        val verifier = BackupCrypto.verifier(key)

        assertTrue(BackupCrypto.verifyPassword(key, verifier))
        assertFalse(BackupCrypto.verifyPassword(wrongKey, verifier))
    }

    @Test
    fun `plaintext bytes are not mistaken for encrypted`() {
        assertFalse(BackupCrypto.isEncrypted("%PDF-1.4 not encrypted".toByteArray()))
        assertFalse(BackupCrypto.isEncrypted(ByteArray(0)))
    }

    @Test
    fun `files round-trip through encrypt and decrypt`() {
        val key = BackupCrypto.deriveKey("file-password".toCharArray(), BackupCrypto.randomSalt())
        val source = File.createTempFile("plain", ".pdf")
        val encrypted = File.createTempFile("enc", ".bin")
        val decrypted = File.createTempFile("dec", ".pdf")
        try {
            val payload = ByteArray(50_000) { (it % 251).toByte() } // larger than one cipher block
            source.writeBytes(payload)

            BackupCrypto.encryptFile(source, encrypted, key)

            assertTrue(BackupCrypto.fileIsEncrypted(encrypted))
            assertFalse(BackupCrypto.fileIsEncrypted(source))

            BackupCrypto.decryptFile(encrypted, decrypted, key)

            assertTrue(payload.contentEquals(decrypted.readBytes()))
        } finally {
            source.delete(); encrypted.delete(); decrypted.delete()
        }
    }

    @Test
    fun `decrypting a plaintext file is rejected instead of returning garbage`() {
        val key = BackupCrypto.deriveKey("password".toCharArray(), BackupCrypto.randomSalt())
        val plain = File.createTempFile("plain", ".pdf")
        val out = File.createTempFile("out", ".pdf")
        try {
            plain.writeBytes("%PDF-1.4 ...".toByteArray())
            assertFalse(runCatching { BackupCrypto.decryptFile(plain, out, key) }.isSuccess)
        } finally {
            plain.delete(); out.delete()
        }
    }
}
