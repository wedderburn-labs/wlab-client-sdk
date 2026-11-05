package org.traccar.client

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.module.Module
import org.koin.dsl.module

internal fun coreModule(): Module = module {
    single { ConfigStore(get()) }
    single<PositionQueue> { DatabaseQueue(get()) }
    single<Flow<Position>> { get<LocationSource>().positions }
    single<StateFlow<State>> { get<StateStore>().state }
    single { ComponentCoroutineScope() }

    single<Uploader> { HttpUploader(get(), get()) }
    single<BatchUploader> { HttpBatchUploader(get(), get()) }
    single { LocationFilter(get(), get()) }

    single {
        TrackerEngine(
            stateStore = get(),
            queue = get(),
            network = get(),
            locationSource = get(),
            signalSources = get(),
            processors = listOf(get<LocationFilter>(), get()),
            uploader = get(),
            buffer = get<Config>().buffer,
            scope = get(),
            deviceId = get<Config>().deviceId,
            batchUploader = get<BatchUploader>().takeIf { get<Config>().batch.enabled },
            batch = get<Config>().batch,
        )
    }

    single {
        Tracker(
            config = get(),
            stateStore = get(),
            locationSource = get(),
            batteryProcessor = get(),
            uploader = get(),
            queue = get(),
            engine = get(),
            componentScope = get(),
        )
    }
}

internal expect fun platformModule(): Module
