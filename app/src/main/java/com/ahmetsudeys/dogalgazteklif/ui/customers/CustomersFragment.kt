package com.ahmetsudeys.dogalgazteklif.ui.customers

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.ahmetsudeys.dogalgazteklif.R
import com.ahmetsudeys.dogalgazteklif.data.customer.CustomerStorage
import com.ahmetsudeys.dogalgazteklif.data.ledger.LedgerStorage
import com.ahmetsudeys.dogalgazteklif.databinding.BottomsheetCustomerFormBinding
import com.ahmetsudeys.dogalgazteklif.databinding.FragmentCustomersBinding
import com.ahmetsudeys.dogalgazteklif.ui.util.FormSheet
import com.ahmetsudeys.dogalgazteklif.ui.util.setOnSingleClickListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CustomersFragment : Fragment() {

    private var _binding: FragmentCustomersBinding? = null
    private val binding: FragmentCustomersBinding
        get() = requireNotNull(_binding)

    private lateinit var adapter: CustomersAdapter
    private var allCustomers: List<CustomerStorage.CustomerRecord> = emptyList()
    private var currentQuery: String = ""
    private var paintFilter: PaintFilter = PaintFilter.ALL
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var refreshToken: Int = 0
    private val money: NumberFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("tr-TR")).apply {
        currency = Currency.getInstance("TRY")
        maximumFractionDigits = 0
    }
    private val filterHandler = Handler(Looper.getMainLooper())
    private var filterRunnable: Runnable? = null

    private var pendingUseLocationAction: (() -> Unit)? = null
    private val requestLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingUseLocationAction
            pendingUseLocationAction = null
            if (granted) {
                action?.invoke()
            } else {
                Toast.makeText(requireContext(), "Konum izni gerekli", Toast.LENGTH_SHORT).show()
            }
        }

    private enum class PaintFilter { ALL, PAINTED, NOT_PAINTED }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCustomersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = CustomersAdapter(
            onEdit = { record -> showCustomerSheet(record) },
            onDelete = { record -> confirmDelete(record) },
            onSetPaint = { record, painted ->
                ioExecutor.execute {
                    CustomerStorage.setPaint(requireContext(), record.createdAtMillis, painted)
                    mainHandler.post {
                        if (!isAdded) return@post
                        Toast.makeText(requireContext(), getString(R.string.updated), Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
            },
            onDirections = { _, address ->
                val addr = address.trim()
                if (addr.isBlank() || addr == "-") {
                    Toast.makeText(requireContext(), getString(R.string.error_directions_no_address), Toast.LENGTH_SHORT).show()
                } else {
                    openDirections(addr)
                }
            }
        )

        binding.recyclerCustomers.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerCustomers.adapter = adapter
        binding.recyclerCustomers.setHasFixedSize(true)
        binding.recyclerCustomers.itemAnimator = null

        binding.buttonAddCustomer.setOnSingleClickListener { showCustomerSheet(null) }

        binding.buttonLedger.setOnSingleClickListener {
            findNavController().navigate(R.id.action_customersFragment_to_ledgerFragment)
        }

        binding.editSearch.doAfterTextChanged { editable ->
            currentQuery = editable?.toString().orEmpty()
            scheduleApplyFilter()
        }

        binding.chipFilterAll.setOnClickListener {
            paintFilter = PaintFilter.ALL
            scheduleApplyFilter()
        }
        binding.chipFilterPainted.setOnClickListener {
            paintFilter = PaintFilter.PAINTED
            scheduleApplyFilter()
        }
        binding.chipFilterNotPainted.setOnClickListener {
            paintFilter = PaintFilter.NOT_PAINTED
            scheduleApplyFilter()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val token = ++refreshToken
        ioExecutor.execute {
            // Newest first: most recently added customers appear at the top.
            val loaded = CustomerStorage.getCustomers(requireContext())
                .sortedByDescending { it.createdAtMillis }
            mainHandler.post {
                if (_binding == null || token != refreshToken) return@post
                allCustomers = loaded
                scheduleApplyFilter(immediate = true)
            }
        }
    }

    private fun scheduleApplyFilter(immediate: Boolean = false) {
        filterRunnable?.let { filterHandler.removeCallbacks(it) }
        val r = Runnable { applyFilterNow() }
        filterRunnable = r
        if (immediate) filterHandler.post(r) else filterHandler.postDelayed(r, 140L)
    }

    private fun applyFilterNow() {
        val q = currentQuery.trim()
        val base = when (paintFilter) {
            PaintFilter.ALL -> allCustomers
            PaintFilter.PAINTED -> allCustomers.filter { it.painted }
            PaintFilter.NOT_PAINTED -> allCustomers.filter { !it.painted }
        }

        val filtered = if (q.isBlank()) base else base.filter { it.name.orEmpty().contains(q, ignoreCase = true) }

        binding.textEmpty.isVisible = filtered.isEmpty()
        binding.recyclerCustomers.isVisible = filtered.isNotEmpty()
        adapter.submitList(filtered)
    }

    private fun confirmDelete(record: CustomerStorage.CustomerRecord) {
        // Inspect the ledger first so we can warn about kept records / outstanding debt.
        ioExecutor.execute {
            val account = LedgerStorage.getAccount(requireContext(), record.createdAtMillis)
            val hasData = account?.hasFinancialData == true
            val remaining = account?.let { ((it.agreedAmount ?: 0.0) - it.collected).coerceAtLeast(0.0) } ?: 0.0
            mainHandler.post {
                if (!isAdded) return@post
                showDeleteDialog(record, hasData, remaining)
            }
        }
    }

    private fun showDeleteDialog(
        record: CustomerStorage.CustomerRecord,
        hasLedgerData: Boolean,
        remaining: Double
    ) {
        val message = when {
            remaining > 0.009 -> getString(R.string.delete_customer_message_debt, money.format(remaining))
            hasLedgerData -> getString(R.string.delete_customer_message_kept)
            else -> getString(R.string.delete_customer_message)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_customer_title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_customer) { _, _ ->
                ioExecutor.execute {
                    CustomerStorage.deleteCustomer(requireContext(), record.createdAtMillis)
                    // Keep the ledger record (with a name/phone snapshot) when it holds financial data.
                    LedgerStorage.detachDeletedCustomer(
                        requireContext(),
                        record.createdAtMillis,
                        record.name,
                        record.phone
                    )
                    mainHandler.post { if (isAdded) refresh() }
                }
            }
            .show()
    }

    private fun openDirections(address: String) {
        // Prefer Google Maps navigation; then any installed maps app; finally fall back to browser (Google Maps web).
        // NOTE: On Android 11+ package visibility rules can make resolveActivity() return null even when apps exist,
        // so we rely on startActivity try/catch instead of pre-checking.
        val encoded = Uri.encode(address)

        runCatching {
            val mapsIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$encoded")).apply {
                setPackage("com.google.android.apps.maps")
            }
            startActivity(mapsIntent)
        }.onSuccess { return }

        runCatching {
            val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded"))
            startActivity(geoIntent)
        }.onSuccess { return }

        runCatching {
            // Web fallback: open directions in browser.
            val web = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$encoded&travelmode=driving")
            val webIntent = Intent(Intent.ACTION_VIEW, web)
            startActivity(webIntent)
        }.onSuccess { return }

        Toast.makeText(requireContext(), "Harita açılamadı", Toast.LENGTH_SHORT).show()
    }

    private fun showCustomerSheet(existing: CustomerStorage.CustomerRecord?) {
        val sheet = BottomsheetCustomerFormBinding.inflate(layoutInflater)
        val dialog = FormSheet.create(requireContext(), sheet.root)
        // Başlığa, etiketlere ya da alanlar arası boşluğa dokununca klavye kapansın.
        FormSheet.dismissKeyboardOnTap(sheet.sheetRoot, sheet.scrollForm)

        val isEdit = existing != null
        sheet.textTitle.text = getString(if (isEdit) R.string.edit_customer else R.string.add_customer)
        sheet.buttonDelete.isVisible = isEdit

        fun s(v: String?): String = v.orEmpty()

        if (existing != null) {
            sheet.editName.setText(s(existing.name))
            sheet.editPhone.setText(s(existing.phone))
            sheet.editTcNo.setText(s(existing.tcNo))
            sheet.editBuildingCode.setText(s(existing.buildingCode))
            sheet.editInstallationNo.setText(s(existing.installationNo))

            sheet.editCity.setText(s(existing.address.city))
            sheet.editDistrict.setText(s(existing.address.district))
            sheet.editNeighborhood.setText(s(existing.address.neighborhood))
            sheet.editStreet.setText(s(existing.address.street))
            sheet.editBuildingNo.setText(s(existing.address.buildingNo))
            sheet.editApartmentNo.setText(s(existing.address.apartmentNo))

            sheet.checkboxPainted.isChecked = existing.painted
        }

        fun previewAddress() {
            val addr = CustomerStorage.AddressParts(
                city = sheet.editCity.text?.toString().orEmpty(),
                district = sheet.editDistrict.text?.toString().orEmpty(),
                neighborhood = sheet.editNeighborhood.text?.toString().orEmpty(),
                street = sheet.editStreet.text?.toString().orEmpty(),
                buildingNo = sheet.editBuildingNo.text?.toString().orEmpty(),
                apartmentNo = sheet.editApartmentNo.text?.toString().orEmpty()
            ).preview()
            sheet.textAddressPreview.text = addr.ifBlank { "-" }
        }

        previewAddress()
        listOf(
            sheet.editCity,
            sheet.editDistrict,
            sheet.editNeighborhood,
            sheet.editStreet,
            sheet.editBuildingNo,
            sheet.editApartmentNo
        ).forEach { edit ->
            edit.doAfterTextChanged { previewAddress() }
        }

        sheet.textUseCurrentLocation.setOnSingleClickListener {
            val run = {
                fillAddressFromCurrentLocation(
                    sheet = sheet,
                    onPreviewUpdated = { previewAddress() }
                )
            }
            if (hasLocationPermission()) {
                run()
            } else {
                pendingUseLocationAction = run
                requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        sheet.buttonSave.setOnSingleClickListener {
            FormSheet.hideKeyboard(sheet.sheetRoot)
            val name = sheet.editName.text?.toString().orEmpty().trim()
            if (name.isBlank()) {
                sheet.inputName.error = getString(R.string.error_customer_required)
                // Ad alanı formun en üstünde: hata mesajı görünsün diye başa sar.
                sheet.scrollForm.smoothScrollTo(0, 0)
                return@setOnSingleClickListener
            }
            sheet.inputName.error = null

            val now = System.currentTimeMillis()
            val createdAt = existing?.createdAtMillis ?: now

            val record = CustomerStorage.CustomerRecord(
                name = name,
                phone = sheet.editPhone.text?.toString().orEmpty().trim().takeIf { it.isNotBlank() },
                tcNo = sheet.editTcNo.text?.toString().orEmpty().trim().takeIf { it.isNotBlank() },
                buildingCode = sheet.editBuildingCode.text?.toString().orEmpty().trim().takeIf { it.isNotBlank() },
                installationNo = sheet.editInstallationNo.text?.toString().orEmpty().trim().takeIf { it.isNotBlank() },
                address = CustomerStorage.AddressParts(
                    city = sheet.editCity.text?.toString().orEmpty().trim().takeIf { it.isNotBlank() },
                    district = sheet.editDistrict.text?.toString().orEmpty().trim().takeIf { it.isNotBlank() },
                    neighborhood = sheet.editNeighborhood.text?.toString().orEmpty().trim().takeIf { it.isNotBlank() },
                    street = sheet.editStreet.text?.toString().orEmpty().trim().takeIf { it.isNotBlank() },
                    buildingNo = sheet.editBuildingNo.text?.toString().orEmpty().trim().takeIf { it.isNotBlank() },
                    apartmentNo = sheet.editApartmentNo.text?.toString().orEmpty().trim().takeIf { it.isNotBlank() }
                ),
                painted = sheet.checkboxPainted.isChecked,
                createdAtMillis = createdAt,
                updatedAtMillis = now
            )

            val appCtx = requireContext().applicationContext
            ioExecutor.execute {
                // Disallow two customers with the same name (case-insensitive). This keeps the
                // customer/quote/ledger matching unambiguous and prevents a new customer from
                // accidentally sharing a same-named person's receivable.
                val nameLc = name.lowercase()
                val duplicate = CustomerStorage.getCustomers(appCtx).any {
                    it.createdAtMillis != createdAt && it.name.orEmpty().trim().lowercase() == nameLc
                }
                if (duplicate) {
                    mainHandler.post {
                        if (!isAdded) return@post
                        sheet.inputName.error = getString(R.string.error_customer_duplicate_name)
                        sheet.scrollForm.smoothScrollTo(0, 0)
                    }
                    return@execute
                }

                CustomerStorage.upsertCustomer(appCtx, record)
                mainHandler.post {
                    if (!isAdded) return@post
                    dialog.dismiss()
                    refresh()
                }
            }
        }

        sheet.buttonDelete.setOnSingleClickListener {
            if (existing == null) return@setOnSingleClickListener
            FormSheet.hideKeyboard(sheet.sheetRoot)
            dialog.dismiss()
            confirmDelete(existing)
        }

        dialog.show()
    }

    private fun hasLocationPermission(): Boolean {
        val ctx = requireContext()
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun fillAddressFromCurrentLocation(
        sheet: BottomsheetCustomerFormBinding,
        onPreviewUpdated: () -> Unit
    ) {
        val ctx = requireContext().applicationContext
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        fun setLoading(loading: Boolean) {
            sheet.textUseCurrentLocation.isEnabled = !loading
            sheet.textUseCurrentLocation.alpha = if (loading) 0.6f else 1.0f
        }

        setLoading(true)
        Toast.makeText(requireContext(), "Konum alınıyor...", Toast.LENGTH_SHORT).show()

        ioExecutor.execute {
            val last = bestLastKnownLocation(lm)
            if (last != null) {
                reverseGeocodeAndFill(ctx, last, sheet, onPreviewUpdated, setLoading = ::setLoading)
                return@execute
            }

            // If no cached location is available, request a single update (main thread callback).
            mainHandler.post {
                val provider = when {
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    else -> null
                }
                if (provider == null) {
                    setLoading(false)
                    Toast.makeText(requireContext(), "Konum servisleri kapalı", Toast.LENGTH_SHORT).show()
                    return@post
                }

                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        ioExecutor.execute {
                            reverseGeocodeAndFill(ctx, location, sheet, onPreviewUpdated, setLoading = ::setLoading)
                        }
                    }
                }
                try {
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                } catch (t: Throwable) {
                    setLoading(false)
                    Toast.makeText(requireContext(), "Konum alınamadı", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun bestLastKnownLocation(lm: LocationManager): Location? {
        return try {
            val gps = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
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
        appContext: Context,
        location: Location,
        sheet: BottomsheetCustomerFormBinding,
        onPreviewUpdated: () -> Unit,
        setLoading: (Boolean) -> Unit
    ) {
        val addr = try {
            if (!Geocoder.isPresent()) null
            else {
                val geocoder = Geocoder(appContext, Locale("tr", "TR"))
                geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
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
            if (!isAdded) return@post
            setLoading(false)

            if (addr == null) {
                Toast.makeText(requireContext(), "Adres bulunamadı", Toast.LENGTH_SHORT).show()
                return@post
            }

            if (city.isNotBlank()) sheet.editCity.setText(city)
            if (district.isNotBlank()) sheet.editDistrict.setText(district)
            if (neighborhood.isNotBlank()) sheet.editNeighborhood.setText(neighborhood)
            if (street.isNotBlank()) sheet.editStreet.setText(street)
            if (buildingNo.isNotBlank()) sheet.editBuildingNo.setText(buildingNo)

            onPreviewUpdated()
            Toast.makeText(requireContext(), "Adres dolduruldu", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        filterRunnable?.let { filterHandler.removeCallbacks(it) }
        filterRunnable = null
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
    }
}



