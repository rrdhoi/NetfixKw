package com.netflixkw.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netflixkw.core.domain.model.MovieDetail
import com.netflixkw.core.domain.repository.MovieRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DetailUiState {
    object Loading : DetailUiState
    data class Success(val movie: MovieDetail) : DetailUiState
    data class Error(val message: String) : DetailUiState
}

@HiltViewModel
class MovieDetailViewModel @Inject constructor(
    private val repository: MovieRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    private var currentImdbId: String? = null

    fun getDetail(imdbId: String) {
        if (currentImdbId == imdbId) return
        currentImdbId = imdbId

        viewModelScope.launch {
            _uiState.value = DetailUiState.Loading
            repository.getMovieDetail(imdbId).onSuccess { movieDetail ->
                // Observe favorite status
                repository.isFavorite(imdbId).collectLatest { isFav ->
                    _uiState.value = DetailUiState.Success(movieDetail.copy(isFavorite = isFav))
                }
            }.onFailure {
                _uiState.value = DetailUiState.Error(it.message ?: "Failed to load movie detail")
            }
        }
    }

    fun toggleFavorite() {
        val currentState = _uiState.value
        if (currentState is DetailUiState.Success) {
            viewModelScope.launch {
                repository.toggleFavorite(currentState.movie)
            }
        }
    }
}
