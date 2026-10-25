package io.github.madooroy.pinionkaroo.extension

import io.github.madooroy.pinionkaroo.PinionBleClient
import io.github.madooroy.pinionkaroo.LowBatteryWarning
import io.github.madooroy.pinionkaroo.PinionLink
import io.github.madooroy.pinionkaroo.R
import io.github.madooroy.pinionkaroo.RideGearStats
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.ActiveRideProfile
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.DeveloperField
import io.hammerhead.karooext.models.FieldValue
import io.hammerhead.karooext.models.FitEffect
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import io.hammerhead.karooext.models.RideProfile
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.WriteToRecordMesg
import io.hammerhead.karooext.models.WriteToSessionMesg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.plus
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Entry point bound by the Karoo system (see AndroidManifest.xml and res/xml/extension_info.xml).
 * Karoo keeps this service alive while the extension is installed. The gearbox connection is not
 * tied to it: the BLE client only runs while the active ride profile has a Pinion field on one of
 * its pages, so riding another bike with another profile does not scan.
 * (Field streams cannot be used for this: Karoo keeps them running whatever profile is open.)
 */
class PinionExtensionService : KarooExtension(EXTENSION_ID, "0.1.4") {
    private lateinit var karooSystem: KarooSystemService
    private lateinit var client: PinionBleClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One thread for [trackRide] */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val rideTracking = Dispatchers.IO.limitedParallelism(1)
    private var holdsClient = false

    /**
     * Pinion field streams Karoo is running. Karoo starts them when the ride screen opens, and only shows an
     * InRideAlert while the ride screen is open: one sent before that (seen 2026-10-20: the gearbox connected
     * on the home screen, 12 s before the ride screen) is lost.
     */
    private val activeStreams = MutableStateFlow(0)

    /** Shifts and average gear of the current (or last) ride, see [trackRide]; null before the first ride */
    private val rideStats = MutableStateFlow<RideGearStats.Summary?>(null)

    override val types by lazy {
        listOf<DataTypeImpl>(
            // Limits: only a gear within 1..gearCount (at most 12) is ever shown
            PinionDataType(
                client, extension, PinionDataType.GEAR,
                value = { state -> state.gear?.takeIf { it in 1..state.gearCount }?.toDouble() },
                onStreamStarted = { activeStreams.update { it + 1 } },
                onStreamStopped = { activeStreams.update { it - 1 } },
            ),
            PinionDataType(
                client, extension, PinionDataType.BATTERY,
                value = { state -> state.batteryPercent },
                onStreamStarted = { activeStreams.update { it + 1 } },
                onStreamStopped = { activeStreams.update { it - 1 } },
            ),
        ) + SplitPartner.values().map { partner ->
            // Drawn fields: Karoo starts their views, not streams, so count views too (the low-battery alert
            // waits for the ride screen)
            SplitGearDataType(
                client, karooSystem, extension, partner,
                onViewStarted = { activeStreams.update { it + 1 } },
                onViewStopped = { activeStreams.update { it - 1 } },
            )
        } + RideGearDataType(
            extension, rideStats,
            onViewStarted = { activeStreams.update { it + 1 } },
            onViewStopped = { activeStreams.update { it - 1 } },
        )
    }

    override fun onCreate() {
        super.onCreate()
        Timber.i("Extension service created")
        client = PinionBleClient.get(this)
        karooSystem = KarooSystemService(this)
        karooSystem.connect { connected ->
            Timber.i("Karoo system connected=$connected")
            if (connected) {
                // Ask Karoo to keep the Bluetooth radio on for us
                karooSystem.dispatch(RequestBluetooth(extension))
            }
        }
        karooSystem.addConsumer<ActiveRideProfile> { event -> onActiveProfile(event.profile) }
        scope.launch { alertOnDisconnect() }
        scope.launch { alertOnLowBattery() }
        scope.launch { keepBluetoothOn() }
        scope.launch(rideTracking) { trackRide() }
    }

    /**
     * Karoo has no Bluetooth switch for the rider: it turns the radio on and off itself, and was seen
     * to turn it off under a running extension. Ask again for as long as the client finds it off.
     */
    private suspend fun keepBluetoothOn() {
        client.state.map { it.link == PinionLink.BLUETOOTH_OFF }.distinctUntilChanged().collectLatest { off ->
            while (off) {
                Timber.i("Bluetooth is off, asking Karoo to turn it on")
                karooSystem.dispatch(RequestBluetooth(extension))
                delay(BLUETOOTH_REQUEST_INTERVAL_MS)
            }
        }
    }

