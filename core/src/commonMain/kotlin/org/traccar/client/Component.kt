package org.traccar.client

import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal val LOCATION_FETCH_TIMEOUT: Duration = 30.seconds

interface LocationSource {
    val positions: Flow<Position>
    suspend fun fetchOnce(): Position?
}

internal enum class LocationMode { Off, Active, Stationary }

internal fun State.locationMode(): LocationMode = when {
    !enabled -> LocationMode.Off
    paused -> LocationMode.Stationary
    else -> LocationMode.Active
}

interface SignalSource {
    val signals: Flow<Signal>
}

fun interface PositionProcessor {
    suspend fun process(position: Position): Position?
}

class ComponentCoroutineScope(
    context: CoroutineContext = SupervisorJob() + Dispatchers.Default,
) : CoroutineScope by CoroutineScope(context)

internal fun <T> CoroutineScope.observeState(
    state: StateFlow<State>,
    select: (State) -> T,
    inactive: T,
    block: suspend (T) -> Unit,
): Job = launch {
    try {
        state.map(select).distinctUntilChanged().collectLatest(block)
    } finally {
        block(inactive)
    }
}
