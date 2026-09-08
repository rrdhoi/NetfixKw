package com.netflixkw.core.domain.model

data class MovieDetail(
    val imdbId: String,
    val title: String,
    val year: String,
    val posterUrl: String,
    val genre: String,
    val runtime: String,
    val rating: String,
    val plot: String,
    val director: String,
    val actors: String,
    val isFavorite: Boolean = false
)
