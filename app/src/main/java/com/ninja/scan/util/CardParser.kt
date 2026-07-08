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

    fun parse(text: String): BusinessCard {
        val rawLines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val email = emailRegex.find(text)?.value.orEmpty()
        val website = urlRegex.findAll(text)
            .map { it.value.trimEnd('.', ',', ';', ')') }
            .firstOrNull { !it.contains("@") }
            .orEmpty()

        // Phones: prefer mobile-labelled lines, skip fax numbers, and clean
        // the matched value to digits, +, spaces, and dashes only.
        val phoneCandidates = rawLines
            .filter { !faxLine.containsMatchIn(it) && !emailRegex.containsMatchIn(it) }
            .mapNotNull { line ->
                phoneRegex.find(line)?.value?.let { raw ->
                    val cleaned = raw.replace(Regex("[^+\\d\\s-]"), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    line to cleaned
                }
            }
            .filter { (_, number) -> number.count(Char::isDigit) in 7..15 }
        val phone = (
            phoneCandidates.firstOrNull { (line, _) -> mobileLabel.containsMatchIn(line) }
                ?: phoneCandidates.firstOrNull()
            )?.second.orEmpty()

        // Lines that are purely contact details don't compete for name/company.
        val remaining = rawLines
            .map { it.replace(labelPrefix, "") }
            .filter { line ->
                line.isNotEmpty() &&
                    !emailRegex.containsMatchIn(line) &&
                    !urlRegex.containsMatchIn(line) &&
                    !(phoneRegex.containsMatchIn(line) && line.count(Char::isDigit) >= 7)
            }

        val jobTitle = remaining.firstOrNull { line ->
            val lower = line.lowercase()
            line.count(Char::isDigit) == 0 && titleHints.any { lower.contains(it) }
        }.orEmpty()

        val company = remaining.firstOrNull { line ->
            line != jobTitle && companyHints.any { hint ->
                Regex("(?i)(^|[\\s.,&])${Regex.escape(hint)}([\\s.,&]|$)")
                    .containsMatchIn(line)
            }
        }.orEmpty()

        // Name: a short, digit-free line near the top whose words look like
        // a person's name (Title Case or ALL CAPS), and not address-like.
        val name = remaining.asSequence()
            .take(6)
            .filter { it != jobTitle && it != company }
            .filter { line -> line.length in 3..40 && line.none(Char::isDigit) }
            .filter { line ->
                val words = line.split(Regex("\\s+"))
                words.size in 1..4 && words.all { word ->
                    word.firstOrNull()?.isLetter() == true &&
                        (word.first().isUpperCase() || word.none(Char::isLowerCase))
                }
            }
            .filterNot { line ->
                val lower = line.lowercase()
                addressHints.any { lower.contains(it) }
            }
            .firstOrNull()
            // Fallback: the earlier loose heuristic, so name is rarely empty.
            ?: remaining.firstOrNull {
                it != jobTitle && it != company && it.length in 3..40 &&
                    it.any(Char::isLetter)
            }.orEmpty()

        // Address: the leftover lines that actually look like an address.
        val address = remaining
            .filter { it != name && it != company && it != jobTitle }
            .filter { line ->
                val lower = line.lowercase()
                line.any(Char::isDigit) || addressHints.any { lower.contains(it) }
            }
            .joinToString(", ")
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
}
