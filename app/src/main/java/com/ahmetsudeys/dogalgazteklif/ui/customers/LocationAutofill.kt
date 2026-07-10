package com.ahmetsudeys.dogalgazteklif.ui.customers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.ahmetsudeys.dogalgazteklif.R
import java.util.Locale

/**
 * Reusable "use current location" address auto-fill.
 *
 * Works with any set of address [Fields] (customer form sheet, quote creation screen, ...).
 * Permission model is intentionally simple so it can run from a plain dialog: if permission is
 * missing we request it and ask the user to tap again once granted.
 */
object LocationAutofill {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** The address inputs to fill, plus the clickable trigger view used for the loading state. */
    class Fields(
        val trigger: View,
        val city: EditText,
        val district: EditText,
        val neighborhood: EditText,
        val street: EditText,
        val buildingNo: EditText
    )

    fun hasPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun requestPermission(fragment: Fragment) {
        ActivityCompat.requestPermissions(
            fragment.requireActivity(),
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            REQUEST_CODE
        )
    }

    /** Entry point wired to the "use current location" trigger. Handles permission + fill. */
    fun useCurrentLocation(fragment: Fragment, fields: Fields, onFilled: () -> Unit) {
        val ctx = fragment.requireContext()
        if (!hasPermission(ctx)) {
            requestPermission(fragment)
            Toast.makeText(ctx, R.string.location_permission_hint, Toast.LENGTH_SHORT).show()
            return
        }
        fill(fragment, fields, onFilled)
    }

    private fun fill(fragment: Fragment, fields: Fields, onFilled: () -> Unit) {
        val appContext = fragment.requireContext().applicationContext
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        fun setLoading(loading: Boolean) {
            fields.trigger.isEnabled = !loading
            fields.trigger.alpha = if (loading) 0.6f else 1.0f
        }

        setLoading(true)
        Toast.makeText(fragment.requireContext(), "Konum alınıyor...", Toast.LENGTH_SHORT).show()

        Thread {
            val last = bestLastKnownLocation(lm)
            if (last != null) {
                reverseGeocodeAndFill(fragment, appContext, last, fields, onFilled, ::setLoading)
                return@Thread
            }

            mainHandler.post {
                val provider = when {
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    else -> null
                }
                if (provider == null) {
                    setLoading(false)
                    Toast.makeText(fragment.requireContext(), "Konum servisleri kapalı", Toast.LENGTH_SHORT).show()
                    return@post
                }
                if (!hasPermission(appContext)) {
                    setLoading(false)
                    return@post
                }
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        Thread {
                            reverseGeocodeAndFill(fragment, appContext, location, fields, onFilled, ::setLoading)
                        }.start()
                    }

                    override fun onProviderDisabled(provider: String) {}
                    override fun onProviderEnabled(provider: String) {}
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                }
                try {
                    @Suppress("MissingPermission")
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                } catch (_: Throwable) {
                    setLoading(false)
                    Toast.makeText(fragment.requireContext(), "Konum alınamadı", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun bestLastKnownLocation(lm: LocationManager): Location? {
        return try {
            @Suppress("MissingPermission")
            val gps = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
            @Suppress("MissingPermission")
            val net = runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
            when {
                gps == null -> net
                net == null -> gps
                else -> if (gps.time >= net.time) gps else net
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun reverseGeocodeAndFill(
        fragment: Fragment,
        appContext: Context,
        location: Location,
        fields: Fields,
        onFilled: () -> Unit,
        setLoading: (Boolean) -> Unit
    ) {
        val addr = try {
            if (!Geocoder.isPresent()) {
                null
            } else {
                @Suppress("DEPRECATION")
                Geocoder(appContext, Locale("tr", "TR"))
                    .getFromLocation(location.latitude, location.longitude, 1)
                    ?.firstOrNull()
            }
        } catch (_: Throwable) {
            null
        }

        val city = addr?.adminArea?.trim().orEmpty()
        val district = (addr?.subAdminArea ?: addr?.locality)?.trim().orEmpty()
        val neighborhood = addr?.subLocality?.trim().orEmpty()
        val street = addr?.thoroughfare?.trim().orEmpty()
        val buildingNo = addr?.subThoroughfare?.trim().orEmpty()

        mainHandler.post {
            if (!fragment.isAdded) return@post
            setLoading(false)
            if (addr == null) {
                Toast.makeText(fragment.requireContext(), "Adres bulunamadı", Toast.LENGTH_SHORT).show()
                return@post
            }
            if (city.isNotBlank()) fields.city.setText(city)
            if (district.isNotBlank()) {
                // Avoid opening the dropdown/filtering when the district input is an AutoCompleteTextView.
                val d = fields.district
                if (d is android.widget.AutoCompleteTextView) d.setText(district, false) else d.setText(district)
            }
            if (neighborhood.isNotBlank()) fields.neighborhood.setText(neighborhood)
            if (street.isNotBlank()) fields.street.setText(street)
            if (buildingNo.isNotBlank()) fields.buildingNo.setText(buildingNo)
            onFilled()
            Toast.makeText(fragment.requireContext(), "Adres dolduruldu", Toast.LENGTH_SHORT).show()
        }
    }

    private const val REQUEST_CODE = 8021
}
