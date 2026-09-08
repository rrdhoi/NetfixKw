package com.netflixkw.ui.screens.favorite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netflixkw.core.domain.model.Movie
import com.netflixkw.core.domain.repository.MovieRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface FavoriteUiState {
    object Loading : FavoriteUiState
    data class Success(val movies: List<Movie>) : FavoriteUiState
    object Empty : FavoriteUiState
}

@HiltViewModel
class FavoriteViewModel @Inject constructor(
    repository: MovieRepository
) : ViewModel() {

    val uiState: StateFlow<FavoriteUiState> = repository.getFavoriteMovies()
        .map { movies ->
            if (movies.isEmpty()) {
                FavoriteUiState.Empty
            } else {
                FavoriteUiState.Success(movies)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = FavoriteUiState.Loading
        )
}
