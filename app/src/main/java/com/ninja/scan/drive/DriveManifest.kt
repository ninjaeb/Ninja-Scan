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
        /** True for a front+back ID card scan — see ScanDocument.isIdCard. */
        val isIdCard: Boolean = false,
    )

    data class CardEntry(val key: String, val card: BusinessCard, val tagTitles: List<String> = emptyList())

    data class TagEntry(val title: String, val description: String, val color: String)

    data class Content(
        val scans: List<ScanEntry>,
        val cards: List<CardEntry>,
        val folders: List<String>,
        val tags: List<TagEntry> = emptyList(),
        /** Folder name -> chip color, so restored folders keep their look. */
        val folderColors: Map<String, String> = emptyMap(),
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
        root.put("folders", JSONArray(content.folders))
        // Kept separate from the legacy "folders" string array so manifests
        // stay readable by app versions from before colors were carried.
        root.put("folderColors", JSONObject(content.folderColors.toMap()))
        val tags = JSONArray()
        for (tag in content.tags) {
            tags.put(
                JSONObject()
                    .put("title", tag.title)
                    .put("description", tag.description)
                    .put("color", tag.color)
            )
        }
        root.put("tags", tags)
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
                    isIdCard = scan.optBoolean("isIdCard", false),
                )
            )
        }
        val cards = mutableListOf<CardEntry>()
        val cardsJson = root.optJSONArray("cards") ?: JSONArray()
        for (i in 0 until cardsJson.length()) {
            val card = cardsJson.getJSONObject(i)
            val tagTitles = mutableListOf<String>()
            val tagTitlesJson = card.optJSONArray("tagTitles") ?: JSONArray()
            for (t in 0 until tagTitlesJson.length()) {
                tagTitlesJson.optString(t).takeIf { it.isNotEmpty() }?.let(tagTitles::add)
            }
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
                    tagTitles = tagTitles,
                )
            )
        }
        val folders = mutableListOf<String>()
        val foldersJson = root.optJSONArray("folders") ?: JSONArray()
        for (i in 0 until foldersJson.length()) {
            foldersJson.optString(i).takeIf { it.isNotEmpty() }?.let(folders::add)
        }
        val folderColors = mutableMapOf<String, String>()
        root.optJSONObject("folderColors")?.let { colorsJson ->
            for (name in colorsJson.keys()) {
                colorsJson.optString(name).takeIf { it.isNotEmpty() }?.let { folderColors[name] = it }
            }
        }
        val tags = mutableListOf<TagEntry>()
        val tagsJson = root.optJSONArray("tags") ?: JSONArray()
        for (i in 0 until tagsJson.length()) {
            val tag = tagsJson.getJSONObject(i)
            val title = tag.optString("title")
            if (title.isNotEmpty()) {
                tags.add(TagEntry(title = title, description = tag.optString("description"), color = tag.optString("color")))
            }
        }
        Content(scans, cards, folders, tags, folderColors)
    }.getOrNull()

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
}
