package com.grapsee.grapmail.features

import java.net.URI
import java.util.Locale

/**
 * GrapMail trust & action layer.
 *
 * Roadmap coverage (feature roadmap, 2026-11-19):
 *  - F16  Tracker shield v2        -> TrackerShield (pixel detection + HTML cleanup)
 *  - F23  QR-code phishing warnings -> UrlSafety (verdict for a scanned/decoded URL)
 *  - F27  Actionable message cards  -> ActionCardExtractor (tracking / flight / dates)
 *
 * Pure Kotlin (java.util + java.net only). Deterministic, offline, no message
 * content leaves the device.
 */

// ---------------------------------------------------------------------------
// F27 — Actionable message cards
// ---------------------------------------------------------------------------

enum class CardKind(val label: String) {
    TRACKING("Tracking"),
    FLIGHT("Flight"),
    EVENT("Event"),
}

data class ActionCard(
    val kind: CardKind,
    val value: String,
    val label: String,
    val url: String?,
)

object ActionCardExtractor {

    private val trackingContext = Regex("""(?i)\b(track|tracking|shipment|package|parcel|delivery|deliver|order number)\b""")
    private val flightContext = Regex("""(?i)\b(flight|departure|depart|arrival|arrive|boarding|gate|itinerary|checked in|check-in)\b""")
    private val eventContext = Regex("""(?i)\b(appointment|meeting|deadline|due date|event|interview|scheduled for)\b""")

    private val upsPattern = Regex("""\b1Z[0-9A-Z]{16}\b""")
    private val uspsPattern = Regex("""\b(?:9\d{19,21}|[A-Z]{2}\d{9}US)\b""")
    private val dhlPattern = Regex("""\bJJD\d{16,20}\b""")
    private val dhlNumericPattern = Regex("""\b\d{10}\b""")
    private val fedexPattern = Regex("""\b(?:\d{12}|\d{15})\b""")
    private val flightPattern = Regex("""\b([A-Z]{2})\s?(\d{1,4})\b""")
    private val isoDatePattern = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")

    private val knownAirlineCodes = setOf(
        "AA", "AC", "AF", "AS", "AV", "AY", "AZ", "B6", "BA", "BR", "CA", "CI",
        "CX", "CZ", "DL", "EK", "ET", "EY", "F9", "FI", "FR", "HA", "HU", "IB",
        "JL", "JQ", "KE", "KL", "KQ", "LA", "LH", "LO", "LX", "MH", "MS", "MU",
        "NH", "NK", "NZ", "OS", "OZ", "PR", "QF", "QR", "RJ", "SA", "SK", "SN",
        "SQ", "SU", "SV", "TK", "TP", "UA", "UL", "VA", "VN", "VS", "WN", "WS",
    )

    fun extract(subject: String, bodyText: String): List<ActionCard> {
        val text = (subject + "\n" + bodyText).take(30000)
        val cards = ArrayList<ActionCard>()

        if (trackingContext.containsMatchIn(text)) {
            upsPattern.find(text)?.let { cards.add(trackingCard("UPS", it.value)) }
            uspsPattern.find(text)?.let { cards.add(trackingCard("USPS", it.value)) }
            dhlPattern.find(text)?.let { cards.add(trackingCard("DHL", it.value)) }
            if (text.lowercase(Locale.ROOT).contains("dhl")) {
                dhlNumericPattern.find(text)?.let { m ->
                    if (cards.none { it.kind == CardKind.TRACKING }) {
                        cards.add(trackingCard("DHL", m.value))
                    }
                }
            }
            fedexPattern.find(text)?.let { m ->
                if (cards.none { it.kind == CardKind.TRACKING }) {
                    cards.add(trackingCard("FedEx", m.value))
                }
            }
        }

        if (flightContext.containsMatchIn(text)) {
            val flight = flightPattern.findAll(text)
                .map { m -> m.groupValues[1] + " " + m.groupValues[2] }
                .firstOrNull { candidate -> knownAirlineCodes.contains(candidate.substringBefore(' ')) }
            if (flight != null) {
                cards.add(
                    ActionCard(
                        CardKind.FLIGHT,
                        flight,
                        "Flight $flight — open flight status",
                        "https://www.google.com/search?q=flight+" + flight.replace(" ", "+") + "+status",
                    ),
                )
            }
        }

        if (eventContext.containsMatchIn(text)) {
            isoDatePattern.find(text)?.let { m ->
                val iso = m.value
                val compact = iso.replace("-", "")
                cards.add(
                    ActionCard(
                        CardKind.EVENT,
                        iso,
                        "Date found: $iso — add to calendar",
                        "https://calendar.google.com/calendar/render?action=TEMPLATE&text=Event&dates=$compact/$compact",
                    ),
                )
            }
        }

        val seen = HashSet<String>()
        return cards.filter { seen.add(it.kind.name + ":" + it.value) }
    }

