package com.netflixkw.core.mvi

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

abstract class MviViewModel<E : UiEvent, S : UiState, F : UiEffect>(initialState: S) : ViewModel() {

    private val _state = MutableStateFlow(BaseState(initialState))
    val state: StateFlow<BaseState<S>> = _state.asStateFlow()

    private val _effect = MutableSharedFlow<F>(replay = 0, extraBufferCapacity = 1)
    val effect = _effect.asSharedFlow()

    fun onEvent(event: E) {
        handleEvent(event)
    }

    protected abstract fun handleEvent(event: E)

    protected fun setState(reducer: BaseState<S>.() -> BaseState<S>) {
        _state.update { state -> state.reducer() }
    }

    protected fun updateData(transform: S.() -> S) {
        _state.update { it.copy(data = it.data.transform()) }
    }

    protected fun sendEffect(builder: () -> F) {
        _effect.tryEmit(builder())
    }
}
