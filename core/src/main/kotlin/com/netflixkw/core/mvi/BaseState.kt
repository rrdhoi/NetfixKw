package com.netflixkw.core.mvi

data class BaseState<T>(
    val data: T,
    val loading: Boolean = false,
    val error: Throwable? = null
)
