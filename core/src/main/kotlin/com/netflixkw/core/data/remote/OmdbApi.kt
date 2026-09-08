package com.netflixkw.core.data.remote

import com.netflixkw.core.data.remote.response.MovieDetailDto
import com.netflixkw.core.data.remote.response.SearchResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface OmdbApi {
    @GET("/")
    suspend fun searchMovies(
        @Query("s") query: String,
        @Query("apikey") apiKey: String,
        @Query("type") type: String? = null,
        @Query("page") page: Int = 1
    ): SearchResponse

    @GET("/")
    suspend fun getMovieDetail(
        @Query("i") imdbId: String,
        @Query("apikey") apiKey: String
    ): MovieDetailDto
}
