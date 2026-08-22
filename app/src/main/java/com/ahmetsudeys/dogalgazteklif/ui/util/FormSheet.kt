package com.ahmetsudeys.dogalgazteklif.ui.util

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.math.abs

/**
 * Klavyeyle rahat çalışan form sayfalarının (Ödeme Ekle, Müşteri Ekle/Düzenle) ortak kurulumu.
 *
 * Eskiden bu formlar MaterialAlertDialog içindeydi: klavye açılınca pencere avuç içi kadar
 * kalıyor, kaydet butonu klavyenin altında kayboluyordu. Artık tam açılmış bir BottomSheet
 * kullanılıyor; ADJUST_RESIZE ile sayfa klavyenin üstündeki alanın TAMAMINI kaplar, form kendi
 * içinde kayar ve kaydet butonuna aşağı kaydırarak ulaşılır.
 *
 * Kullanılan layout'ların kökü şu üç niteliğe sahip olmalıdır, yoksa [hideKeyboard] çalışmaz:
 * `clickable`, `focusableInTouchMode` ve `descendantFocusability="beforeDescendants"`.
 */
object FormSheet {

    /** Tam açılmış, klavyeyle birlikte yeniden boyutlanan bir alt sayfa oluşturur. */
    fun create(context: Context, content: View): BottomSheetDialog {
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(content)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.behavior.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isFitToContents = true
        }
        return dialog
    }

    /**
     * Formun boş bir yerine (başlık, etiketler, alanlar arası boşluk...) dokununca klavyeyi
     * kapatır. [sheetRoot] sayfanın odaklanabilir kökü, [alsoOn] ise genelde kaydırma alanıdır.
     *
     * OnClickListener DEĞİL: kök focusableInTouchMode olduğundan ilk dokunuşta ACTION_DOWN odağı
     * alır ve View o dokunuş için performClick()'i atlar; yani asıl önemli olan ilk dokunuş hiç
     * tıklama üretmez. Dinleyici false döner ve yalnızca yerinde duran (kaydırma olmayan)
     * dokunuşlara tepki verir; böylece kaydırma ve tıklama davranışı bozulmaz.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun dismissKeyboardOnTap(sheetRoot: View, vararg alsoOn: View) {
        val touchSlop = ViewConfiguration.get(sheetRoot.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        val listener = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(event.x - downX) < touchSlop && abs(event.y - downY) < touchSlop) {
                        hideKeyboard(sheetRoot)
                    }
                }
            }
            false
        }
        sheetRoot.setOnTouchListener(listener)
        alsoOn.forEach { it.setOnTouchListener(listener) }
    }

    /**
     * Klavyeyi kapatır. [root], odaklanabilir kök görünüm olmalıdır: odağı önce ona almak şart,
     * çünkü EditText üzerinde doğrudan clearFocus() çağırmak odağı bir SONRAKİ EditText'e taşıyıp
     * klavyeyi anında yeniden açıyor. Gizleme, post ile odak değişiminin ARDINDAN çalışır; aynı
     * karede yapılırsa yeni odak için başlatılan input oturumu klavyeyi geri getiriyor.
     */
    fun hideKeyboard(root: View) {
        root.requestFocus()
        root.post {
            ViewCompat.getWindowInsetsController(root)?.hide(WindowInsetsCompat.Type.ime())
            val imm = ContextCompat.getSystemService(root.context, InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(root.windowToken, 0)
        }
    }
}
