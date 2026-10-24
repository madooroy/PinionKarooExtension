package io.github.madooroy.pinionkaroo.extension

import android.content.Context
import android.content.res.Configuration
import android.widget.RemoteViews
import io.github.madooroy.pinionkaroo.PinionBleClient
import io.github.madooroy.pinionkaroo.PinionLink
import io.github.madooroy.pinionkaroo.R
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * "3s Cad + Gear": one field split in two, the Karoo's 3 second cadence on the left and the Pinion gear on the
 * right, so a 2x2 map page can carry speed, power, heart rate, cadence and gear. A graphical field: the header
 * is drawn by [SplitFieldRenderer] (showHeader = false), not by the Karoo.
 */
class CadenceGearDataType(
    private val client: PinionBleClient,
    private val karooSystem: KarooSystemService,
    extension: String,
    /** Told when the ride screen shows this field and when it stops, like the numeric fields' streams. */
    private val onViewStarted: () -> Unit = {},
    private val onViewStopped: () -> Unit = {},
) : DataTypeImpl(extension, TYPE_ID) {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Timber.d("Start $typeId view: ${config.viewSize.first}x${config.viewSize.second} px, text ${config.textSize} sp, preview=${config.preview}")
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        onViewStarted()

        // In the profile editor show sample values, as the Karoo does with its 42
        val cadence = if (config.preview) {
            flowOf(SAMPLE_CADENCE)
        } else {
            karooSystem.streamDataFlow(DataType.Type.SMOOTHED_3S_AVERAGE_CADENCE)
                .map { state -> (state as? StreamState.Streaming)?.dataPoint?.singleValue?.roundToInt() }
                .onStart { emit(null) }
        }
        val gear = if (config.preview) {
            flowOf(SAMPLE_GEAR)
        } else {
            client.state.map { state ->
                if (state.link == PinionLink.CONNECTED) state.gear?.takeIf { it in 1..state.gearCount } else null
            }
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            combine(cadence, gear) { c, g -> c to g }
                .distinctUntilChanged()
                .conflate()
                .collect { (c, g) ->
                    val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                        Configuration.UI_MODE_NIGHT_YES
                    val bitmap = SplitFieldRenderer.render(
                        widthPx = config.viewSize.first,
                        heightPx = config.viewSize.second,
                        density = context.resources.displayMetrics.density,
                        textSizeSp = config.textSize,
                        night = night,
                        alignment = config.alignment,
                        left = SplitFieldRenderer.Half(context.getString(R.string.split_cadence_label), c?.toString()),
                        right = SplitFieldRenderer.Half(context.getString(R.string.split_gear_label), g?.toString()),
                    )
                    val views = RemoteViews(context.packageName, R.layout.split_field)
                    views.setImageViewBitmap(R.id.split_image, bitmap)
                    emitter.updateView(views)
                    // Karoo drops view updates that come less than about 0.9 s apart: wait, then draw
                    // whatever is newest (conflate keeps only the latest)
                    delay(UPDATE_INTERVAL_MS)
                }
        }
        emitter.setCancellable {
            Timber.d("Stop $typeId view")
            scope.cancel()
            onViewStopped()
        }
    }

    companion object {
        /** Must match the `typeId` in res/xml/extension_info.xml */
        const val TYPE_ID = "cadence_gear"
        private const val UPDATE_INTERVAL_MS = 1_000L
        private const val SAMPLE_CADENCE = 85
        private const val SAMPLE_GEAR = 7
    }
}
