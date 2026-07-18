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
                tagTitles = listOf("Receipts"),
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
                tagTitles = listOf("Client"),
            ),
        ),
        tags = listOf(DriveManifest.TagEntry(title = "Client", description = "", color = "#42A5F5")),
        scanTags = listOf(DriveManifest.TagEntry(title = "Receipts", description = "", color = "#EF5350")),
        updatedAt = 999L,
    )

    @Test
    fun `encode then decode round-trips every field`() {
        val content = sampleContent()
        val decoded = DriveManifest.decode(DriveManifest.encode(content))

        assertTrue(decoded != null)
        assertEquals(content.scans, decoded!!.scans)
        assertEquals(
            content.cards.map { it.key to it.card.name to it.card.photoDriveFileId to it.tagTitles },
            decoded.cards.map { it.key to it.card.name to it.card.photoDriveFileId to it.tagTitles },
        )
        assertEquals(content.tags, decoded.tags)
        assertEquals(content.scanTags, decoded.scanTags)
        assertEquals(content.updatedAt, decoded.updatedAt)
    }

    @Test
    fun `decode of a legacy manifest with no updatedAt key defaults to zero`() {
        val bytes = """{"schemaVersion":1,"scans":[]}""".toByteArray()
        val decoded = DriveManifest.decode(bytes)

        assertTrue(decoded != null)
        assertEquals(0L, decoded!!.updatedAt)
    }

    @Test
    fun `decode tolerates a legacy manifest that still carries folder fields`() {
        val bytes = """
            {"schemaVersion":1,"updatedAt":1,
             "scans":[{"driveFileId":"f1","folder":"Receipts"}],
             "folders":["Receipts"],"folderColors":{"Receipts":"#EF5350"}}
        """.trimIndent().toByteArray()
        val decoded = DriveManifest.decode(bytes)

        assertTrue(decoded != null)
        assertEquals(1, decoded!!.scans.size)
        assertTrue(decoded.scans.first().tagTitles.isEmpty())
    }

    @Test
    fun `pickFreshest prefers the greatest updatedAt regardless of list order`() {
        val older = sampleContent().copy(updatedAt = 100L)
        val newer = sampleContent().copy(updatedAt = 200L)

        assertEquals(200L, DriveManifest.pickFreshest(listOf(newer, older))?.updatedAt)
        assertEquals(200L, DriveManifest.pickFreshest(listOf(older, newer))?.updatedAt)
    }

    @Test
    fun `pickFreshest ignores undecodable (null) candidates`() {
        val only = sampleContent().copy(updatedAt = 42L)
        assertEquals(42L, DriveManifest.pickFreshest(listOf(null, only, null))?.updatedAt)
        assertNull(DriveManifest.pickFreshest(listOf(null, null)))
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
        assertTrue(decoded.scans.first().tagTitles.isEmpty())
        assertNull(decoded.scans.first().watermark)
        assertTrue(decoded.cards.isEmpty())
        assertTrue(decoded.scanTags.isEmpty())
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
