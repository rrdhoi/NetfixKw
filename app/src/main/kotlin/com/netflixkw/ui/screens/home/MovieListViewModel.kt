package com.netflixkw.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.netflixkw.core.domain.repository.MovieRepository
import com.netflixkw.core.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MovieListViewModel @Inject constructor(
    private val repository: MovieRepository
) : MviViewModel<MovieListEvent, MovieListState, MovieListEffect>(MovieListState()) {

    private var fetchJob: Job? = null

    init {
        onEvent(MovieListEvent.LoadMovies)
    }

    override fun handleEvent(event: MovieListEvent) {
        when (event) {
            is MovieListEvent.LoadMovies -> fetchMovies()
            is MovieListEvent.OnFilterSelected -> {
                updateData { copy(filterType = event.type) }
                fetchMovies()
            }
            is MovieListEvent.OnMovieClick -> {
                sendEffect { MovieListEffect.NavigateToDetail(event.imdbId) }
            }
            is MovieListEvent.OnFavoriteClick -> {
                viewModelScope.launch {
                    repository.toggleFavorite(event.movie)
                }
            }
        }
    }

    private fun fetchMovies() {
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            setState { copy(loading = true, error = null) }
            val currentFilter = state.value.data.filterType
            repository.searchMovies("marvel", currentFilter).onSuccess { movies ->
                if (movies.isEmpty()) {
                    setState { copy(loading = false, data = data.copy(movies = emptyList())) }
                } else {
                    repository.getFavoriteMovies().collectLatest { favorites ->
                        val favoriteIds = favorites.map { it.imdbId }.toSet()
                        val updatedMovies = movies.map { 
                            it.copy(isFavorite = favoriteIds.contains(it.imdbId)) 
                        }
                        setState { copy(loading = false, data = data.copy(movies = updatedMovies)) }
                    }
                }
            }.onFailure {
                setState { copy(loading = false, error = it) }
            }
        }
    }
}
