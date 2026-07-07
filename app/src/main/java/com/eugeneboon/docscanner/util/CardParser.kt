package com.eugeneboon.docscanner.util

import com.eugeneboon.docscanner.data.BusinessCard

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

    private val titleHints = listOf(
        "ceo", "cto", "cfo", "coo", "director", "manager", "engineer", "developer",
        "designer", "founder", "co-founder", "president", "consultant", "executive",
        "officer", "head of", "lead", "specialist", "analyst", "architect",
        "sales", "marketing", "partner", "advisor", "supervisor",
    )
    private val companyHints = listOf(
        "ltd", "llc", "inc", "gmbh", "bhd", "sdn", "pte", "plc", "corp",
        "company", "enterprise", "solutions", "technologies", "technology",
        "studio", "agency", "group", "holdings", "services", "trading",
    )

    fun parse(text: String): BusinessCard {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val email = emailRegex.find(text)?.value.orEmpty()
        val website = urlRegex.findAll(text)
            .map { it.value }
            .firstOrNull { !it.contains("@") }
            .orEmpty()
        val phone = phoneRegex.findAll(text)
            .map { it.value.trim() }
            .firstOrNull { candidate -> candidate.count(Char::isDigit) >= 7 }
            .orEmpty()

        // Lines that are contact details are excluded from name/company guessing.
        val remaining = lines.filter { line ->
            !emailRegex.containsMatchIn(line) &&
                !urlRegex.containsMatchIn(line) &&
                !(phoneRegex.containsMatchIn(line) && line.count(Char::isDigit) >= 7)
        }

        val jobTitle = remaining.firstOrNull { line ->
            titleHints.any { line.lowercase().contains(it) }
        }.orEmpty()

        val company = remaining.firstOrNull { line ->
            line != jobTitle && companyHints.any { line.lowercase().contains(it) }
        }.orEmpty()

        val name = remaining.firstOrNull { line ->
            line != jobTitle && line != company &&
                line.length in 3..40 && line.any(Char::isLetter)
        }.orEmpty()

        val address = remaining
            .filter { it != name && it != company && it != jobTitle }
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
