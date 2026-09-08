package com.netflixkw.core.domain.model

data class Movie(
    val imdbId: String,
    val title: String,
    val year: String,
    val posterUrl: String,
    val isFavorite: Boolean = false
)
