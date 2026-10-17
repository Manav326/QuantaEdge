package com.quantaedge.android.data

/** Pure range/type/checksum rules for the authenticated offline video transfer. */
internal object OfflineLearningVideoContract {
    const val MAX_VIDEO_BYTES = 524_288_000L

    data class ContentRange(val start: Long, val end: Long, val total: Long)
    enum class ResumeDisposition { APPEND, RESTART, VERIFY_COMPLETE, RETRY_INVALID }

    fun extensionFor(mediaType: String): String? = when (
        mediaType.substringBefore(';').trim().lowercase()
    ) {
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        else -> null
    }

    fun isValidSha256(value: String): Boolean =
        value.matches(Regex("(?i)^[0-9a-f]{64}$"))

    fun isValidSize(size: Long): Boolean = size in 1L..MAX_VIDEO_BYTES

    fun parseContentRange(value: String?): ContentRange? {
        if (value.isNullOrBlank()) return null
        val match = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+)$", RegexOption.IGNORE_CASE)
            .matchEntire(value.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (start < 0L || end < start || total <= 0L || end >= total) return null
        return ContentRange(start, end, total)
    }

    fun resumeDisposition(
        existingBytes: Long, statusCode: Int, range: ContentRange?, expectedSize: Long
    ): ResumeDisposition {
        if (existingBytes < 0L || expectedSize <= 0L || existingBytes > expectedSize) {
            return ResumeDisposition.RETRY_INVALID
        }
        return when (statusCode) {
            200 -> ResumeDisposition.RESTART
            206 -> if (range != null && range.start == existingBytes &&
                range.total == expectedSize && range.end == expectedSize - 1L) {
                ResumeDisposition.APPEND
            } else ResumeDisposition.RETRY_INVALID
            416 -> if (existingBytes == expectedSize) ResumeDisposition.VERIFY_COMPLETE
                else ResumeDisposition.RETRY_INVALID
            else -> ResumeDisposition.RETRY_INVALID
        }
    }
}
