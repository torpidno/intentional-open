package com.pause.app.service

import com.pause.app.data.repository.InterceptionRepository
import com.pause.app.domain.model.InterventionOutcome
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repairs the narrow crash window between committing a session and writing its
 * history/launch outcome.
 *
 * An active session is the durable "continued" decision. If its attempt row is
 * still pending, complete it as [InterventionOutcome.PROCEEDED] with the
 * saved intention and limit. Every other pending row came from a pause that
 * disappeared with the process and is closed as walked away.
 *
 * The target app is intentionally not launched again on startup. Replaying a
 * background launch would be surprising; instead, the restored session arms the
 * matching cooldown so opening the app manually does not show a duplicate pause.
 */
@Singleton
class SessionStartupReconciler @Inject constructor(
    private val persistence: SessionPersistence,
    private val interceptionRepository: InterceptionRepository,
    private val cooldownTracker: CooldownTracker,
    private val clock: Clock,
) {

    suspend fun reconcile() {
        val active = persistence.read()
        val attemptId = active?.attemptId
        if (attemptId != null) {
            interceptionRepository.completeAttempt(
                id = attemptId,
                outcome = InterventionOutcome.PROCEEDED,
                intention = active.intention,
                sessionLimitMinutes = active.limitMinutes,
            )
        }
        if (active != null) {
            val remaining = active.endsAtEpochMillis - clock.millis()
            if (remaining > 0L) {
                cooldownTracker.setCooldown(active.packageName, remaining)
            }
        }
        interceptionRepository.completeStalePendingAttempts()
    }
}
