package com.netflixkw.core.data.repository

import com.netflixkw.core.BuildConfig
import com.netflixkw.core.data.local.MovieDao
import com.netflixkw.core.data.local.MovieEntity
import com.netflixkw.core.data.remote.OmdbApi
import com.netflixkw.core.domain.model.Movie
import com.netflixkw.core.domain.model.MovieDetail
import com.netflixkw.core.domain.repository.MovieRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

@Singleton
class MovieRepositoryImpl @Inject constructor(
    private val api: OmdbApi,
    private val dao: MovieDao
) : MovieRepository {

    override suspend fun searchMovies(query: String, type: String?): Result<List<Movie>> {
        return try {
            coroutineScope {
                val page1 = async { api.searchMovies(query, BuildConfig.API_KEY, type, 1) }
                val page2 = async { api.searchMovies(query, BuildConfig.API_KEY, type, 2) }
                
                val response1 = page1.await()
                val response2 = page2.await()

                val results = mutableListOf<Movie>()

                if (response1.response == "True" && response1.search != null) {
                    results.addAll(response1.search.map { dto ->
                        Movie(
                            imdbId = dto.imdbId,
                            title = dto.title,
                            year = dto.year,
                            posterUrl = dto.poster,
                            isFavorite = false
                        )
                    })
                }
                
                if (response2.response == "True" && response2.search != null) {
                    results.addAll(response2.search.map { dto ->
                        Movie(
                            imdbId = dto.imdbId,
                            title = dto.title,
                            year = dto.year,
                            posterUrl = dto.poster,
                            isFavorite = false
                        )
                    })
                }
                
                if (results.isNotEmpty()) {
                    Result.success(results)
                } else {
                    Result.failure(Exception(response1.error ?: "Unknown error occurred"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getMovieDetail(imdbId: String): Result<MovieDetail> {
        return try {
            val response = api.getMovieDetail(imdbId, BuildConfig.API_KEY)
            if (response.response == "True") {
                Result.success(
                    MovieDetail(
                        imdbId = response.imdbId,
                        title = response.title,
                        year = response.year,
                        posterUrl = response.poster,
                        genre = response.genre ?: "-",
                        runtime = response.runtime ?: "-",
                        rating = response.rating ?: "-",
                        plot = response.plot ?: "No plot available",
                        director = response.director ?: "-",
                        actors = response.actors ?: "-",
                        isFavorite = false
                    )
                )
            } else {
                Result.failure(Exception(response.error ?: "Unknown error occurred"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getFavoriteMovies(): Flow<List<Movie>> {
        return dao.getFavoriteMovies().map { entities ->
            entities.map {
                Movie(
                    imdbId = it.imdbId,
                    title = it.title,
                    year = it.year,
                    posterUrl = it.posterUrl,
                    isFavorite = true
                )
            }
        }
    }

    override fun isFavorite(imdbId: String): Flow<Boolean> {
        return dao.getFavoriteMovieById(imdbId).map { it != null }
    }

    override suspend fun toggleFavorite(movieDetail: MovieDetail) {
        if (movieDetail.isFavorite) {
            dao.deleteFavoriteMovieById(movieDetail.imdbId)
        } else {
            dao.insertFavoriteMovie(
                MovieEntity(
                    imdbId = movieDetail.imdbId,
                    title = movieDetail.title,
                    year = movieDetail.year,
                    posterUrl = movieDetail.posterUrl,
                    genre = movieDetail.genre,
                    runtime = movieDetail.runtime,
                    rating = movieDetail.rating,
                    plot = movieDetail.plot,
                    director = movieDetail.director,
                    actors = movieDetail.actors
                )
            )
        }
    }

    override suspend fun toggleFavorite(movie: Movie) {
        if (movie.isFavorite) {
            dao.deleteFavoriteMovieById(movie.imdbId)
        } else {
            dao.insertFavoriteMovie(
                MovieEntity(
                    imdbId = movie.imdbId,
                    title = movie.title,
                    year = movie.year,
                    posterUrl = movie.posterUrl
                )
            )
        }
    }
}
