package com.netflixkw.core.domain.repository

import com.netflixkw.core.domain.model.Movie
import com.netflixkw.core.domain.model.MovieDetail
import kotlinx.coroutines.flow.Flow

interface MovieRepository {
    suspend fun searchMovies(query: String, type: String? = null): Result<List<Movie>>
    suspend fun getMovieDetail(imdbId: String): Result<MovieDetail>
    fun getFavoriteMovies(): Flow<List<Movie>>
    fun isFavorite(imdbId: String): Flow<Boolean>
    suspend fun toggleFavorite(movieDetail: MovieDetail)
    suspend fun toggleFavorite(movie: Movie)
}