    private fun trackingCard(carrier: String, value: String): ActionCard {
        val url = when (carrier) {
            "UPS" -> "https://www.ups.com/track?tracknum=" + value
            "USPS" -> "https://tools.usps.com/go/TrackConfirmAction?tLabels=" + value
            "DHL" -> "https://www.dhl.com/global-en/home/tracking.html?tracking-id=" + value
            "FedEx" -> "https://www.fedex.com/fedextrack/?trknbr=" + value
            else -> null
        }
        return ActionCard(CardKind.TRACKING, value, "$carrier $value — track package", url)
    }
}

// ---------------------------------------------------------------------------
// F23 — QR / URL phishing analysis
// ---------------------------------------------------------------------------

enum class UrlRisk(val label: String) {
    LOW("Looks safe"),
    MEDIUM("Be careful"),
    HIGH("Dangerous"),
}

data class UrlVerdict(
    val risk: UrlRisk,
    val reasons: List<String>,
    val displayHost: String,
    val original: String,
)

object UrlSafety {

    private val brandDomains = linkedMapOf(
        "apple" to "apple.com",
        "icloud" to "icloud.com",
        "google" to "google.com",
        "gmail" to "gmail.com",
        "microsoft" to "microsoft.com",
        "outlook" to "outlook.com",
        "office" to "office.com",
        "paypal" to "paypal.com",
        "amazon" to "amazon.com",
        "netflix" to "netflix.com",
        "facebook" to "facebook.com",
        "instagram" to "instagram.com",
        "whatsapp" to "whatsapp.com",
        "linkedin" to "linkedin.com",
        "dropbox" to "dropbox.com",
        "docusign" to "docusign.com",
        "adobe" to "adobe.com",
        "coinbase" to "coinbase.com",
        "binance" to "binance.com",
        "chase" to "chase.com",
        "wellsfargo" to "wellsfargo.com",
        "hsbc" to "hsbc.com",
        "barclays" to "barclays.co.uk",
        "revolut" to "revolut.com",
        "steampowered" to "steampowered.com",
        "roblox" to "roblox.com",
        "grapsee" to "grapsee.com",
        "grapmail" to "grapmail.com",
    )

    private val shortenerHosts = listOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "buff.ly",
        "rebrand.ly", "cutt.ly", "rb.gy", "tiny.cc", "shorturl.at", "s.id", "t.ly",
    )

    private val riskyTlds = listOf(
        ".zip", ".mov", ".top", ".xyz", ".click", ".link", ".quest", ".rest",
        ".gq", ".tk", ".ml", ".cf", ".buzz", ".surf",
    )

    private val ipv4Pattern = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")

    fun analyze(rawUrl: String): UrlVerdict {
        val url = rawUrl.trim()
        val reasons = ArrayList<String>(4)

        var scheme = ""
        var host = ""
        var userInfo = false

        try {
            val uri = URI(url)
            scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
            host = uri.host?.lowercase(Locale.ROOT).orEmpty()
            userInfo = !uri.userInfo.isNullOrEmpty()
        } catch (e: Exception) {
            // fall through to the manual split below
        }

        if (host.isEmpty()) {
            val m = Regex("""(?i)^([a-z][a-z0-9+.-]*)://([^/?#]+)""").find(url)
            if (m != null) {
                scheme = m.groupValues[1].lowercase(Locale.ROOT)
                val authority = m.groupValues[2]
                userInfo = authority.contains('@')
                host = authority.substringAfterLast('@').substringBefore(':').lowercase(Locale.ROOT)
            }
        }

        if (host.isEmpty()) {
            return UrlVerdict(UrlRisk.HIGH, listOf("The link's destination could not be read."), "", url)
        }

        if (scheme == "http") {
            reasons.add("Not encrypted (http://) — anything entered on that page can be intercepted.")
        }
        if (userInfo) {
            reasons.add("The address hides a fake login string before the real domain.")
        }
        if (ipv4Pattern.matches(host)) {
            reasons.add("The destination is a raw IP address, not a real domain.")
        }
        if (host.startsWith("xn--") || host.contains(".xn--")) {
            reasons.add("The domain uses punycode characters, often used to imitate real letters.")
        }
        if (shortenerHosts.any { host == it || host.endsWith("." + it) }) {
            reasons.add("This is a shortened link — the true destination is hidden.")
        }
        if (riskyTlds.any { host.endsWith(it) }) {
            reasons.add("This domain uses a top-level domain heavily abused by scams.")
        }

        val labels = host.split('.').filter { it.isNotEmpty() }
        if (labels.size >= 5) {
            reasons.add("Unusually deep subdomain chain (${labels.size} levels) — common in fake login pages.")
        }

        val registrable = registrableDomain(labels)
        val officialHit = brandDomains.entries.firstOrNull { (_, official) ->
            host == official || host.endsWith("." + official)
        }
        if (officialHit == null) {
            for ((brand, official) in brandDomains) {
                val subImitates = labels.dropLast(2).any { label -> label.replace("-", "").contains(brand) }
                val tokens = registrable.split('-', '.')
                val regImitates = tokens.any { t ->
                    t == brand || (brand.length >= 6 && (t.startsWith(brand) || t.endsWith(brand)))
                }
                if (subImitates || regImitates) {
                    reasons.add("\"$registrable\" imitates $brand — the official domain is $official.")
                    break
                }
            }
        }

        val risk = when {
            ipv4Pattern.matches(host) || userInfo -> UrlRisk.HIGH
            reasons.size >= 2 -> UrlRisk.HIGH
            reasons.size == 1 -> UrlRisk.MEDIUM
            else -> UrlRisk.LOW
        }

        val display = when {
            ipv4Pattern.matches(host) -> host
            registrable.isNotEmpty() -> registrable
            else -> host
        }
        return UrlVerdict(risk, reasons.distinct(), display, url)
    }

    private fun registrableDomain(labels: List<String>): String {
        if (labels.size < 2) return labels.joinToString(".")
        val secondLevelSuffixes = setOf("co", "com", "org", "net", "gov", "ac", "edu")
        val last = labels.last()
        if (last.length == 2 && labels.size >= 3 && secondLevelSuffixes.contains(labels[labels.size - 2])) {
            return labels.takeLast(3).joinToString(".")
        }
        return labels.takeLast(2).joinToString(".")
    }
}

