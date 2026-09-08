package com.netflixkw.core.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorite_movies")
data class MovieEntity(
    @PrimaryKey val imdbId: String,
    val title: String,
    val year: String,
    val posterUrl: String,
    val genre: String? = null,
    val runtime: String? = null,
    val rating: String? = null,
    val plot: String? = null,
    val director: String? = null,
    val actors: String? = null
)
