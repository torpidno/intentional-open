package com.pause.app.service

/**
 * Starts a timed session only after its deadline has been persisted.
 *
 * Returning a failure means the target app must stay closed: the caller should
 * offer a retry rather than launching with an unprotected session.
 */
interface SessionStarter {
    suspend fun startSession(
        packageName: String,
        appLabel: String,
        limitMinutes: Int,
    ): Result<Unit>

    suspend fun cancelSession(packageName: String?)
}
