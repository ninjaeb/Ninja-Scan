package com.ninja.scan.drive

import com.ninja.scan.data.BusinessCard
import org.json.JSONArray
import org.json.JSONObject

/**
 * The JSON library manifest stored alongside the PDFs in the Drive backup
 * folder. It carries everything a PDF file alone cannot: titles, folders,
 * editable watermark text, OCR text, and the full business-card list — so a
 * restore after reinstall/data-clear rebuilds the library losslessly.
 */
internal object DriveManifest {

    const val FILE_NAME = "ninja-scan-manifest.json"
    private const val SCHEMA_VERSION = 1

    data class ScanEntry(
        val driveFileId: String,
        val title: String,
        val createdAt: Long,
        val pageCount: Int,
        val folder: String?,
        val watermark: String?,
        /** True for legacy uploads that had the watermark baked into pages. */
        val watermarkBaked: Boolean,
        val ocrText: String,
    )

    data class CardEntry(val key: String, val card: BusinessCard)

    data class Content(
        val scans: List<ScanEntry>,
        val cards: List<CardEntry>,
        val folders: List<String>,
    )

    /** Stable dedupe key for a card across backup/restore cycles. */
    fun cardKey(card: BusinessCard): String = "${card.createdAt}-${card.id}"

    fun encode(content: Content, updatedAt: Long): ByteArray {
        val root = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("updatedAt", updatedAt)
        val scans = JSONArray()
        for (entry in content.scans) {
            scans.put(
                JSONObject()
                    .put("driveFileId", entry.driveFileId)
                    .put("title", entry.title)
                    .put("createdAt", entry.createdAt)
                    .put("pageCount", entry.pageCount)
                    .put("folder", entry.folder ?: JSONObject.NULL)
                    .put("watermark", entry.watermark ?: JSONObject.NULL)
                    .put("watermarkBaked", entry.watermarkBaked)
                    .put("ocrText", entry.ocrText)
            )
        }
        root.put("scans", scans)
        val cards = JSONArray()
        for (entry in content.cards) {
            val card = entry.card
            cards.put(
                JSONObject()
                    .put("key", entry.key)
                    .put("name", card.name)
                    .put("company", card.company)
                    .put("jobTitle", card.jobTitle)
                    .put("phone", card.phone)
                    .put("email", card.email)
                    .put("website", card.website)
                    .put("address", card.address)
                    .put("notes", card.notes)
                    .put("tags", card.tags)
                    .put("createdAt", card.createdAt)
            )
        }
        root.put("cards", cards)
        root.put("folders", JSONArray(content.folders))
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    /** Parses manifest bytes; null when absent fields make it unusable. */
    fun decode(bytes: ByteArray): Content? = runCatching {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val scans = mutableListOf<ScanEntry>()
        val scansJson = root.optJSONArray("scans") ?: JSONArray()
        for (i in 0 until scansJson.length()) {
            val scan = scansJson.getJSONObject(i)
            scans.add(
                ScanEntry(
                    driveFileId = scan.getString("driveFileId"),
                    title = scan.optString("title"),
                    createdAt = scan.optLong("createdAt"),
                    pageCount = scan.optInt("pageCount"),
                    folder = scan.optStringOrNull("folder"),
                    watermark = scan.optStringOrNull("watermark"),
                    watermarkBaked = scan.optBoolean("watermarkBaked", false),
                    ocrText = scan.optString("ocrText"),
                )
            )
        }
        val cards = mutableListOf<CardEntry>()
        val cardsJson = root.optJSONArray("cards") ?: JSONArray()
        for (i in 0 until cardsJson.length()) {
            val card = cardsJson.getJSONObject(i)
            cards.add(
                CardEntry(
                    key = card.optString("key"),
                    card = BusinessCard(
                        name = card.optString("name"),
                        company = card.optString("company"),
                        jobTitle = card.optString("jobTitle"),
                        phone = card.optString("phone"),
                        email = card.optString("email"),
                        website = card.optString("website"),
                        address = card.optString("address"),
                        notes = card.optString("notes"),
                        tags = card.optString("tags"),
                        createdAt = card.optLong("createdAt"),
                    ),
                )
            )
        }
        val folders = mutableListOf<String>()
        val foldersJson = root.optJSONArray("folders") ?: JSONArray()
        for (i in 0 until foldersJson.length()) {
            foldersJson.optString(i).takeIf { it.isNotEmpty() }?.let(folders::add)
        }
        Content(scans, cards, folders)
    }.getOrNull()

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
}
