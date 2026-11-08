package com.example.myapplication.ui.sections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.SectionRepository
import com.example.myapplication.data.db.SectionWithCount
import com.example.myapplication.domain.SectionNameError
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SectionsViewModel(
    private val repository: SectionRepository,
) : ViewModel() {

    val sections: StateFlow<List<SectionWithCount>> =
        repository.observeVisibleSections()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createSection(name: String, onResult: (SectionNameError?) -> Unit) {
        viewModelScope.launch {
            onResult(repository.createSection(name, DEFAULT_SECTION_ICON))
        }
    }

    companion object {
        /** Иконка по умолчанию для пользовательских разделов. */
        const val DEFAULT_SECTION_ICON = "other"
    }
}
