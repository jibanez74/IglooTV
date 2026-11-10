package com.igloo.blindpenguincoder.feature.shared

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.feature.auth.toLibraryDisplayMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The single-read detail overlays' lifecycle, adapted to each screen's existing state. */
internal class ReadOnlyDetailsLoader<State, Response, Ui>(
    private val scope: CoroutineScope,
    private val createState: (Long?) -> State,
    private val openId: (State) -> Long?,
    private val details: (State) -> DetailsState<Ui>,
    private val withDetails: (State, DetailsState<Ui>) -> State,
    private val fetch: suspend (Long) -> ApiResult<Response>,
    private val toUi: (Response) -> Ui,
) {
    private val state = MutableStateFlow(createState(null))
    val uiState: StateFlow<State> = state.asStateFlow()
    private var loadJob: Job? = null

    fun open(id: Long) {
        loadJob?.cancel()
        state.value = createState(id)
        load(id, userInitiated = true)
    }

    fun close() {
        loadJob?.cancel()
        loadJob = null
        state.value = createState(null)
    }

    /** A fresh open: the states hold nothing but the id and the details. */
    fun retry() {
        open(openId(state.value) ?: return)
    }

    fun refresh() {
        val id = openId(state.value) ?: return
        load(id, userInitiated = false)
    }

    private fun load(id: Long, userInitiated: Boolean) {
        loadJob = scope.launch {
            val result = fetch(id)
            if (openId(state.value) != id) return@launch
            state.update {
                val nextDetails = when (result) {
                    is ApiResult.Success -> DetailsState.Loaded(toUi(result.value))
                    is ApiResult.Failure -> details(it).errorOrKeep(
                        result.error.toLibraryDisplayMessage(),
                        userInitiated,
                    )
                }
                withDetails(it, nextDetails)
            }
        }
    }
}
