package io.github.madooroy.pinionkaroo.extension

import android.content.Context
import android.content.res.Configuration
import android.widget.RemoteViews
import io.github.madooroy.pinionkaroo.PinionBleClient
import io.github.madooroy.pinionkaroo.PinionLink
import io.github.madooroy.pinionkaroo.R
import io.github.madooroy.pinionkaroo.ZoneColors
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.UserProfile
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import kotlin.math.roundToInt

/** The Karoo value shown beside the gear in a split field. The gear is always the right half. */
enum class SplitPartner(
    /** Must match a `typeId` in res/xml/extension_info.xml */
    val typeId: String,
    /** The Karoo stream for the left half, or null for one of our own values (the gearbox battery) */
    val karooType: String?,
    val label: Int,
    /** Zone colours for this value, or null for none */
    val zonePalette: IntArray?,
    /** Shown in the profile editor, in the Karoo's own units (m/s for speed) */
    val sample: Double,
) {
    CADENCE("cadence_gear", DataType.Type.SMOOTHED_3S_AVERAGE_CADENCE, R.string.split_cadence_label, null, 85.0),
    POWER("power_gear", DataType.Type.SMOOTHED_3S_AVERAGE_POWER, R.string.split_power_label, ZoneColors.POWER, 187.0),
    HEART_RATE("hr_gear", DataType.Type.HEART_RATE, R.string.split_hr_label, ZoneColors.HEART_RATE, 132.0),
    SPEED("speed_gear", DataType.Type.SMOOTHED_3S_AVERAGE_SPEED, R.string.split_speed_label, null, 9.33),
    BATTERY("battery_gear", null, R.string.split_battery_label, null, 88.0),
}

/**
 * A field split in two: a value on the left ([partner]: 3s cadence, 3s power, heart rate, 3s speed or the
 * gearbox battery) and
 * the Pinion gear on the right, so a 2x2 map page can carry five values. A graphical field: the whole cell is
 * drawn by [SplitFieldRenderer] (showHeader = false). Power and heart rate get the rider's zone colour as the
 * fill of their half, like Karoo's own fields in zone colour mode.
 */
class SplitGearDataType(
    private val client: PinionBleClient,
    private val karooSystem: KarooSystemService,
    extension: String,
    private val partner: SplitPartner,
    /** Told when the ride screen shows this field and when it stops, like the numeric fields' streams. */
    private val onViewStarted: () -> Unit = {},
    private val onViewStopped: () -> Unit = {},
) : DataTypeImpl(extension, partner.typeId) {

    private data class Frame(val partnerValue: Double?, val gear: Int?, val profile: UserProfile?)

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Timber.d("Start $typeId view: ${config.viewSize.first}x${config.viewSize.second} px, text ${config.textSize} sp, preview=${config.preview}")
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        onViewStarted()

        // In the profile editor show sample values, as the Karoo does with its 42
        val karooType = partner.karooType
        val partnerValue: Flow<Double?> = when {
            config.preview -> flowOf(partner.sample)
            karooType != null ->
                karooSystem.streamDataFlow(karooType)
                    .map { state ->
                        when (state) {
                            is StreamState.Streaming -> state.dataPoint.singleValue
                            // Sensor connected but silent (stopped, auto-paused): Karoo's own field shows 0 then
                            // (3s power, 2026-10-25), so do the same, except for heart rate, where 0 means nothing
                            StreamState.Idle -> if (partner == SplitPartner.HEART_RATE) null else 0.0
                            else -> null
                        }
                    }
                    .onStart { emit(null) }
            // The gearbox battery, shown like the gear: only while connected
            else -> client.state.map { state -> if (state.link == PinionLink.CONNECTED) state.batteryPercent else null }
        }
        val gear: Flow<Int?> = if (config.preview) {
            flowOf(SAMPLE_GEAR)
        } else {
            client.state.map { state ->
                if (state.link == PinionLink.CONNECTED) state.gear?.takeIf { it in 1..state.gearCount } else null
            }
        }
        // Units and zones; the field draws without them until the profile arrives
        val profile: Flow<UserProfile?> = karooSystem.consumerFlow<UserProfile>()
            .map<UserProfile, UserProfile?> { it }
            .onStart { emit(null) }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            combine(partnerValue, gear, profile) { v, g, p -> Frame(v, g, p) }
                .distinctUntilChanged()
                .conflate()
                .collect { frame ->
                    emitter.updateView(draw(context, config, frame))
                    // Karoo drops view updates that come less than about 0.9 s apart: wait, then draw whatever
                    // is newest (conflate keeps only the latest)
                    delay(UPDATE_INTERVAL_MS)
                }
        }
        emitter.setCancellable {
            Timber.d("Stop $typeId view")
            scope.cancel()
            onViewStopped()
        }
    }

    private fun draw(context: Context, config: ViewConfig, frame: Frame): RemoteViews {
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val value = frame.partnerValue
        val text = value?.let { format(it, frame.profile) }
        val fill = if (value != null && partner.zonePalette != null && frame.profile != null) {
            val zones = when (partner) {
                SplitPartner.POWER -> frame.profile.powerZones
                else -> frame.profile.heartRateZones
            }.map { it.min to it.max }
            ZoneColors.fill(ZoneColors.zoneOf(value, zones), partner.zonePalette)
        } else {
            null
        }
        val bitmap = SplitFieldRenderer.render(
            widthPx = config.viewSize.first,
            heightPx = config.viewSize.second,
            density = context.resources.displayMetrics.density,
            textSizeSp = config.textSize,
            night = night,
            alignment = config.alignment,
            left = SplitFieldRenderer.Half(context.getString(partner.label), text, fill),
            right = SplitFieldRenderer.Half(context.getString(R.string.split_gear_label), frame.gear?.toString()),
        )
        return RemoteViews(context.packageName, R.layout.split_field).apply {
            setImageViewBitmap(R.id.split_image, bitmap)
        }
    }

    private fun format(value: Double, profile: UserProfile?): String = when (partner) {
        SplitPartner.SPEED -> {
            // Karoo streams speed in m/s
            val imperial = profile?.preferredUnit?.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL
            String.format(Locale.US, "%.1f", value * if (imperial) MPS_TO_MPH else MPS_TO_KMH)
        }
        // Cut off rather than rounded, like Karoo's own battery field (86.54 shows as 86)
        SplitPartner.BATTERY -> value.toInt().toString()
        else -> value.roundToInt().toString()
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 1_000L
        private const val SAMPLE_GEAR = 7
        private const val MPS_TO_KMH = 3.6
        private const val MPS_TO_MPH = 2.2369363
    }
}
