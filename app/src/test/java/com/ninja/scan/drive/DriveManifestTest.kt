package com.ninja.scan.drive

import com.ninja.scan.data.BusinessCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveManifestTest {

    private fun sampleContent() = DriveManifest.Content(
        scans = listOf(
            DriveManifest.ScanEntry(
                driveFileId = "file-1",
                title = "Invoice",
                createdAt = 111L,
                pageCount = 3,
                folder = "Receipts",
                watermark = "CONFIDENTIAL",
                watermarkBaked = false,
                ocrText = "some recognized text",
            ),
        ),
        cards = listOf(
            DriveManifest.CardEntry(
                key = "222-1",
                card = BusinessCard(
                    name = "Jane Smith", phone = "+1 555", createdAt = 222L,
                    photoDriveFileId = "photo-1",
                ),
            ),
        ),
        folders = listOf("Receipts", "Invoices"),
        folderColors = mapOf("Receipts" to "#EF5350", "Invoices" to "#42A5F5"),
    )

    @Test
    fun `encode then decode round-trips every field`() {
        val content = sampleContent()
        val decoded = DriveManifest.decode(DriveManifest.encode(content, 999L))

        assertTrue(decoded != null)
        assertEquals(content.scans, decoded!!.scans)
        assertEquals(
            content.cards.map { it.key to it.card.name to it.card.photoDriveFileId },
            decoded.cards.map { it.key to it.card.name to it.card.photoDriveFileId },
        )
        assertEquals(content.folders, decoded.folders)
        assertEquals(content.folderColors, decoded.folderColors)
    }

    @Test
    fun `decode of garbage bytes returns null`() {
        val decoded = DriveManifest.decode("not json at all".toByteArray())
        assertNull(decoded)
    }

    @Test
    fun `decode tolerates missing optional fields`() {
        val bytes = """{"schemaVersion":1,"updatedAt":1,"scans":[{"driveFileId":"f1"}]}"""
            .toByteArray()
        val decoded = DriveManifest.decode(bytes)

        assertTrue(decoded != null)
        assertEquals(1, decoded!!.scans.size)
        assertEquals("f1", decoded.scans.first().driveFileId)
        assertEquals("", decoded.scans.first().title)
        assertNull(decoded.scans.first().folder)
        assertNull(decoded.scans.first().watermark)
        assertTrue(decoded.cards.isEmpty())
        assertTrue(decoded.folders.isEmpty())
    }

    @Test
    fun `decode tolerates a card entry with no photoDriveFileId`() {
        val bytes = """
            {"schemaVersion":1,"updatedAt":1,"scans":[],
             "cards":[{"key":"1-1","name":"X","createdAt":1}]}
        """.trimIndent().toByteArray()
        val decoded = DriveManifest.decode(bytes)

        assertTrue(decoded != null)
        assertEquals(1, decoded!!.cards.size)
        assertNull(decoded.cards.first().card.photoDriveFileId)
    }

    @Test
    fun `cardKey is stable for a given card`() {
        val card = BusinessCard(id = 7, name = "X", createdAt = 555L)
        assertEquals("555-7", DriveManifest.cardKey(card))
    }
}
