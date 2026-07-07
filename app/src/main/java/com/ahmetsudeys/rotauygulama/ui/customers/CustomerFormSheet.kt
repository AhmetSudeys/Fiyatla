package com.ahmetsudeys.rotauygulama.ui.customers

import android.view.WindowManager
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.ahmetsudeys.rotauygulama.R
import com.ahmetsudeys.rotauygulama.data.customer.CustomerStorage
import com.ahmetsudeys.rotauygulama.databinding.BottomsheetCustomerFormBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Lightweight, reusable customer form dialog (name, phone, TC no, address, ...).
 *
 * Used by the "approve quote -> add customer" flow so the same fields as the Customers screen can
 * be reviewed/completed from anywhere. Location auto-fill and delete are intentionally omitted here
 * (those stay in the full Customers screen).
 */
object CustomerFormSheet {

    fun show(
        fragment: Fragment,
        prefill: CustomerStorage.CustomerRecord?,
        titleRes: Int = R.string.customer_info_title,
        onSaved: (CustomerStorage.CustomerRecord) -> Unit
    ) {
        val ctx = fragment.requireContext()
        val dialog = MaterialAlertDialogBuilder(ctx).create()
        val sheet = BottomsheetCustomerFormBinding.inflate(fragment.layoutInflater)
        dialog.setView(sheet.root)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        sheet.textTitle.text = ctx.getString(titleRes)
        sheet.buttonDelete.isVisible = false
        // Offer an explicit "leave blank" escape so nothing feels mandatory.
        sheet.buttonSkip.isVisible = true

        fun s(v: String?): String = v.orEmpty()

        prefill?.let { existing ->
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
        ).forEach { edit -> edit.doAfterTextChanged { previewAddress() } }

        sheet.textUseCurrentLocation.setOnClickListener {
            LocationAutofill.useCurrentLocation(
                fragment,
                LocationAutofill.Fields(
                    trigger = sheet.textUseCurrentLocation,
                    city = sheet.editCity,
                    district = sheet.editDistrict,
                    neighborhood = sheet.editNeighborhood,
                    street = sheet.editStreet,
                    buildingNo = sheet.editBuildingNo
                )
            ) { previewAddress() }
        }

        // Nothing is mandatory here: the info may simply be unknown at this point.
        fun buildRecord(): CustomerStorage.CustomerRecord {
            fun f(v: CharSequence?) = v?.toString().orEmpty().trim().takeIf { it.isNotBlank() }
            return CustomerStorage.CustomerRecord(
                name = f(sheet.editName.text),
                phone = f(sheet.editPhone.text),
                tcNo = f(sheet.editTcNo.text),
                buildingCode = f(sheet.editBuildingCode.text),
                installationNo = f(sheet.editInstallationNo.text),
                address = CustomerStorage.AddressParts(
                    city = f(sheet.editCity.text),
                    district = f(sheet.editDistrict.text),
                    neighborhood = f(sheet.editNeighborhood.text),
                    street = f(sheet.editStreet.text),
                    buildingNo = f(sheet.editBuildingNo.text),
                    apartmentNo = f(sheet.editApartmentNo.text)
                ),
                painted = sheet.checkboxPainted.isChecked,
                createdAtMillis = prefill?.createdAtMillis ?: System.currentTimeMillis()
            )
        }

        fun save() {
            dialog.dismiss()
            onSaved(buildRecord())
        }

        sheet.buttonSave.setOnClickListener { save() }
        sheet.buttonSkip.setOnClickListener { save() }

        dialog.show()
    }
}
