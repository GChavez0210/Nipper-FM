package app.openradio.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Best-effort, one-shot lookup of the ISO 3166-1 alpha-2 country the user is currently in.
 *
 * Order: coarse device location reverse-geocoded to a country, then the mobile network's
 * country. Returns null when neither is available (no permission, no SIM, no geocoder).
 */
class CountryLocator(context: Context) {
    private val appContext = context.applicationContext

    suspend fun detectCountryCode(): String? =
        fromLocation() ?: fromTelephony()

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private suspend fun fromLocation(): String? {
        if (!hasLocationPermission() || !Geocoder.isPresent()) return null
        val location = withTimeoutOrNull(LOCATION_TIMEOUT_MS) { currentLocation() } ?: return null
        return withTimeoutOrNull(GEOCODE_TIMEOUT_MS) { reverseGeocode(location) }
    }

    @SuppressLint("MissingPermission") // Checked in fromLocation().
    private suspend fun currentLocation(): Location? {
        val lm = appContext.getSystemService<LocationManager>() ?: return null
        // A cached fix is plenty to resolve a country and costs no battery.
        val cached = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
        if (cached != null) return cached
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val provider = LocationManager.NETWORK_PROVIDER.takeIf { lm.isProviderEnabled(it) } ?: return null
        return suspendCancellableCoroutine { cont ->
            val signal = android.os.CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            runCatching {
                lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(appContext)) { loc ->
                    if (cont.isActive) cont.resume(loc)
                }
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }

    private suspend fun reverseGeocode(location: Location): String? {
        val geocoder = Geocoder(appContext, Locale.getDefault())
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(
                        location.latitude,
                        location.longitude,
                        1,
                        object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<android.location.Address>) {
                                if (cont.isActive) cont.resume(addresses.firstOrNull()?.countryCode.normalized())
                            }

                            override fun onError(errorMessage: String?) {
                                if (cont.isActive) cont.resume(null)
                            }
                        },
                    )
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(location.latitude, location.longitude, 1)
                        ?.firstOrNull()?.countryCode.normalized()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun fromTelephony(): String? {
        val tm = appContext.getSystemService<TelephonyManager>() ?: return null
        return runCatching { tm.networkCountryIso.normalized() ?: tm.simCountryIso.normalized() }.getOrNull()
    }

    private fun String?.normalized(): String? =
        this?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }

    private companion object {
        const val LOCATION_TIMEOUT_MS = 5_000L
        const val GEOCODE_TIMEOUT_MS = 5_000L
    }
}
