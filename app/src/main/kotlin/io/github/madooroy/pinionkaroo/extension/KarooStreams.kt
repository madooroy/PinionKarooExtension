package io.github.madooroy.pinionkaroo.extension

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A Karoo data stream (speed, cadence, ...) as a Flow. From the karoo-ext sample app (Apache 2.0). */
fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> =
    callbackFlow {
        val listenerId = addConsumer(OnStreamState.StartStreaming(dataTypeId)) { event: OnStreamState ->
            trySendBlocking(event.state)
        }
        awaitClose { removeConsumer(listenerId) }
    }
