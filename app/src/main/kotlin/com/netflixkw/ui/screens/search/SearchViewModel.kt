package com.netflixkw.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netflixkw.core.domain.model.Movie
import com.netflixkw.core.domain.repository.MovieRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SearchUiState {
    object Idle : SearchUiState
    object Loading : SearchUiState
    data class Success(val movies: List<Movie>) : SearchUiState
    data class Error(val message: String) : SearchUiState
    object Empty : SearchUiState
}

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: MovieRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val searchQuery = MutableStateFlow("")
    val query: StateFlow<String> = searchQuery.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            searchQuery
                .debounce(500)
                .collectLatest { query ->
                    if (query.isBlank()) {
                        _uiState.value = SearchUiState.Idle
                    } else {
                        performSearch(query)
                    }
                }
        }
    }

    fun onSearchQueryChanged(newQuery: String) {
        searchQuery.value = newQuery
    }

    fun performSearch(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.value = SearchUiState.Loading
            repository.searchMovies(query).onSuccess { movies ->
                if (movies.isEmpty()) {
                    _uiState.value = SearchUiState.Empty
                } else {
                    repository.getFavoriteMovies().collectLatest { favorites ->
                        val favoriteIds = favorites.map { it.imdbId }.toSet()
                        val updatedMovies = movies.map { 
                            it.copy(isFavorite = favoriteIds.contains(it.imdbId)) 
                        }
                        _uiState.value = SearchUiState.Success(updatedMovies)
                    }
                }
            }.onFailure {
                _uiState.value = SearchUiState.Error(it.message ?: "Failed to load movies")
            }
        }
    }
}
