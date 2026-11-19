package app.openradio.domain

/**
 * Pure rules for the car preset list. Pins are an ordered list of station ids, at most [MAX_PINS].
 */
object PresetOrdering {
    const val MAX_PINS = 6

    /** Appends [id]; no-op when already pinned or the list is full. */
    fun pin(pins: List<String>, id: String): List<String> =
        if (id in pins || pins.size >= MAX_PINS) pins else pins + id

    fun unpin(pins: List<String>, id: String): List<String> = pins.filterNot { it == id }

    /** Moves the item at [from] to index [to] (both clamped); returns the same list when nothing moves. */
    fun move(pins: List<String>, from: Int, to: Int): List<String> {
        if (from !in pins.indices) return pins
        val target = to.coerceIn(0, pins.lastIndex)
        if (target == from) return pins
        val mutable = pins.toMutableList()
        mutable.add(target, mutable.removeAt(from))
        return mutable
    }

    /** Drops pins that are no longer favorites (e.g. after unfavoriting) while keeping order. */
    fun retainFavorites(pins: List<String>, favoriteIds: Set<String>): List<String> =
        pins.filter { it in favoriteIds }.take(MAX_PINS)

    /**
     * Presets exposed to the car: the user's pins in order, or, when nothing is pinned,
     * the favorites (most recently saved first) so the car view is still useful.
     */
    fun carPresets(favorites: List<SavedStation>, fallbackLimit: Int = 20): List<Station> {
        val pinned = favorites.filter { it.pinnedOrder != null }.sortedBy { it.pinnedOrder }
        val source = if (pinned.isNotEmpty()) pinned.take(MAX_PINS) else favorites.take(fallbackLimit)
        return source.map { it.station }
    }
}
