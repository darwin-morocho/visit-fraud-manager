package fir.codergym.visit_fraud_manager

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

val minInterval = 15.seconds

private fun String.isBiometricWorkerId(): Boolean {
    return startsWith("worker:") || startsWith("employee_")
}

class VisitsFraudManager(
    private var interval: Duration,
    private val notificationDelay: Duration,
    lifecycle: Lifecycle? = null
) : LifecycleObserver {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val visits = ConcurrentHashMap<String, Long>()
    private val notifications = ConcurrentHashMap<String, Long>()
    private var purgeJob: Job? = null


    var isUsingMinInterval = interval == minInterval

    var enabled = interval.inWholeSeconds > 1L

    init {
        val shouldEnable = interval > 0.seconds
        if (interval.inWholeSeconds < 60L) {
            interval = minInterval
        }
        enabled = shouldEnable
        isUsingMinInterval = shouldEnable && interval == minInterval
        lifecycle?.addObserver(this)
        if (enabled) {
            startPurgeTimer()
        }
    }

    fun recordVisit(userId: String) {
        if (!enabled) {
            return
        }

        if (userId.isBiometricWorkerId()) {
            return
        }
        visits[userId] = System.currentTimeMillis()
    }

    fun removeVisit(userId: String) {
        if (!enabled) {
            return
        }
        visits.remove(userId)
        notifications.remove(userId)
    }

    fun hasRecentVisit(userId: String): Boolean {
        if (!enabled) {
            return false
        }

        if (userId.isBiometricWorkerId()) {
            return false
        }

        val lastVisit = visits[userId] ?: return false
        val difference = (System.currentTimeMillis() - lastVisit).milliseconds
        return difference <= interval
    }

    fun recordNotification(userId: String) {
        if (!enabled) {
            return
        }
        notifications[userId] = System.currentTimeMillis()
    }

    fun shouldNotify(userId: String): Boolean {
        val lastNotification = notifications[userId] ?: return true
        val difference = (System.currentTimeMillis() - lastNotification).milliseconds
        return difference > notificationDelay
    }

    fun startPurgeTimer() {
        purgeJob?.cancel()
        purgeJob = scope.launch {
            while (isActive) {
                delay(interval)
                val now = System.currentTimeMillis()
                visits.entries.removeAll { entry ->
                    val difference = (now - entry.value).milliseconds
                    difference > interval
                }
            }
        }
    }

    fun setInterval(newInterval: Duration) {
        val shouldEnable = newInterval > 0.seconds
        if (interval == newInterval && enabled == shouldEnable) {
            return
        }

        purgeJob?.cancel()

        if (!shouldEnable) {
            interval = 0.seconds
            enabled = false
            isUsingMinInterval = false
            visits.clear()
            notifications.clear()
            return
        }

        enabled = true
        interval = if (newInterval.inWholeSeconds < 60L) minInterval else newInterval
        isUsingMinInterval = interval == minInterval
        startPurgeTimer()
    }

    fun dispose() {
        visits.clear()
        notifications.clear()
        purgeJob?.cancel()
        scope.cancel()
    }
}
