package com.ahmetsudeys.dogalgazteklif

import android.os.Bundle
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.getSystemService
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.ahmetsudeys.dogalgazteklif.data.excel.ExcelPriceListRepository
import com.ahmetsudeys.dogalgazteklif.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        // Force the app to always use the same (light) colors regardless of system dark mode.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Paketteki fiyat listesi (.xlsx) süreç başına bir kez ayrıştırılıyor. Bunu açılışta arka
        // planda yapıyoruz; aksi halde ilk kez Malzemeler sekmesine girildiğinde ekran önce boş
        // açılıp sekmeler ve liste sonradan düşüyor, takılıyormuş gibi görünüyordu.
        val appCtx = applicationContext
        Thread({ ExcelPriceListRepository(appCtx).warmUp() }, "price-list-warmup").apply {
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            currentFocus?.let { focused ->
                focused.clearFocus()
                val imm = getSystemService<InputMethodManager>()
                imm?.hideSoftInputFromWindow(focused.windowToken, 0)
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}