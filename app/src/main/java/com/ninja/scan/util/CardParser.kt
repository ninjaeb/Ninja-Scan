package com.ninja.scan.util

import com.ninja.scan.data.BusinessCard

/**
 * Heuristic extraction of contact fields from the OCR text of a business
 * card. Best-effort: everything is user-editable afterwards.
 */
object CardParser {

    private val emailRegex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val phoneRegex = Regex("\\+?\\d[\\d\\s()./-]{5,}\\d")
    private val urlRegex = Regex(
        "(?i)\\b((https?://|www\\.)\\S+|[a-z0-9-]+\\.(com|net|org|io|co|biz|info|my|sg|id|au|uk|de)(/\\S*)?)\\b"
    )

    /** Labels commonly printed before contact details; stripped for parsing. */
    private val labelPrefix = Regex(
        "(?i)^(tel|telephone|phone|mobile|hp|h/p|cell|office|off|direct|fax|" +
            "email|e-mail|web|website|www|[tmfpew])\\s*[:.]\\s*"
    )
    private val faxLine = Regex("(?i)\\bfax\\b")
    private val mobileLabel = Regex("(?i)\\b(mobile|hp|h/p|cell|m)\\b")

    private val titleHints = listOf(
        "ceo", "cto", "cfo", "coo", "director", "manager", "engineer", "developer",
        "designer", "founder", "co-founder", "president", "consultant", "executive",
        "officer", "head of", "lead", "specialist", "analyst", "architect",
        "sales", "marketing", "partner", "advisor", "supervisor", "accountant",
        "attorney", "lawyer", "agent", "broker", "coordinator",
    )
    private val companyHints = listOf(
        "ltd", "llc", "inc", "gmbh", "bhd", "sdn", "pte", "plc", "corp",
        "company", "enterprise", "solutions", "technologies", "technology",
        "studio", "agency", "group", "holdings", "services", "trading",
        "industries", "international", "global", "consulting", "ventures",
    )
    private val addressHints = listOf(
        "street", "st.", "road", "rd", "avenue", "ave", "lane", "jalan", "jln",
        "block", "blok", "floor", "level", "suite", "unit", "no.", "lot",
        "building", "tower", "plaza", "park", "city", "state", "postcode",
    )

    private const val NAME_TITLE_GAP_HEIGHT_MULTIPLIER = 1.5
    private const val NAME_TITLE_MIN_GAP_PX = 8
    private const val NAME_TITLE_HORIZONTAL_SLACK_PX = 20

    /**
     * Compatibility entry point for plain OCR text with no layout info.
     * Degrades to line-order ranking (equivalent to the pre-layout-aware
     * behavior) since every synthetic line has zero height.
     */
    fun parse(text: String): BusinessCard =
        parse(
            text.lines().mapIndexed { index, raw -> OcrLine(raw.trim(), 0, index, 0, index) }
        )

