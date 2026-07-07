package com.ahmetsudeys.rotauygulama.ui.quote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.ahmetsudeys.rotauygulama.data.excel.ExcelPriceListRepository
import com.ahmetsudeys.rotauygulama.data.model.MaterialItem
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class QuoteMaterialsPageViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ExcelPriceListRepository(app)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private val _state = MutableLiveData<UiState>()
    val state: LiveData<UiState> = _state

    fun load(operationName: String) {
        _state.postValue(UiState.Loading)
        executor.execute {
            try {
                val list = repo.getMaterials(operationName)
                _state.postValue(UiState.Content(operationName, list))
            } catch (t: Throwable) {
                _state.postValue(UiState.Error(operationName))
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        executor.shutdownNow()
    }

    sealed class UiState {
        data object Loading : UiState()
        data class Content(val operationName: String, val items: List<MaterialItem>) : UiState()
        data class Error(val operationName: String) : UiState()
    }
}


