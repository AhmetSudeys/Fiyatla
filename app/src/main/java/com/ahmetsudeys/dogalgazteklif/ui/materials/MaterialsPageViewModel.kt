package com.ahmetsudeys.dogalgazteklif.ui.materials

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.ahmetsudeys.dogalgazteklif.data.excel.ExcelPriceListRepository
import com.ahmetsudeys.dogalgazteklif.data.model.MaterialItem
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MaterialsPageViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ExcelPriceListRepository(app)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private val _state = MutableLiveData<UiState>()
    val state: LiveData<UiState> = _state

    /** One-shot structural events (list renamed / deleted) so the host can refresh its tabs. */
    private val _listEvent = MutableLiveData<ListEvent?>()
    val listEvent: LiveData<ListEvent?> = _listEvent

    private var currentSheet: String = ""
    private var isCustom: Boolean = false
    private val items = ArrayList<MaterialItem>()

    fun load(sheetName: String) {
        currentSheet = sheetName
        _state.postValue(UiState.Loading)
        executor.execute {
            try {
                isCustom = repo.isCustomList(sheetName)
                val list = repo.getMaterials(sheetName)
                items.clear()
                items.addAll(list)
                postContent()
            } catch (_: Throwable) {
                _state.postValue(UiState.Error(sheetName))
            }
        }
    }

    fun addMaterial(name: String, quantity: Double, price: Double) {
        executor.execute {
            items.add(MaterialItem(name = name.trim(), quantity = quantity, price = price))
            persistAndPost()
        }
    }

    fun updateMaterial(index: Int, name: String, quantity: Double, price: Double) {
        executor.execute {
            if (index in items.indices) {
                items[index] = MaterialItem(name = name.trim(), quantity = quantity, price = price)
                persistAndPost()
            }
        }
    }

    fun deleteMaterial(index: Int) {
        executor.execute {
            if (index in items.indices) {
                items.removeAt(index)
                persistAndPost()
            }
        }
    }

    fun renameList(newName: String) {
        executor.execute {
            val ok = repo.renameList(currentSheet, newName)
            if (ok) {
                currentSheet = newName.trim()
                _listEvent.postValue(ListEvent.Renamed(currentSheet))
            } else {
                _listEvent.postValue(ListEvent.Failed)
            }
        }
    }

    fun deleteList() {
        executor.execute {
            repo.deleteList(currentSheet)
            _listEvent.postValue(ListEvent.Deleted)
        }
    }

    fun consumeListEvent() {
        _listEvent.value = null
    }

    private fun persistAndPost() {
        repo.saveMaterials(currentSheet, items.toList())
        postContent()
    }

    private fun postContent() {
        _state.postValue(UiState.Content(currentSheet, items.toList(), isCustom))
    }

    override fun onCleared() {
        super.onCleared()
        executor.shutdownNow()
    }

    sealed class UiState {
        data object Loading : UiState()
        data class Content(
            val sheetName: String,
            val items: List<MaterialItem>,
            val isCustom: Boolean
        ) : UiState()
        data class Error(val sheetName: String) : UiState()
    }

    sealed class ListEvent {
        data class Renamed(val newName: String) : ListEvent()
        data object Deleted : ListEvent()
        data object Failed : ListEvent()
    }
}
