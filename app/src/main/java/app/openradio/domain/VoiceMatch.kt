package app.openradio.domain

/** Picks a station for a spoken request such as "play KEXP". Exact names beat partial ones. */
object VoiceMatch {
    private fun normalize(s: String) =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    private val fillers = Regex("^(play|listen to|put on|start)\\s+|\\s+(radio|station|on open radio)$")

    fun cleanQuery(raw: String): String = normalize(raw).replace(fillers, "").trim()

    /** @param candidates ordered by preference (e.g. favorites first, then directory results) */
    fun best(query: String, candidates: List<Station>): Station? {
        val q = cleanQuery(query)
        if (q.isEmpty()) return null
        candidates.firstOrNull { normalize(it.name) == q }?.let { return it }
        candidates.firstOrNull { normalize(it.name).startsWith(q) }?.let { return it }
        return candidates.firstOrNull { normalize(it.name).contains(q) }
    }
}
