package app.openradio.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.openradio.domain.DataUsageMath
import app.openradio.domain.MonthlyUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.usageStore by preferencesDataStore("data_usage")

/** Streamed network bytes per calendar month, split into mobile (metered) and Wi-Fi (unmetered). */
class UsageRepository(private val context: Context) {

    /** Newest month first; only months with traffic are listed. */
    val months: Flow<List<MonthlyUsage>> = context.usageStore.data.map { prefs ->
        val totals = HashMap<String, Pair<Long, Long>>()
        prefs.asMap().forEach { (key, value) ->
            val (kind, month) = key.name.split('_', limit = 2).takeIf { it.size == 2 } ?: return@forEach
            val bytes = value as? Long ?: return@forEach
            val (m, w) = totals[month] ?: (0L to 0L)
            totals[month] = if (kind == MOBILE) (m + bytes to w) else (m to w + bytes)
        }
        totals.map { (month, mw) -> MonthlyUsage(month, mw.first, mw.second) }.sortedByDescending { it.month }
    }

    suspend fun add(mobileBytes: Long, wifiBytes: Long, nowMs: Long = System.currentTimeMillis()) {
        if (mobileBytes <= 0 && wifiBytes <= 0) return
        val month = DataUsageMath.monthKey(nowMs)
        context.usageStore.edit { prefs ->
            if (mobileBytes > 0) {
                val k = longPreferencesKey("${MOBILE}_$month")
                prefs[k] = (prefs[k] ?: 0L) + mobileBytes
            }
            if (wifiBytes > 0) {
                val k = longPreferencesKey("${WIFI}_$month")
                prefs[k] = (prefs[k] ?: 0L) + wifiBytes
            }
            val monthsPresent = prefs.asMap().keys.mapNotNull { it.name.substringAfter('_', "").ifEmpty { null } }.toSet()
            val drop = DataUsageMath.monthsToDrop(monthsPresent).toSet()
            if (drop.isNotEmpty()) {
                prefs.asMap().keys.filter { it.name.substringAfter('_') in drop }.forEach { prefs.remove(it) }
            }
        }
    }

    suspend fun reset() {
        context.usageStore.edit { it.clear() }
    }

    private companion object {
        const val MOBILE = "mobile"
        const val WIFI = "wifi"
    }
}
