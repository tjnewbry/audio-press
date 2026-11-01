package io.github.tjnewbry.audiopress.core

/**
 * The `urls` token rewrite (pipeline stage 9): web addresses are shortened to their domain, dropped, or
 * kept. Punctuation around the address is kept. Email addresses are not touched.
 */
object UrlRewriter {
    /** Opening punctuation, the address, then closing punctuation (including curly quotes). */
    private val URL =
        Regex(
            """([(\[{"'\x{201C}\x{2018}]*)((?:https?://|www\.)\S+?)([.,;:!?)\]}"'\x{201D}\x{2019}]*)""",
            RegexOption.IGNORE_CASE,
        )
    private val SCHEME = Regex("""^https?://""", RegexOption.IGNORE_CASE)

    fun rewrite(
        transcript: Transcript,
        mode: UrlMode,
    ): Transcript =
        if (mode == UrlMode.KEEP) {
            transcript
        } else {
            Transcript(transcript.segments.map { it.copy(tokens = rewrite(it.tokens, mode)) })
        }

    private fun rewrite(
        tokens: List<Token>,
        mode: UrlMode,
    ): List<Token> {
        val result = mutableListOf<Token>()
        for (token in tokens) {
            val match = URL.matchEntire(token.text)
            if (match == null) {
                result += token
                continue
            }
            val (before, url, after) = match.destructured
            if (mode == UrlMode.DOMAIN) {
                result += token.copy(text = before + domainOf(url) + after)
                continue
            }
            // Dropping the address keeps the punctuation around it, attached to the previous word when possible.
            val rest = before + after
            if (rest.isEmpty()) continue
            when {
                before.isEmpty() && result.isNotEmpty() -> {
                    val last = result.removeAt(result.lastIndex)
                    result += last.copy(text = last.text + rest, spans = last.spans + token.spans)
                }

                else -> {
                    result += token.copy(text = rest)
                }
            }
        }
        return result
    }

    /** `https://www.example.com/a/b?c=1` becomes `example.com`. */
    fun domainOf(url: String): String {
        val host =
            url
                .replace(SCHEME, "")
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .substringBefore(':')
        val domain = if (host.startsWith("www.", ignoreCase = true)) host.drop(4) else host
        return domain.ifEmpty { url }
    }
}