// ---------------------------------------------------------------------------
// F16 — Tracker shield v2 (pixel detection + HTML cleanup)
// ---------------------------------------------------------------------------

data class TrackerScanResult(
    val remoteImageCount: Int,
    val trackerCount: Int,
    val blockedHosts: List<String>,
    val cleanedHtml: String,
)

object TrackerShield {

    private val imgTagRegex = Regex("""(?is)<img\b[^>]*>""")
    private val srcAttr = Regex("""(?is)\bsrc\s*=\s*["']([^"']+)["']""")
    private val onePixel = Regex("""(?is)(?:width\s*=\s*["']?1(?:["'\s/>]|px)|height\s*=\s*["']?1(?:["'\s/>]|px)|width\s*:\s*1px|height\s*:\s*1px)""")

    private val trackerHostHints = listOf(
        "sendgrid", "mailchimp", "list-manage", "hubspot", "marketo", "klaviyo",
        "braze", "iterable", "customer.io", "postmark", "mailgun", "sparkpost",
        "campaign-archive", "facebook.com/tr", "google-analytics", "googletagmanager",
        "doubleclick", "adservice", "track.", "tracking.", "pixel.", "beacon.",
        "open.", "click.", "links.", "mandrill", "exacttarget", "salesforce",
    )

    fun scan(html: String, allowRemoteImages: Boolean): TrackerScanResult {
        val blocked = ArrayList<String>()
        var remoteCount = 0
        var trackerCount = 0

        val tags = imgTagRegex.findAll(html).toList()
        for (m in tags) {
            val tag = m.value
            val src = srcAttr.find(tag)?.groupValues?.get(1)?.trim() ?: continue
            if (!isRemote(src)) continue
            remoteCount += 1
            val host = hostOf(src) ?: continue
            val isTracker = onePixel.containsMatchIn(tag) || trackerHostHints.any { host.contains(it) }
            if (isTracker) trackerCount += 1
            if (isTracker || !allowRemoteImages) blocked.add(host)
        }

        var out = html
        if (!allowRemoteImages || trackerCount > 0) {
            for (m in tags) {
                val tag = m.value
                val src = srcAttr.find(tag)?.groupValues?.get(1)?.trim() ?: continue
                if (!isRemote(src)) continue
                val host = hostOf(src) ?: continue
                val isTracker = onePixel.containsMatchIn(tag) || trackerHostHints.any { host.contains(it) }
                if (isTracker || !allowRemoteImages) {
                    out = out.replace(tag, "")
                }
            }
        }

        return TrackerScanResult(
            remoteImageCount = remoteCount,
            trackerCount = trackerCount,
            blockedHosts = blocked.distinct(),
            cleanedHtml = out,
        )
    }

    private fun isRemote(src: String): Boolean =
        src.startsWith("http://", ignoreCase = true) || src.startsWith("https://", ignoreCase = true)

    private fun hostOf(src: String): String? {
        try {
            val h = URI(src).host
            if (h != null) return h.lowercase(Locale.ROOT)
        } catch (e: Exception) {
            // fall through
        }
        val m = Regex("""(?i)^[a-z]+://([^/?#]+)""").find(src) ?: return null
        return m.groupValues[1].substringBefore(':').lowercase(Locale.ROOT)
    }
}
