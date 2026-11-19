package app.openradio.ui.explore

import app.openradio.data.remote.StationDirectory
import app.openradio.domain.Country
import app.openradio.domain.Station
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExploreState(
    val query: String = "",
    val country: Country? = null,
    val tag: String? = null,
    val items: List<Station> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val endReached: Boolean = false,
) {
    /** Trending ranking applies whenever there is no active text search. */
    val isTrending: Boolean get() = query.isBlank()
    val isEmpty: Boolean get() = !loading && error == null && items.isEmpty()
}

/**
 * Search/paging state machine. Each reload gets a new request id; a response is applied only if
 * its id is still current, so a slow older query can never replace newer results.
 */
class ExploreController(
    private val scope: CoroutineScope,
    private val directory: StationDirectory,
    private val debounceMs: Long = 350,
    private val pageSize: Int = 30,
) {
    private val _state = MutableStateFlow(ExploreState())
    val state: StateFlow<ExploreState> = _state.asStateFlow()

    private var requestId = 0
    private var job: Job? = null
    private var moreJob: Job? = null

    fun setQuery(query: String) {
        if (query == _state.value.query) return
        _state.update { it.copy(query = query) }
        reload(debounceMs)
    }

    fun setCountry(country: Country?) {
        _state.update { it.copy(country = country) }
        reload(0)
    }

    fun setTag(tag: String?) {
        _state.update { it.copy(tag = tag) }
        reload(0)
    }

    fun retry() = reload(0)

    fun reload(delayMs: Long = 0) {
        job?.cancel()
        moreJob?.cancel()
        val id = ++requestId
        _state.update { it.copy(loading = true, loadingMore = false, error = null, endReached = false, items = if (delayMs > 0) it.items else emptyList()) }
        job = scope.launch {
            if (delayMs > 0) delay(delayMs)
            val s = _state.value
            try {
                val result = directory.search(s.query.trim().ifEmpty { null }, s.country?.code, s.tag, 0, pageSize)
                if (id != requestId) return@launch
                _state.update { it.copy(items = result, loading = false, endReached = result.size < pageSize) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (id != requestId) return@launch
                _state.update { it.copy(loading = false, error = "Couldn't reach the station directory.", items = emptyList()) }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.loadingMore || s.endReached || s.error != null || s.items.isEmpty()) return
        val id = requestId
        val offset = s.items.size
        _state.update { it.copy(loadingMore = true) }
        moreJob = scope.launch {
            try {
                val result = directory.search(s.query.trim().ifEmpty { null }, s.country?.code, s.tag, offset, pageSize)
                if (id != requestId) return@launch
                _state.update {
                    val known = it.items.mapTo(HashSet()) { st -> st.id }
                    it.copy(
                        items = it.items + result.filter { st -> st.id !in known },
                        loadingMore = false, endReached = result.size < pageSize,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (id != requestId) return@launch
                // Keep what we have; the user can scroll again to retry.
                _state.update { it.copy(loadingMore = false) }
            }
        }
    }
}