    @Synchronized
    private fun onActiveProfile(profile: RideProfile) {
        val prefix = DataType.dataTypeId(extension, "")
        val wanted = profile.pages.any { page -> page.elements.any { it.dataTypeId.startsWith(prefix) } }
        Timber.i("Active profile \"${profile.name}\" has Pinion fields: $wanted")
        if (wanted && !holdsClient) client.start()
        if (!wanted && holdsClient) client.stop()
        holdsClient = wanted
    }

    override fun onDestroy() {
        Timber.i("Extension service destroyed")
        scope.cancel()
        synchronized(this) {
            if (holdsClient) client.stop()
            holdsClient = false
        }
        karooSystem.dispatch(ReleaseBluetooth(extension))
        karooSystem.disconnect()
        super.onDestroy()
    }

    /**
     * The gearbox stops advertising when the link drops, so it cannot come back by itself:
     * tell the rider what to do.
     */
    private suspend fun alertOnDisconnect() {
        var wasConnected = false
        client.state.map { it.link }.distinctUntilChanged().collect { link ->
            if (wasConnected && link != PinionLink.CONNECTED && link != PinionLink.IDLE) {
                karooSystem.dispatch(
                    InRideAlert(
                        id = "pinion-disconnected",
                        icon = R.drawable.ic_pinion,
                        title = getString(R.string.alert_disconnected_title),
                        detail = getString(R.string.alert_disconnected_detail),
                        // Long enough not to be missed at a glance; not sticky, as there is no way to take an
                        // alert down again when the gearbox comes back, and it covers the top of the ride page
                        autoDismissMs = DISCONNECT_ALERT_MS,
                        backgroundColor = R.color.alert_background,
                        textColor = R.color.alert_text,
                    ),
                )
            }
            wasConnected = link == PinionLink.CONNECTED
        }
    }

    /** The gear while connected, else null */
    private fun gear(): Flow<Int?> = client.state.map { state ->
        if (state.link == PinionLink.CONNECTED) state.gear?.takeIf { it in 1..state.gearCount } else null
    }

    /**
     * Keeps [rideStats] for the "Shifts + Avg Gear" field and the ride file: a fresh [RideGearStats] each time a
     * ride starts (Idle to recording or paused), every gear change counted while the ride is not idle, and the
     * gear sampled once a second while recording. Runs on one thread ([rideTracking]), so the stats need no lock.
     * If the extension is restarted during a ride, the counts start again from there.
     */
    private suspend fun trackRide() {
        val ride: StateFlow<RideState> = karooSystem.consumerFlow<RideState>().stateIn(scope + rideTracking)
        val gear: StateFlow<Int?> = gear().stateIn(scope + rideTracking)
        var stats: RideGearStats? = null
        scope.launch(rideTracking) {
            ride.collect { state ->
                if (state !is RideState.Idle && stats == null) {
                    Timber.i("Ride started: counting shifts")
                    stats = RideGearStats().also { it.onGear(gear.value) }
                    rideStats.value = stats?.summary
                } else if (state is RideState.Idle && stats != null) {
                    Timber.i("Ride ended: ${stats?.summary}")
                    stats = null // keep showing the last summary until the next ride
                }
            }
        }
        scope.launch(rideTracking) {
            gear.collect { g ->
                stats?.let { it.onGear(g); rideStats.value = it.summary }
            }
        }
        while (true) {
            delay(1_000)
            if (ride.value is RideState.Recording) stats?.let { it.onSecond(gear.value); rideStats.value = it.summary }
        }
    }