    /**
     * Extracts contact fields from layout-aware OCR lines. Lines that were
     * split by [splitLineIntoColumns] (e.g. a name and job title printed as
     * side-by-side columns) arrive as separate entries here, so keyword
     * matching no longer conflates them.
     */
    fun parse(lines: List<OcrLine>): BusinessCard {
        val rawLines = lines.filter { it.text.isNotEmpty() }
        val joined = rawLines.joinToString("\n") { it.text }

        val email = emailRegex.find(joined)?.value.orEmpty()
        val website = urlRegex.findAll(joined)
            .map { it.value.trimEnd('.', ',', ';', ')') }
            .firstOrNull { candidate ->
                !candidate.contains("@") &&
                    (email.isEmpty() || !email.contains(candidate, ignoreCase = true))
            }
            .orEmpty()

        // Phones: prefer mobile-labelled lines, skip fax numbers, and clean
        // the matched value to digits, +, spaces, and dashes only.
        val phoneCandidates = rawLines
            .filter { !faxLine.containsMatchIn(it.text) && !emailRegex.containsMatchIn(it.text) }
            .mapNotNull { line ->
                phoneRegex.find(line.text)?.value?.let { raw ->
                    val cleaned = raw.replace(Regex("[^+\\d\\s-]"), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    line.text to cleaned
                }
            }
            .filter { (_, number) -> number.count(Char::isDigit) in 7..15 }
        val phone = (
            phoneCandidates.firstOrNull { (line, _) -> mobileLabel.containsMatchIn(line) }
                ?: phoneCandidates.firstOrNull()
            )?.second.orEmpty()

        // Lines that are purely contact details don't compete for name/company.
        val remaining = rawLines
            .map { Candidate(it, it.text.replace(labelPrefix, "").trim()) }
            .filter { (_, text) ->
                text.isNotEmpty() &&
                    !emailRegex.containsMatchIn(text) &&
                    !urlRegex.containsMatchIn(text) &&
                    !(phoneRegex.containsMatchIn(text) && text.count(Char::isDigit) >= 7)
            }

        // High-confidence signal: a name-shaped line with a job-title-shaped
        // line directly below it (how most cards actually lay out a
        // contact's details). Takes priority over the keyword-anywhere scan
        // and the tallest-line ranking below, since neither of those account
        // for position — a large stylized company logo can otherwise easily
        // out-rank the real (smaller-font) name.
        val pair = findNameTitlePair(remaining)

        val jobTitle = pair?.second?.text ?: remaining.firstOrNull { (_, text) ->
            val lower = text.lowercase()
            text.count(Char::isDigit) == 0 && titleHints.any { lower.contains(it) }
        }?.text.orEmpty()

        val company = remaining.firstOrNull { (_, text) ->
            text != jobTitle && companyHints.any { hint ->
                Regex("(?i)(^|[\\s.,&])${Regex.escape(hint)}([\\s.,&]|$)")
                    .containsMatchIn(text)
            }
        }?.text.orEmpty()

        val name = pair?.first?.text ?: selectName(remaining, jobTitle, company)

        // Address: the leftover lines that actually look like an address.
        val address = remaining
            .filter { (_, text) -> text != name && text != company && text != jobTitle }
            .filter { (_, text) ->
                val lower = text.lowercase()
                text.any(Char::isDigit) || addressHints.any { lower.contains(it) }
            }
            .joinToString(", ") { it.text }
            .take(250)

        return BusinessCard(
            name = name,
            company = company,
            jobTitle = jobTitle,
            phone = phone,
            email = email,
            website = website,
            address = address,
        )
    }

    private fun isAddressLike(text: String): Boolean {
        val lower = text.lowercase()
        return addressHints.any { lower.contains(it) }
    }

    private fun looksLikeCompany(text: String): Boolean = companyHints.any { hint ->
        Regex("(?i)(^|[\\s.,&])${Regex.escape(hint)}([\\s.,&]|$)").containsMatchIn(text)
    }

    private fun looksLikeName(text: String): Boolean {
        if (text.length !in 3..40 || text.any(Char::isDigit)) return false
        val words = text.split(Regex("\\s+"))
        return words.size in 1..4 && words.all { word ->
            word.firstOrNull()?.isLetter() == true &&
                (word.first().isUpperCase() || word.none(Char::isLowerCase))
        }
    }

    private fun couldBeName(text: String): Boolean =
        text.length in 3..40 && text.none(Char::isDigit) && !isAddressLike(text)

    private fun matchesTitleHint(text: String): Boolean {
        val lower = text.lowercase()
        return text.count(Char::isDigit) == 0 && titleHints.any { lower.contains(it) }
    }

    private fun gapOk(a: OcrLine, b: OcrLine): Boolean {
        val gap = b.top - a.bottom
        if (gap < 0) return false
        return gap <= maxOf((a.height * NAME_TITLE_GAP_HEIGHT_MULTIPLIER).toInt(), NAME_TITLE_MIN_GAP_PX)
    }

    private fun horizontallyAligned(a: OcrLine, b: OcrLine): Boolean {
        val left = maxOf(a.left, b.left)
        val right = minOf(a.right, b.right)
        return right >= left || (left - right) <= NAME_TITLE_HORIZONTAL_SLACK_PX
    }

    /**
     * Topmost-first search for a name line immediately followed (in the same
     * column) by a job-title-shaped line. Returns null if no such adjacency
     * exists anywhere on the card, so callers can fall back to the
     * independent keyword/height-based heuristics.
     */
    private fun findNameTitlePair(candidates: List<Candidate>): Pair<Candidate, Candidate>? {
        val byTop = candidates.sortedBy { it.line.top }
        for (a in byTop) {
            // Company-shaped lines are excluded from name candidacy here:
            // otherwise a company name sitting directly above an unrelated
            // job-title line elsewhere on the card could be picked as the
            // "name" instead of the real person's name.
            if (!looksLikeName(a.text) || isAddressLike(a.text) || looksLikeCompany(a.text)) continue
            val nearestBelow = byTop
                .filter { it !== a && gapOk(a.line, it.line) && horizontallyAligned(a.line, it.line) }
                .minByOrNull { it.line.top - a.line.bottom }
                ?: continue
            if (matchesTitleHint(nearestBelow.text)) return a to nearestBelow
        }
        return null
    }

    /**
     * Picks the most likely person's name from the remaining candidates,
     * preferring a strict Title-Case/ALL-CAPS shape and, among ties,
     * whichever candidate has the tallest bounding box (largest font is
     * often the name on a business card) and then the topmost position.
     * The relaxed fallback still excludes digits and address-like text, so
     * an address line can never be picked — unlike the old loose fallback.
     * Only used when [findNameTitlePair] finds no positional match.
     */
    private fun selectName(
        remaining: List<Candidate>,
        jobTitle: String,
        company: String,
    ): String {
        val candidates = remaining.filter { (_, text) -> text != jobTitle && text != company }
        val strict = candidates.filter { (_, text) -> looksLikeName(text) && !isAddressLike(text) }
        val relaxed = candidates.filter { (_, text) -> couldBeName(text) }

        return (strict.ifEmpty { relaxed })
            .sortedWith(compareByDescending<Candidate> { it.line.height }.thenBy { it.line.top })
            .firstOrNull()?.text.orEmpty()
    }

    private data class Candidate(val line: OcrLine, val text: String)
}
