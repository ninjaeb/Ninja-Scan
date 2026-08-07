package com.ninja.scan.drive

import com.ninja.scan.data.BusinessCard
import org.json.JSONArray
import org.json.JSONObject

/**
 * The JSON library manifest stored alongside the PDFs in the Drive backup
 * folder. It carries everything a PDF file alone cannot: titles, tags,
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
        val tagTitles: List<String> = emptyList(),
        val watermark: String?,
        /** True for legacy uploads that had the watermark baked into pages. */
        val watermarkBaked: Boolean,
        val ocrText: String,
        /** True for a front+back ID card scan — see ScanDocument.isIdCard. */
        val isIdCard: Boolean = false,
    )

    data class CardEntry(val key: String, val card: BusinessCard, val tagTitles: List<String> = emptyList())

    data class TagEntry(val title: String, val description: String, val color: String)

    data class Content(
        val scans: List<ScanEntry>,
        val cards: List<CardEntry>,
        /** Business Cards' tag catalog. */
        val tags: List<TagEntry> = emptyList(),
        /** Documents' tag catalog — a separate catalog from [tags]. */
        val scanTags: List<TagEntry> = emptyList(),
        /**
         * When this manifest was written (epoch millis). Restore reads this
         * back to pick the genuinely newest manifest across duplicate backup
         * folders, instead of trusting Drive's file/folder listing order —
         * see DriveRestoreWorker.
         */
        val updatedAt: Long = 0L,
    )

    /** Stable dedupe key for a card across backup/restore cycles. */
    fun cardKey(card: BusinessCard): String = "${card.createdAt}-${card.id}"

    fun encode(content: Content): ByteArray {
        val root = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("updatedAt", content.updatedAt)
        val scans = JSONArray()
        for (entry in content.scans) {
            scans.put(
                JSONObject()
                    .put("driveFileId", entry.driveFileId)
                    .put("title", entry.title)
                    .put("createdAt", entry.createdAt)
                    .put("pageCount", entry.pageCount)
                    .put("tagTitles", JSONArray(entry.tagTitles))
                    .put("watermark", entry.watermark ?: JSONObject.NULL)
                    .put("watermarkBaked", entry.watermarkBaked)
                    .put("ocrText", entry.ocrText)
                    .put("isIdCard", entry.isIdCard)
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
                    .put("tagTitles", JSONArray(entry.tagTitles))
                    .put("createdAt", card.createdAt)
                    .put("photoDriveFileId", card.photoDriveFileId ?: JSONObject.NULL)
            )
        }
        root.put("cards", cards)
        fun tagsArray(entries: List<TagEntry>) = JSONArray().apply {
            for (tag in entries) {
                put(
                    JSONObject()
                        .put("title", tag.title)
                        .put("description", tag.description)
                        .put("color", tag.color)
                )
            }
        }
        root.put("tags", tagsArray(content.tags))
        root.put("scanTags", tagsArray(content.scanTags))
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    /** Parses manifest bytes; null when absent fields make it unusable. */
    fun decode(bytes: ByteArray): Content? = runCatching {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        fun titlesArray(json: JSONObject, key: String): List<String> {
            val titles = mutableListOf<String>()
            val array = json.optJSONArray(key) ?: JSONArray()
            for (i in 0 until array.length()) {
                array.optString(i).takeIf { it.isNotEmpty() }?.let(titles::add)
            }
            return titles
        }
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
                    tagTitles = titlesArray(scan, "tagTitles"),
                    watermark = scan.optStringOrNull("watermark"),
                    watermarkBaked = scan.optBoolean("watermarkBaked", false),
                    ocrText = scan.optString("ocrText"),
                    isIdCard = scan.optBoolean("isIdCard", false),
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
                        createdAt = card.optLong("createdAt"),
                        photoDriveFileId = card.optStringOrNull("photoDriveFileId"),
                    ),
                    tagTitles = titlesArray(card, "tagTitles"),
                )
            )
        }
        fun tagsArray(key: String): List<TagEntry> {
            val entries = mutableListOf<TagEntry>()
            val array = root.optJSONArray(key) ?: JSONArray()
            for (i in 0 until array.length()) {
                val tag = array.getJSONObject(i)
                val title = tag.optString("title")
                if (title.isNotEmpty()) {
                    entries.add(
                        TagEntry(title = title, description = tag.optString("description"), color = tag.optString("color"))
                    )
                }
            }
            return entries
        }
        Content(
            scans, cards,
            tags = tagsArray("tags"),
            scanTags = tagsArray("scanTags"),
            updatedAt = root.optLong("updatedAt", 0L),
        )
    }.getOrNull()

    /**
     * Picks the manifest with the greatest [Content.updatedAt] among
     * [candidates] (nulls — undecodable copies — are ignored). Restore uses
     * this to pick the genuinely newest manifest across duplicate backup
     * folders, instead of trusting whichever copy Drive's API happened to
     * list first.
     */
    fun pickFreshest(candidates: List<Content?>): Content? =
        candidates.filterNotNull().maxByOrNull { it.updatedAt }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
}