    /**
     * Writes the gear into the ride's FIT file (Karoo calls this for each recorded ride; extension_info.xml has
     * fitFile="true"): developer field "Pinion Gear" in every record (1 Hz) while the gearbox is connected, so
     * intervals.icu, GoldenCheetah and the like can chart it, and the session totals "Pinion Shifts" and
     * "Pinion Avg Gear" from [rideStats]. Nothing is written to a record while the gearbox is not connected, so the
     * file shows a gap there. Elapsed time is only the 1 s tick, as in the karoo-ext sample.
     */
    override fun startFit(emitter: Emitter<FitEffect>) {
        Timber.i("Ride file: recording the gear")
        val records = scope.launch {
            combine(
                karooSystem.streamDataFlow(DataType.Type.ELAPSED_TIME),
                gear(),
                karooSystem.consumerFlow<RideState>(),
            ) { _, gearNow, ride -> gearNow to ride }.collect { (gearNow, ride) ->
                if (ride is RideState.Recording && gearNow != null) {
                    emitter.onNext(WriteToRecordMesg(FieldValue(FIT_GEAR, gearNow.toDouble())))
                }
            }
        }
        // Session values are kept as last written, so write them when they change
        val session = scope.launch {
            rideStats.mapNotNull { summary ->
                summary?.let { it.shifts to it.averageGear?.let { avg -> Math.round(avg * 10) / 10.0 } }
            }.distinctUntilChanged().collect { (shifts, average) ->
                val values = listOfNotNull(
                    FieldValue(FIT_SHIFTS, shifts.toDouble()),
                    average?.let { FieldValue(FIT_AVG_GEAR, it) },
                )
                emitter.onNext(WriteToSessionMesg(values))
            }
        }
        emitter.setCancellable {
            Timber.i("Ride file: done, ${rideStats.value}")
            records.cancel()
            session.cancel()
        }
    }

    /**
     * The battery barely moves during a ride, so it need not take a field: [LowBatteryWarning] decides when to
     * warn (once per connection at 20 % or less, once more at 10 % and at 5 % or less). A connection normally
     * lasts the ride, so in effect this is a warning at the start and more as it gets critical. Works whether or
     * not the battery field is on a page. Waits for the ride screen, see [activeStreams].
     */
    private suspend fun alertOnLowBattery() {
        val warning = LowBatteryWarning()
        val rideScreenOpen = activeStreams.map { it > 0 }.distinctUntilChanged()
        combine(client.state, rideScreenOpen) { state, open -> state to open }.collect { (state, open) ->
            // Hold a warning back until the ride screen is open, or Karoo drops it. A disconnect still goes
            // through, so that the next connection starts afresh.
            if (state.link == PinionLink.CONNECTED && !open) return@collect
            val level = warning.update(state) ?: return@collect
            val percent = state.batteryPercent ?: return@collect
            Timber.i("Battery ${"%.2f".format(percent)} %, low battery alert ($level)")
            karooSystem.dispatch(
                InRideAlert(
                    id = "pinion-battery-low",
                    icon = R.drawable.ic_pinion,
                    title = getString(
                        when (level) {
                            LowBatteryWarning.Level.LOW -> R.string.alert_battery_low_title
                            LowBatteryWarning.Level.VERY_LOW -> R.string.alert_battery_very_low_title
                            LowBatteryWarning.Level.CRITICAL -> R.string.alert_battery_critical_title
                        },
                    ),
                    detail = getString(
                        if (level == LowBatteryWarning.Level.CRITICAL) R.string.alert_battery_critical_detail
                        else R.string.alert_battery_low_detail,
                        // Cut off like Karoo's own battery field (86.54 shows as 86 there), not rounded
                        percent.toInt(),
                    ),
                    autoDismissMs = 10_000,
                    backgroundColor = R.color.alert_background,
                    textColor = R.color.alert_text,
                ),
            )
        }
    }

    companion object {
        /** Must match `id` in res/xml/extension_info.xml */
        const val EXTENSION_ID = "pinion"
        private const val BLUETOOTH_REQUEST_INTERVAL_MS = 15_000L
        private const val DISCONNECT_ALERT_MS = 30_000L

        // FIT developer fields: numbered from 0 per extension; base types from the FIT SDK (2 = uint8, 132 = uint16)
        private val FIT_GEAR = DeveloperField(
            fieldDefinitionNumber = 0, fitBaseTypeId = 2, fieldName = "Pinion Gear", units = "gear",
        )
        private val FIT_SHIFTS = DeveloperField(
            fieldDefinitionNumber = 1, fitBaseTypeId = 132, fieldName = "Pinion Shifts", units = "shifts",
        )
        private val FIT_AVG_GEAR = DeveloperField(
            fieldDefinitionNumber = 2, fitBaseTypeId = 136, fieldName = "Pinion Avg Gear", units = "gear", // float32
        )
    }
}
