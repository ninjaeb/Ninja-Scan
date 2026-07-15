package com.ninja.scan.drive

import java.security.SecureRandom
import java.io.File
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption for Drive backup content, keyed by a randomly
 * generated 256-bit recovery key rather than a user-chosen password — there's
 * nothing to guess offline, and nothing to forget the wording of, but the
 * key itself must be saved somewhere by the user (a password manager, a
 * written-down copy) since — same as a password — losing it means the
 * backup can never be decrypted again. See [DriveBackup] for where the key
 * is cached on-device (wrapped by Android Keystore) and verified.
 *
 * Every encrypted blob/file starts with a 4-byte magic header, so decrypt
 * can tell an already-encrypted file apart from a plaintext one uploaded
 * before this feature existed instead of guessing from content — older
 * backups keep restoring as plaintext rather than failing outright.
 */
object BackupCrypto {

    const val KEY_BYTES = 32 // 256 bits
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    // "NJSE" (Ninja Scan Encrypted) + format version 1.
    private val MAGIC = byteArrayOf('N'.code.toByte(), 'J'.code.toByte(), 'S'.code.toByte(), 1)
    private val VERIFIER_PLAINTEXT = "ninja-scan-backup-check".toByteArray(Charsets.UTF_8)

    /** A fresh, uniformly random 256-bit key — nothing derived, nothing guessable. */
    fun generateKey(): ByteArray = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }

    /** The one-time recovery code shown to the user, encoding [key] for display/copy. */
    fun encodeRecoveryKey(key: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(key)

    /** Parses a recovery code back into key bytes, or null if it's not a valid one. */
    fun decodeRecoveryKey(code: String): ByteArray? = runCatching {
        val trimmed = code.trim().filterNot { it.isWhitespace() }
        Base64.getUrlDecoder().decode(trimmed).takeIf { it.size == KEY_BYTES }
    }.getOrNull()

    /** Encrypts a known constant, so a recovery key can be checked without touching real data. */
    fun verifier(keyBytes: ByteArray): ByteArray = encryptBytes(VERIFIER_PLAINTEXT, keyBytes)

    fun verifyKey(keyBytes: ByteArray, verifierBytes: ByteArray): Boolean =
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
