package io.github.madooroy.pinionkaroo.extension

import io.github.madooroy.pinionkaroo.PinionBleClient
import io.github.madooroy.pinionkaroo.LowBatteryWarning
import io.github.madooroy.pinionkaroo.PinionLink
import io.github.madooroy.pinionkaroo.R
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.ActiveRideProfile
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import io.hammerhead.karooext.models.RideProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * Entry point bound by the Karoo system (see AndroidManifest.xml and res/xml/extension_info.xml).
 * Karoo keeps this service alive while the extension is installed. The gearbox connection is not
 * tied to it: the BLE client only runs while the active ride profile has a Pinion field on one of
 * its pages, so riding another bike with another profile does not scan.
 * (Field streams cannot be used for this: Karoo keeps them running whatever profile is open.)
 */
class PinionExtensionService : KarooExtension(EXTENSION_ID, "0.1.2") {
    private lateinit var karooSystem: KarooSystemService
    private lateinit var client: PinionBleClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var holdsClient = false

    override val types by lazy {
        listOf(
            // Limits: only a gear within 1..gearCount (at most 12) is ever shown
            PinionDataType(client, extension, PinionDataType.GEAR) { state ->
                state.gear?.takeIf { it in 1..state.gearCount }?.toDouble()
            },
            PinionDataType(client, extension, PinionDataType.BATTERY) { state -> state.batteryPercent },
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
                        autoDismissMs = 10_000,
                        backgroundColor = R.color.alert_background,
                        textColor = R.color.alert_text,
                    ),
                )
            }
            wasConnected = link == PinionLink.CONNECTED
        }
    }

    /**
     * The battery barely moves during a ride, so it need not take a field: [LowBatteryWarning] decides when to
     * warn (once per connection at 20 % or less, once more at 10 % or less). A connection normally lasts the
     * ride, so in effect this is a warning at the start and one when it gets critical. Works whether or not
     * the battery field is on a page.
     */
    private suspend fun alertOnLowBattery() {
        val warning = LowBatteryWarning()
        client.state.collect { state ->
            val level = warning.update(state) ?: return@collect
            val percent = state.batteryPercent ?: return@collect
            Timber.i("Battery ${"%.2f".format(percent)} %, low battery alert ($level)")
            karooSystem.dispatch(
                InRideAlert(
                    id = "pinion-battery-low",
                    icon = R.drawable.ic_pinion,
                    title = getString(
                        if (level == LowBatteryWarning.Level.VERY_LOW) R.string.alert_battery_very_low_title
                        else R.string.alert_battery_low_title,
                    ),
                    detail = getString(R.string.alert_battery_low_detail, percent.roundToInt()),
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
    }
}
