package com.ninja.scan.drive

import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption for Drive backup content, with the key derived from
 * a user-chosen backup password (PBKDF2) rather than stored anywhere in
 * plaintext — see [DriveBackup] for where the derived key itself is cached
 * on-device (wrapped by Android Keystore) and how the password is checked.
 *
 * Every encrypted blob/file starts with a 4-byte magic header, so decrypt
 * can tell an already-encrypted file apart from a plaintext one uploaded
 * before this feature existed instead of guessing from content — older
 * backups keep restoring as plaintext rather than failing outright.
 */
object BackupCrypto {

    const val PBKDF2_ITERATIONS = 210_000
    const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    // "NJSE" (Ninja Scan Encrypted) + format version 1.
    private val MAGIC = byteArrayOf('N'.code.toByte(), 'J'.code.toByte(), 'S'.code.toByte(), 1)
    private val VERIFIER_PLAINTEXT = "ninja-scan-backup-check".toByteArray(Charsets.UTF_8)

    fun randomSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    /** Derives a 256-bit AES key from a backup password; never stored itself. */
    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Encrypts a known constant, so a password can be checked without touching real data. */
    fun verifier(keyBytes: ByteArray): ByteArray = encryptBytes(VERIFIER_PLAINTEXT, keyBytes)

    fun verifyPassword(keyBytes: ByteArray, verifierBytes: ByteArray): Boolean =
        runCatching { decryptBytes(verifierBytes, keyBytes).contentEquals(VERIFIER_PLAINTEXT) }
            .getOrDefault(false)

    fun encryptBytes(data: ByteArray, keyBytes: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, ALGORITHM), GCMParameterSpec(GCM_TAG_BITS, iv))
        return MAGIC + iv + cipher.doFinal(data)
    }

    fun decryptBytes(data: ByteArray, keyBytes: ByteArray): ByteArray {
        require(isEncrypted(data)) { "Not an encrypted backup blob" }
        val iv = data.copyOfRange(MAGIC.size, MAGIC.size + IV_BYTES)
        val ciphertext = data.copyOfRange(MAGIC.size + IV_BYTES, data.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, ALGORITHM), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    fun isEncrypted(data: ByteArray): Boolean =
        data.size > MAGIC.size + IV_BYTES && data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    /** Streams [source] into [target] as an encrypted file (magic header + IV first). */
    fun encryptFile(source: File, target: File, keyBytes: ByteArray) {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, ALGORITHM), GCMParameterSpec(GCM_TAG_BITS, iv))
        target.outputStream().use { out ->
            out.write(MAGIC)
            out.write(iv)
            CipherOutputStream(out, cipher).use { cipherOut -> source.inputStream().use { it.copyTo(cipherOut) } }
        }
    }

    /** Streams an [encryptFile]-produced file back to plaintext at [target]. */
    fun decryptFile(source: File, target: File, keyBytes: ByteArray) {
        source.inputStream().use { input ->
            val header = ByteArray(MAGIC.size + IV_BYTES)
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                require(read >= 0) { "Not an encrypted backup file" }
                offset += read
            }
            require(header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Not an encrypted backup file" }
            val iv = header.copyOfRange(MAGIC.size, header.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, ALGORITHM), GCMParameterSpec(GCM_TAG_BITS, iv))
            target.outputStream().use { out -> CipherInputStream(input, cipher).use { it.copyTo(out) } }
        }
    }

    /** True when [file]'s leading bytes look like an [encryptFile] output. */
    fun fileIsEncrypted(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(MAGIC.size)
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read < 0) return@runCatching false
                offset += read
            }
            header.contentEquals(MAGIC)
        }
    }.getOrDefault(false)
}
