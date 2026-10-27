package io.screenstream.mjpeg.screenoff

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import io.screenstream.streaming.logE
import kotlin.uuid.Uuid

/** Owns a conditional Android screen-off subscription and its synchronous cleanup. */
internal class AndroidScreenOffObserver(private val context: Context) {
    private val gate = Any()
    private var admissionClosed = false
    private var registration: Registration? = null
    private var cleanupFailure: Exception? = null

    enum class RegistrationResult { Registered, ScreenOff, Rejected }

    /** Records the receiver before Android registration; [close] uses the same gate. */
    @SuppressLint("UnspecifiedRegisterReceiverFlag") // ACTION_SCREEN_OFF is exclusively a protected system broadcast.
    fun register(registrationId: Uuid, onScreenOff: () -> Unit): RegistrationResult = synchronized(gate) {
        cleanupFailure?.let { throw it }
        if (admissionClosed) return@synchronized RegistrationResult.Rejected
        check(registration == null) { "Screen-off receiver is already registered" }
        val powerManager = checkNotNull(context.getSystemService(PowerManager::class.java))
        if (!powerManager.isInteractive) return@synchronized RegistrationResult.ScreenOff

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) onScreenOff()
            }
        }
        val current = Registration(registrationId, receiver)
        registration = current
        try {
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
            current.registered = true
            if (powerManager.isInteractive) {
                RegistrationResult.Registered
            } else {
                unregisterLocked(registrationId)
                RegistrationResult.ScreenOff
            }
        } catch (failure: Exception) {
            try {
                unregisterLocked(registrationId)
            } catch (cleanupFailure: Exception) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    fun unregister(registrationId: Uuid) {
        synchronized(gate) { unregisterLocked(registrationId) }
    }

    /** Permanently closes registration and performs receiver cleanup synchronously. */
    fun close(): Boolean = synchronized(gate) {
        admissionClosed = true
        registration?.let { current ->
            try {
                unregisterLocked(current.id)
            } catch (failure: Exception) {
                logE("Cleanup.screenOff", "Screen-off receiver cleanup failed", failure)
            }
        }
        cleanupFailure == null
    }

    private fun unregisterLocked(registrationId: Uuid) {
        val current = registration?.takeIf { it.id == registrationId } ?: return
        registration = null
        try {
            context.unregisterReceiver(current.receiver)
        } catch (failure: Exception) {
            // Registration can throw before Android installs even its local receiver dispatcher.
            if (!current.registered && failure is IllegalArgumentException) return
            cleanupFailure = cleanupFailure ?: failure
            admissionClosed = true
            throw failure
        }
    }

    private class Registration(val id: Uuid, val receiver: BroadcastReceiver, var registered: Boolean = false)
}
