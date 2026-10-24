package io.github.madooroy.pinionkaroo.extension

import io.github.madooroy.pinionkaroo.PinionBleClient
import io.github.madooroy.pinionkaroo.LowBatteryWarning
import io.github.madooroy.pinionkaroo.PinionLink
import io.github.madooroy.pinionkaroo.R
import io.github.madooroy.pinionkaroo.ShiftCounter
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    private var holdsClient = false

    /**
     * Pinion field streams Karoo is running. Karoo starts them when the ride screen opens, and only shows an
     * InRideAlert while the ride screen is open: one sent before that (seen 2026-10-20: the gearbox connected
     * on the home screen, 12 s before the ride screen) is lost.
     */
    private val activeStreams = MutableStateFlow(0)

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
        }
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

    /**
     * Writes the gear into the ride's FIT file (Karoo calls this for each recorded ride; extension_info.xml has
     * fitFile="true"): developer field "Pinion Gear" in every record (1 Hz) while the gearbox is connected, so
     * intervals.icu, GoldenCheetah and the like can chart it, and "Pinion Shifts", the number of gear changes, in
     * the session. Nothing is written while the gearbox is not connected, so the file shows a gap there.
     * Elapsed time is only the 1 s tick, as in the karoo-ext sample.
     */
    override fun startFit(emitter: Emitter<FitEffect>) {
        Timber.i("Ride file: recording the gear")
        val counter = ShiftCounter()
        var shiftsWritten = -1
        val gear = client.state.map { state ->
            if (state.link == PinionLink.CONNECTED) state.gear?.takeIf { it in 1..state.gearCount } else null
        }
        val job = scope.launch {
            combine(
                karooSystem.streamDataFlow(DataType.Type.ELAPSED_TIME),
                gear,
                karooSystem.consumerFlow<RideState>(),
            ) { _, gearNow, ride -> gearNow to ride }.collect { (gearNow, ride) ->
                if (ride is RideState.Recording) {
                    counter.update(gearNow)
                    if (gearNow != null) emitter.onNext(WriteToRecordMesg(FieldValue(FIT_GEAR, gearNow.toDouble())))
                }
                // The session value is kept as last written, so write it when it changes
                if (ride !is RideState.Idle && counter.shifts != shiftsWritten) {
                    emitter.onNext(WriteToSessionMesg(FieldValue(FIT_SHIFTS, counter.shifts.toDouble())))
                    shiftsWritten = counter.shifts
                }
            }
        }
        emitter.setCancellable {
            Timber.i("Ride file: done, ${counter.shifts} shifts")
            job.cancel()
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
    }
}
