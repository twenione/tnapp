package com.trailnav.app

/** Events that must be surfaced to the user as spoken GPS diagnostics. */
sealed class GpsSignalEvent {
    data object NoFixTimeout : GpsSignalEvent()
    data object ProviderError : GpsSignalEvent()
    data class WeakSignal(val accuracyMeters: Float) : GpsSignalEvent()
}

/**
 * Lifecycle-safe GPS health tracker. It is kept independent of Android so the
 * no-fix, provider-error, and invalid-accuracy paths can be regression tested
 * without relying on a physical device.
 */
class GpsSignalMonitor(
    private val weakAccuracyMeters: Float = 50.0f,
) {
    private var active = false
    private var receivedLocation = false
    private var noFixEventIssued = false
    private var providerErrorIssued = false
    private var weakSignalIssued = false

    fun start() {
        active = true
        receivedLocation = false
        noFixEventIssued = false
        providerErrorIssued = false
        weakSignalIssued = false
    }

    fun stop() {
        active = false
    }

    fun onLocation(location: TrailLocation): GpsSignalEvent? {
        if (!active) return null
        receivedLocation = true
        val accuracy = location.accuracyMeters
        if (!weakSignalIssued && (accuracy.isNaN() || accuracy.isInfinite() || accuracy < 0.0f || accuracy > weakAccuracyMeters)) {
            weakSignalIssued = true
            return GpsSignalEvent.WeakSignal(accuracy)
        }
        return null
    }

    fun onNoFixTimeout(): GpsSignalEvent? {
        if (!active || receivedLocation || noFixEventIssued) return null
        noFixEventIssued = true
        return GpsSignalEvent.NoFixTimeout
    }

    fun onProviderError(): GpsSignalEvent? {
        if (!active || providerErrorIssued || noFixEventIssued) return null
        providerErrorIssued = true
        // A provider failure is the stronger and immediate form of the
        // no-fix warning. Mark the no-fix slot so the delayed watchdog does
        // not emit a second prompt for the same outage.
        noFixEventIssued = true
        return GpsSignalEvent.ProviderError
    }
}
