package org.heyogesh.drive.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.heyogesh.drive.api.DriveApi
import org.heyogesh.drive.api.DriveItem

data class BrowserState(
    val path: String = "",
    val items: List<DriveItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

class DriveViewModel(application: Application) : AndroidViewModel(application) {
    val api = DriveApi(application)
    private val mutableState = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = mutableState.asStateFlow()

    fun load(path: String = mutableState.value.path) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(isLoading = true, error = null)
            try {
                val response = api.folder(path)
                mutableState.value = BrowserState(path = response.path, items = response.items)
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(isLoading = false, error = error.message ?: "Could not load this folder.")
            }
        }
    }

    fun goUp() {
        val current = mutableState.value.path
        if (current.isEmpty()) return
        load(current.substringBeforeLast('/', ""))
    }
}
