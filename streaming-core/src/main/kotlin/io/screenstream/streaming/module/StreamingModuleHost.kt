package io.screenstream.streaming.module

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.StringRes
import info.dvkr.screenstream.common.module.StreamingModule
import io.screenstream.streaming.foreground.ForegroundControl
import io.screenstream.streaming.foreground.ServiceForegroundController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.IdentityHashMap

/**
 * One process-lifetime host per concrete streaming module, shared by its module API and physical
 * Services. A module-specific subclass supplies the configuration and per-launch controller
 * factory. This base owns the final lifecycle algorithm, ordered manager reports, and cleanup
 * work that can outlive a Service object or the caller's wait limit. Finalization observes
 * onDestroy for an attached Service, but its result excludes Android stop-call outcomes. Those
 * calls can remain in flight, and failures are logged. It is not scoped to an Activity or Service,
 * and its in-memory records do not survive process death.
 */
public abstract class StreamingModuleHost<C : StreamingModuleApi.Controller> protected constructor(
    applicationContext: Context,
    private val moduleId: StreamingModule.Id,
    private val serviceClass: Class<out Service>,
    private val foregroundNotificationId: Int,
    private val controllerFactory: (StreamingModuleApi.Controller.Callbacks, ForegroundControl) -> C,
) {
    private enum class InstancePhase { OPEN, CLOSING, FINISHED }

    /** One physical Service lifetime; only the manager mutates these fields under [stateLock]. */
    private class ServiceRecord(val service: Service) {
        var attachedInstanceId: StreamingModuleApi.InstanceId? = null
        var latestDeliveredStartId: Int? = null

        /** Signals observed onDestroy after detachment; it does not prove physical cleanup. */
        val destroyedSignal: CompletableDeferred<Unit> = CompletableDeferred()
        var isReadyToStop: Boolean = false
        var lastStopRequestedStartId: Int? = null
        var normalStopInFlight: Boolean = false
        var emergencyStopRequested: Boolean = false
        var foregroundController: ServiceForegroundController? = null
    }

    private inner class InstanceRecord(val instanceCallbacks: StreamingModuleApi.InstanceCallbacks) {
        val instanceId: StreamingModuleApi.InstanceId = instanceCallbacks.instanceId
        val launchKey: String = instanceId.uuid.toString()

        /** The launch dispatch attempt has ended; Android delivery may still be pending. */
        val launchDispatchDone: CompletableDeferred<Unit> = CompletableDeferred()

        /** The controller setup attempt has ended; owned cleanup may remain. */
        var controllerSetupDone: CompletableDeferred<Unit>? = null
        var phase: InstancePhase = InstancePhase.OPEN
        var serviceRecord: ServiceRecord? = null
        var controller: C? = null
        var hasCleanupFailure: Boolean = false
        val cleanupJobs: MutableList<Job> = ArrayList()
        var pendingStatusReport: StreamingModuleApi.Status? = null
        var pendingHeartbeatAtUptimeMillis: Long = 0L
        var hasPendingFailureReport: Boolean = false
        var failureMessageResource: Int? = null
        var pendingFinishedResult: Boolean? = null

        /** True while report delivery is posted or running. */
        var reportDeliveryScheduled: Boolean = false

        val controllerCallbacks: StreamingModuleApi.Controller.Callbacks = object : StreamingModuleApi.Controller.Callbacks {
            override val instanceId: StreamingModuleApi.InstanceId = this@InstanceRecord.instanceId
            override fun isCurrent(): Boolean = isInstanceCurrent(this@InstanceRecord)
            override fun reportRunning(status: StreamingModuleApi.Status, heartbeatAtUptimeMillis: Long) =
                queueRunningReport(this@InstanceRecord, status, heartbeatAtUptimeMillis)

            override fun reportFailed(@StringRes messageResource: Int?) =
                beginShutdown(this@InstanceRecord, reportFailure = true, messageResource = messageResource)

            override fun launchCleanup(block: suspend () -> Unit): Job =
                this@StreamingModuleHost.launchCleanup(this@InstanceRecord, block)
        }
    }

    private val stateLock: Any = Any()
    private val appContext: Context = applicationContext.applicationContext
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
    private val managerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val instancesByLaunchKey: MutableMap<String, InstanceRecord> = LinkedHashMap()
    private val liveServiceRecords: IdentityHashMap<Service, ServiceRecord> = IdentityHashMap()
    private val _installedController: MutableStateFlow<C?> = MutableStateFlow(null)
    private var desiredController: C? = null

    /** True while controller publication is posted or running. */
    private var controllerPublicationScheduled: Boolean = false

    init {
        require(foregroundNotificationId > 0) { "Foreground notification ID must be positive" }
    }

    /**
     * Eventually published installation for module UI. Closing revokes command admission immediately,
     * but this flow can briefly show the old controller until Main publishes the change; callers
     * must recheck admission before commanding it. Distinct [StreamingModuleApi.Controller] instances
     * retain reference equality so StateFlow does not conflate separate launches.
     */
    public val installedController: StateFlow<C?> = _installedController.asStateFlow()

    /** Reserve the exact launch before asking Android to deliver it. A normal return means dispatch only. */
    public fun requestLaunch(instanceCallbacks: StreamingModuleApi.InstanceCallbacks) {
        val instanceId = instanceCallbacks.instanceId
        require(instanceId.moduleId == moduleId) { "Launch is addressed to a different module" }
        val instance = InstanceRecord(instanceCallbacks)
        synchronized(stateLock) {
            check(instancesByLaunchKey.putIfAbsent(instance.launchKey, instance) == null) { "Duplicate launch identity" }
        }

        try {
            val launchIntent = Intent(appContext, serviceClass).putExtra(EXTRA_INSTANCE_UUID, instance.launchKey)
            checkNotNull(appContext.startService(launchIntent)) { "Android did not find the module Service" }
        } catch (failure: RuntimeException) {
            beginShutdown(instance)
            throw failure
        } finally {
            instance.launchDispatchDone.complete(Unit)
        }
    }

    /** Close only the addressed instance; a coordinator deadline limits waiting, not owned cleanup. */
    public fun requestShutdown(instanceId: StreamingModuleApi.InstanceId) {
        val instance = synchronized(stateLock) { instancesByLaunchKey[instanceId.uuid.toString()]?.takeIf { it.instanceId == instanceId } }
        if (instance != null) beginShutdown(instance)
    }

    /** Typed snapshot for local commands; the controller must recheck admission while handling a command. */
    public fun controllerFor(instanceId: StreamingModuleApi.InstanceId): C? = synchronized(stateLock) {
        val instance = instancesByLaunchKey[instanceId.uuid.toString()] ?: return@synchronized null
        if (instance.instanceId != instanceId || !isInstanceAdmittedLocked(instance)) return@synchronized null
        instance.controller?.takeIf { desiredController === it }
    }

    /** Register one physical Service object from its onCreate callback. */
    public fun onServiceCreated(service: Service) {
        synchronized(stateLock) {
            check(!liveServiceRecords.containsKey(service)) { "Service was already registered" }
            liveServiceRecords[service] = ServiceRecord(service)
        }
    }

    /** Route one Android start delivery to an already registered Service lifetime. */
    public fun onServiceStartCommand(service: Service, intent: Intent?, startId: Int) {
        val serviceRecord = synchronized(stateLock) { liveServiceRecords[service] }
        if (serviceRecord == null) {
            Log.w(TAG, "Start delivered without a registered Service: startId=$startId")
            return
        }
        val launchKey = intent?.getStringExtra(EXTRA_INSTANCE_UUID)
        val requestedInstance = synchronized(stateLock) { launchKey?.let(instancesByLaunchKey::get) }
        val isRequestedInstanceCurrent = requestedInstance?.let(::isInstanceCurrent) == true
        var instanceToStart: InstanceRecord? = null
        var instanceToReject: InstanceRecord? = null
        var shouldRequestStop = false

        synchronized(stateLock) {
            serviceRecord.latestDeliveredStartId = startId
            val rejectableInstance = requestedInstance?.takeIf { it.phase == InstancePhase.OPEN && it.serviceRecord == null }
            when {
                serviceRecord.destroyedSignal.isCompleted -> instanceToReject = rejectableInstance
                serviceRecord.attachedInstanceId != null -> {
                    if (serviceRecord.attachedInstanceId != requestedInstance?.instanceId && requestedInstance?.serviceRecord == null) {
                        instanceToReject = rejectableInstance
                    }
                    shouldRequestStop = serviceRecord.isReadyToStop
                }

                serviceRecord.isReadyToStop -> {
                    instanceToReject = rejectableInstance
                    shouldRequestStop = true
                }

                requestedInstance?.serviceRecord != null -> {
                    // A duplicate delivery on another Service object cannot take its owner away.
                    serviceRecord.isReadyToStop = true
                    shouldRequestStop = true
                }

                requestedInstance == null || requestedInstance.phase != InstancePhase.OPEN || !isRequestedInstanceCurrent -> {
                    instanceToReject = rejectableInstance
                    serviceRecord.isReadyToStop = true
                    shouldRequestStop = true
                }

                else -> {
                    serviceRecord.attachedInstanceId = requestedInstance.instanceId
                    requestedInstance.serviceRecord = serviceRecord
                    requestedInstance.controllerSetupDone = CompletableDeferred()
                    serviceRecord.foregroundController = ServiceForegroundController(
                        service = serviceRecord.service,
                        instanceId = requestedInstance.instanceId,
                        notificationId = foregroundNotificationId,
                        stateLock = stateLock,
                        managerScope = managerScope,
                        isInstanceCurrent = { isInstanceCurrent(requestedInstance) },
                        requestShutdown = { beginShutdown(requestedInstance) },
                    )
                    instanceToStart = requestedInstance
                }
            }
        }

        instanceToReject?.let { beginShutdown(it, reportFailure = true) }
        if (shouldRequestStop) requestServiceStopIfReady(serviceRecord)
        instanceToStart?.let { createAndStartController(it, serviceRecord) }
    }

    /** Detach this exact Service object; already admitted operations retain its record. */
    public fun onServiceDestroyed(service: Service) {
        val attachedInstance: InstanceRecord?
        val foregroundController: ServiceForegroundController?
        val wasUnexpectedDestruction: Boolean
        synchronized(stateLock) {
            val serviceRecord = liveServiceRecords.remove(service) ?: return
            attachedInstance = serviceRecord.attachedInstanceId?.let { instancesByLaunchKey[it.uuid.toString()] }
            foregroundController = serviceRecord.foregroundController
            foregroundController?.markHostDestroyedLocked()
            serviceRecord.attachedInstanceId = null
            wasUnexpectedDestruction = attachedInstance?.phase == InstancePhase.OPEN
            serviceRecord.destroyedSignal.complete(Unit)
        }
        if (wasUnexpectedDestruction && attachedInstance != null) beginShutdown(attachedInstance, reportFailure = true)
    }

    /** Route a platform timeout to its exact registered Service lifetime. */
    public fun onServiceTimeout(service: Service, startId: Int, foregroundServiceType: Int? = null) {
        // The platform startId/type are diagnostic identities, not the logical module owner.
        Log.w(TAG, "Android Service timeout: startId=$startId, type=$foregroundServiceType")
        val serviceRecord = synchronized(stateLock) { liveServiceRecords[service] }
        if (serviceRecord == null) {
            Log.w(TAG, "Timeout delivered without a registered Service: startId=$startId")
            return
        }
        val attachedInstance = synchronized(stateLock) {
            serviceRecord.attachedInstanceId?.let { instancesByLaunchKey[it.uuid.toString()] }
        }
        requestEmergencyStop(serviceRecord)
        if (attachedInstance != null) beginShutdown(attachedInstance, reportFailure = true)
    }

    private fun createAndStartController(instance: InstanceRecord, serviceRecord: ServiceRecord) {
        try {
            val controller = controllerFactory(instance.controllerCallbacks, checkNotNull(serviceRecord.foregroundController))
            // Retain the created owner for cleanup before reading its identity. It is not installed yet.
            val isClosing = synchronized(stateLock) {
                instance.controller = controller
                instance.phase != InstancePhase.OPEN
            }
            if (isClosing) return
            val controllerInstanceId = try {
                controller.instanceId
            } catch (failure: Throwable) {
                Log.e(TAG, "Module controller identity check failed", failure)
                beginShutdown(instance, reportFailure = true)
                return
            }
            if (controllerInstanceId != instance.instanceId) {
                Log.e(TAG, "Module controller identity does not match its launch")
                beginShutdown(instance, reportFailure = true)
                return
            }
            val isCurrent = isInstanceCurrent(instance)
            var shouldPostControllerPublication = false
            val wasInstalled = synchronized(stateLock) {
                if (isCurrent && instance.phase == InstancePhase.OPEN && !serviceRecord.destroyedSignal.isCompleted &&
                    serviceRecord.attachedInstanceId == instance.instanceId
                ) {
                    shouldPostControllerPublication = updateDesiredControllerLocked(controller)
                    true
                } else {
                    false
                }
            }
            if (shouldPostControllerPublication) postControllerPublication()
            if (!wasInstalled) {
                beginShutdown(instance, reportFailure = true)
                return
            }

            // start() only activates already installed, resource-free control work. It must check
            // controllerCallbacks.isCurrent() before its worker admits a resource if Shutdown wins this race.
            if (isInstanceCurrent(instance)) controller.start() else beginShutdown(instance)
        } catch (failure: RuntimeException) {
            Log.e(TAG, "Module controller creation or activation failed", failure)
            beginShutdown(instance, reportFailure = true)
        } finally {
            instance.controllerSetupDone?.complete(Unit)
        }
    }

    private fun isInstanceCurrent(instance: InstanceRecord): Boolean {
        val isAdmitted = synchronized(stateLock) { isInstanceAdmittedLocked(instance) }
        if (!isAdmitted) return false
        val isCurrentForCoordinator = try {
            instance.instanceCallbacks.isCurrent()
        } catch (failure: RuntimeException) {
            Log.e(TAG, "Instance currentness check failed", failure)
            false
        }
        return isCurrentForCoordinator && synchronized(stateLock) { isInstanceAdmittedLocked(instance) }
    }

    private fun isInstanceAdmittedLocked(instance: InstanceRecord): Boolean {
        val serviceRecord = instance.serviceRecord
        return instance.phase == InstancePhase.OPEN && instancesByLaunchKey[instance.launchKey] === instance &&
                (serviceRecord == null || (!serviceRecord.destroyedSignal.isCompleted && serviceRecord.attachedInstanceId == instance.instanceId))
    }

    private fun queueRunningReport(
        instance: InstanceRecord,
        status: StreamingModuleApi.Status,
        heartbeatAtUptimeMillis: Long,
    ) {
        val shouldPostReports = synchronized(stateLock) {
            if (!isInstanceAdmittedLocked(instance)) return
            instance.pendingStatusReport = status
            instance.pendingHeartbeatAtUptimeMillis = heartbeatAtUptimeMillis
            markReportDeliveryScheduledLocked(instance)
        }
        if (shouldPostReports) postReportDelivery(instance)
    }

    private fun launchCleanup(instance: InstanceRecord, block: suspend () -> Unit): Job {
        val cleanupJob = managerScope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (failure: Throwable) {
                synchronized(stateLock) { instance.hasCleanupFailure = true }
                Log.e(TAG, "Module cleanup child failed", failure)
            }
        }
        synchronized(stateLock) { instance.cleanupJobs.add(cleanupJob) }
        cleanupJob.start()
        return cleanupJob
    }

    private fun beginShutdown(instance: InstanceRecord, reportFailure: Boolean = false, @StringRes messageResource: Int? = null) {
        val controller: C?
        val shouldStartFinalization: Boolean
        val shouldPostReports: Boolean
        var shouldPostControllerPublication = false
        synchronized(stateLock) {
            if (instance.phase == InstancePhase.FINISHED) return
            val foregroundFailed = instance.serviceRecord?.foregroundController?.closeAdmissionLocked() == true
            if ((reportFailure || foregroundFailed) && !instance.hasPendingFailureReport && instance.phase == InstancePhase.OPEN) {
                instance.hasPendingFailureReport = true
                instance.failureMessageResource = if (foregroundFailed) null else messageResource
            }
            shouldPostReports = if (instance.hasPendingFailureReport) markReportDeliveryScheduledLocked(instance) else false
            if (instance.phase == InstancePhase.CLOSING) {
                controller = null
                shouldStartFinalization = false
            } else {
                instance.phase = InstancePhase.CLOSING
                controller = instance.controller
                if (controller != null && desiredController === controller) {
                    shouldPostControllerPublication = updateDesiredControllerLocked(null)
                }
                shouldStartFinalization = true
            }
        }
        if (controller != null) requestControllerShutdown(instance, controller)
        if (shouldPostReports) postReportDelivery(instance)
        if (shouldPostControllerPublication) postControllerPublication()
        if (shouldStartFinalization) managerScope.launch { finishShutdown(instance) }
    }

    private fun requestControllerShutdown(instance: InstanceRecord, controller: C) {
        try {
            controller.requestShutdown()
        } catch (failure: Throwable) {
            synchronized(stateLock) { instance.hasCleanupFailure = true }
            Log.e(TAG, "Module controller shutdown request failed", failure)
        }
    }

    private suspend fun finishShutdown(instance: InstanceRecord) {
        instance.launchDispatchDone.await()
        synchronized(stateLock) { instance.controllerSetupDone }?.await()

        val controller = synchronized(stateLock) { instance.controller }
        if (controller != null) requestControllerShutdown(instance, controller)
        val controllerCleanupSucceeded = if (controller == null) true else try {
            controller.awaitCleanup()
        } catch (failure: Throwable) {
            Log.e(TAG, "Module controller cleanup failed", failure)
            false
        }
        val cleanupJobsSnapshot = synchronized(stateLock) { instance.cleanupJobs.toList() }
        for (cleanupJob in cleanupJobsSnapshot) {
            cleanupJob.join()
            if (cleanupJob.isCancelled) synchronized(stateLock) { instance.hasCleanupFailure = true }
        }

        // Foreground release follows controller cleanup, never the controller's cleanup Job list.
        val serviceRecord = synchronized(stateLock) { instance.serviceRecord }
        val foregroundCleanupSucceeded = serviceRecord?.foregroundController?.releaseAndAwaitCleanup() ?: true
        if (serviceRecord != null) {
            synchronized(stateLock) { if (!serviceRecord.destroyedSignal.isCompleted) serviceRecord.isReadyToStop = true }
            requestServiceStopIfReady(serviceRecord)
            serviceRecord.destroyedSignal.await()
        }
        val shouldPostReports = synchronized(stateLock) {
            instance.phase = InstancePhase.FINISHED
            instance.pendingFinishedResult = controllerCleanupSucceeded && foregroundCleanupSucceeded && !instance.hasCleanupFailure
            instancesByLaunchKey.remove(instance.launchKey, instance)
            markReportDeliveryScheduledLocked(instance)
        }
        if (shouldPostReports) postReportDelivery(instance)
    }

    private fun requestServiceStopIfReady(serviceRecord: ServiceRecord) {
        val startId = synchronized(stateLock) {
            if (serviceRecord.destroyedSignal.isCompleted || !serviceRecord.isReadyToStop || serviceRecord.normalStopInFlight) return
            val startId = serviceRecord.latestDeliveredStartId?.takeIf { it != serviceRecord.lastStopRequestedStartId } ?: return
            serviceRecord.lastStopRequestedStartId = startId
            serviceRecord.normalStopInFlight = true
            startId
        }
        managerScope.launch(Dispatchers.IO) {
            try {
                serviceRecord.service.stopSelfResult(startId)
            } catch (failure: Throwable) {
                Log.e(TAG, "Android Service stop request failed", failure)
            }
            val shouldRetryWithLatestStartId = synchronized(stateLock) {
                serviceRecord.normalStopInFlight = false
                serviceRecord.latestDeliveredStartId != startId && !serviceRecord.destroyedSignal.isCompleted && serviceRecord.isReadyToStop
            }
            if (shouldRetryWithLatestStartId) requestServiceStopIfReady(serviceRecord)
        }
    }

    private fun requestEmergencyStop(serviceRecord: ServiceRecord) {
        synchronized(stateLock) {
            if (serviceRecord.destroyedSignal.isCompleted || serviceRecord.emergencyStopRequested) return
            serviceRecord.emergencyStopRequested = true
        }
        managerScope.launch(Dispatchers.IO) {
            try {
                serviceRecord.service.stopSelf()
            } catch (failure: Throwable) {
                Log.e(TAG, "Emergency Android Service stop request failed", failure)
            }
        }
    }

    /** True means the caller must post report delivery after leaving [stateLock]. */
    private fun markReportDeliveryScheduledLocked(instance: InstanceRecord): Boolean {
        if (instance.reportDeliveryScheduled) return false
        instance.reportDeliveryScheduled = true
        return true
    }

    /** True means the caller must post publication after leaving [stateLock]. */
    private fun updateDesiredControllerLocked(controller: C?): Boolean {
        desiredController = controller
        if (controllerPublicationScheduled) return false
        controllerPublicationScheduled = true
        return true
    }

    private fun postControllerPublication() {
        if (!mainHandler.post(::publishDesiredController)) {
            synchronized(stateLock) { controllerPublicationScheduled = false }
            Log.e(TAG, "Main looper rejected an installation publication")
        }
    }

    private fun publishDesiredController() {
        while (true) {
            val controllerToPublish = synchronized(stateLock) { desiredController }
            _installedController.value = controllerToPublish
            val publicationCaughtUp = synchronized(stateLock) {
                if (desiredController === controllerToPublish) {
                    controllerPublicationScheduled = false
                    true
                } else {
                    false
                }
            }
            if (publicationCaughtUp) return
        }
    }

    private fun postReportDelivery(instance: InstanceRecord) {
        if (!mainHandler.post { deliverPendingReports(instance) }) {
            synchronized(stateLock) { instance.reportDeliveryScheduled = false }
            Log.e(TAG, "Main looper rejected a module report")
        }
    }

    private fun deliverPendingReports(instance: InstanceRecord) {
        while (true) {
            val deliverNextReport = synchronized(stateLock) {
                val pendingStatus = instance.pendingStatusReport
                val cleanupCompleted = instance.pendingFinishedResult
                when {
                    pendingStatus != null -> {
                        val pendingHeartbeatAtUptimeMillis = instance.pendingHeartbeatAtUptimeMillis
                        instance.pendingStatusReport = null
                        { instance.instanceCallbacks.reportRunning(pendingStatus, pendingHeartbeatAtUptimeMillis) }
                    }

                    instance.hasPendingFailureReport -> {
                        val messageResource = instance.failureMessageResource
                        instance.hasPendingFailureReport = false
                        { instance.instanceCallbacks.reportFailed(messageResource) }
                    }

                    cleanupCompleted != null -> {
                        instance.pendingFinishedResult = null
                        { instance.instanceCallbacks.reportFinished(cleanupCompleted) }
                    }

                    else -> {
                        instance.reportDeliveryScheduled = false
                        return
                    }
                }
            }
            try {
                deliverNextReport()
            } catch (failure: RuntimeException) {
                Log.e(TAG, "Module coordinator callback failed", failure)
            }
        }
    }

    private companion object {
        private const val TAG: String = "StreamingModuleHost"
        private const val EXTRA_INSTANCE_UUID: String = "io.screenstream.streaming.INSTANCE_UUID"
    }
}
