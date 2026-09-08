package com.netflixkw.ui.screens.home

import com.netflixkw.core.domain.model.Movie
import com.netflixkw.core.mvi.UiEffect
import com.netflixkw.core.mvi.UiEvent
import com.netflixkw.core.mvi.UiState

data class MovieListState(
    val movies: List<Movie> = emptyList(),
    val filterType: String? = null
) : UiState

sealed interface MovieListEvent : UiEvent {
    object LoadMovies : MovieListEvent
    data class OnFilterSelected(val type: String?) : MovieListEvent
    data class OnMovieClick(val imdbId: String) : MovieListEvent
    data class OnFavoriteClick(val movie: Movie) : MovieListEvent
}

sealed interface MovieListEffect : UiEffect {
    data class NavigateToDetail(val imdbId: String) : MovieListEffect
}
