package io.github.madooroy.pinionkaroo.extension

import android.content.Context
import android.content.res.Configuration
import android.widget.RemoteViews
import io.github.madooroy.pinionkaroo.R
import io.github.madooroy.pinionkaroo.RideGearStats
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale

/**
 * "Shifts + Avg Gear": the ride's shift count on the left and its average gear (one decimal) on the right, drawn
 * like the other split fields by [SplitFieldRenderer]. Both come from [RideGearStats], kept by the service; both
 * show a dash until a ride has started, and keep the last ride's values after it ends until the next one starts.
 * Redrawn at most once a second, and only when a shown number changes.
 */
class RideGearDataType(
    extension: String,
    private val stats: Flow<RideGearStats.Summary?>,
    private val onViewStarted: () -> Unit = {},
    private val onViewStopped: () -> Unit = {},
) : DataTypeImpl(extension, TYPE_ID) {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Timber.d("Start $typeId view, preview=${config.preview}")
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        onViewStarted()

        val shown: Flow<Pair<String?, String?>> =
            (if (config.preview) flowOf(SAMPLE) else stats).map { summary ->
                summary?.shifts?.toString() to summary?.averageGear?.let { String.format(Locale.US, "%.1f", it) }
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            shown.distinctUntilChanged().conflate().collect { (shifts, average) ->
                emitter.updateView(draw(context, config, shifts, average))
                delay(UPDATE_INTERVAL_MS) // Karoo drops view updates less than about 0.9 s apart
            }
        }
        emitter.setCancellable {
            Timber.d("Stop $typeId view")
            scope.cancel()
            onViewStopped()
        }
    }

    private fun draw(context: Context, config: ViewConfig, shifts: String?, average: String?): RemoteViews {
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val bitmap = SplitFieldRenderer.render(
            widthPx = config.viewSize.first,
            heightPx = config.viewSize.second,
            density = context.resources.displayMetrics.density,
            textSizeSp = config.textSize,
            night = night,
            alignment = config.alignment,
            left = SplitFieldRenderer.Half(context.getString(R.string.split_shifts_label), shifts),
            right = SplitFieldRenderer.Half(context.getString(R.string.split_avg_gear_label), average),
        )
        return RemoteViews(context.packageName, R.layout.split_field).apply {
            setImageViewBitmap(R.id.split_image, bitmap)
        }
    }

    companion object {
        /** Must match a `typeId` in res/xml/extension_info.xml */
        const val TYPE_ID = "shifts_avg_gear"
        private const val UPDATE_INTERVAL_MS = 1_000L
        private val SAMPLE = RideGearStats.Summary(shifts = 143, averageGear = 6.4)
    }
}
