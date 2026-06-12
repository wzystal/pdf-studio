package com.pdfstudio.feature.filelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pdfstudio.core.storage.RecentFileRepository
import com.pdfstudio.core.storage.entity.RecentFileEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FileListViewModel @Inject constructor(
    private val recentFileRepository: RecentFileRepository,
) : ViewModel() {

    val recentFiles: StateFlow<List<RecentFileEntity>> = recentFileRepository
        .observeRecentFiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun removeRecent(uri: String) {
        viewModelScope.launch {
            recentFileRepository.remove(android.net.Uri.parse(uri))
        }
    }
}
