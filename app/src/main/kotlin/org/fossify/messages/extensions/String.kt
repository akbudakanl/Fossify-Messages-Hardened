package org.fossify.messages.extensions

fun String.getExtensionFromMimeType(): String {
    return when (lowercase()) {
        "image/png" -> ".png"
        "image/apng" -> ".apng"
        "image/webp" -> ".webp"
        "image/svg+xml" -> ".svg"
        "image/gif" -> ".gif"
        else -> ".jpg"
    }
}

fun String.isImageMimeType(): Boolean {
    return lowercase().startsWith("image")
}

fun String.isGifMimeType(): Boolean {
    return lowercase().endsWith("gif")
}

fun String.isVideoMimeType(): Boolean {
    return lowercase().startsWith("video")
}

fun String.isVCardMimeType(): Boolean {
    val lowercase = lowercase()
    return lowercase.endsWith("x-vcard") || lowercase.endsWith("vcard")
}

fun String.isAudioMimeType(): Boolean {
    return lowercase().startsWith("audio")
}

fun String.isCalendarMimeType(): Boolean {
    return lowercase().endsWith("calendar")
}

fun String.isPdfMimeType(): Boolean {
    return lowercase().endsWith("pdf")
}

fun String.isZipMimeType(): Boolean {
    return lowercase().endsWith("zip")
}

fun String.isPlainTextMimeType(): Boolean {
    return lowercase() == "text/plain"
}

/**
 * Restricts displayed text strictly to standard Roboto Latin keyboard characters:
 * - English / ASCII printable characters (A-Z, a-z, 0-9, standard punctuation)
 * - Turkish & German / European Latin letters (including ÄäÖöÜüßẞ, ÇçĞğİıŞş)
 * - Standard currency & typography symbols present in Roboto
 *
 * All emojis, non-Latin scripts (including Arabic), Bidi controls, zero-width characters, and
 * combining marks are stripped so Android never loads fallback/emoji fonts outside Roboto.
 * If a non-empty message consists entirely of unsupported characters (e.g. "👍"), returns
 * [allRemovedPlaceholder] so the message still appears in the conversation history.
 */
fun String.sanitizeForDisplay(
    allRemovedPlaceholder: String = "(Unsupported characters removed)"
): String {
    if (isEmpty()) return this
    val normalized = java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFC)
    val sb = StringBuilder(normalized.length)
    var i = 0
    while (i < normalized.length) {
        val cp = normalized.codePointAt(i)
        i += Character.charCount(cp)

        if (isAllowedRobotoKeyboardCodePoint(cp)) {
            sb.appendCodePoint(cp)
        }
    }
    val result = sb.toString()
    if (result.isBlank() && this.isNotBlank()) {
        return allRemovedPlaceholder
    }
    return result
}

private fun isAllowedRobotoKeyboardCodePoint(cp: Int): Boolean {
    // Basic whitespace: newline, carriage return, tab, standard space
    if (cp == '\n'.code || cp == '\r'.code || cp == '\t'.code || cp == 0x20) {
        return true
    }
    // Printable ASCII (0x21..0x7E): English A-Z, a-z, 0-9, standard keyboard punctuation & symbols
    if (cp in 0x21..0x7E) {
        return true
    }
    // Latin-1 Supplement & Latin Extended-A/B precomposed letters:
    // Includes Turkish (ÇçĞğİıÖöŞşÜü), German (ÄäÖöÜüß), and European Latin keyboard letters,
    // plus German capital sharp S (ẞ U+1E9E).
    if ((cp in 0x00C0..0x024F || cp == 0x1E9E) && Character.isLetter(cp)) {
        return true
    }
    // Common keyboard currency & typography symbols supported directly in Roboto
    return when (cp) {
        0x20BA, // ₺ Turkish Lira
        0x20AC, // € Euro
        0x00A3, // £ Pound
        0x00A5, // ¥ Yen
        0x00A7, // § Section (common on German keyboards)
        0x00B0, // ° Degree (common on German keyboards)
        0x00B1, // ± Plus-minus
        0x00AB, // « Left guillemet
        0x00BB, // » Right guillemet
        0x00D7, // × Multiplication
        0x00F7, // ÷ Division
        0x2013, // – En dash
        0x2014, // — Em dash
        0x2018, // ‘ Left single quote
        0x2019, // ’ Right single quote
        0x201A, // ‚ Single low-9 quote (German)
        0x201C, // “ Left double quote
        0x201D, // ” Right double quote
        0x201E, // „ Double low-9 quote (German)
        0x2026  // … Ellipsis
        -> true
        else -> false
    }
}


