package com.eko.dcvisits.app.di

import android.content.Context
import com.eko.dcvisits.app.BuildConfig
import com.eko.dcvisits.app.data.db.AppDatabase
import com.eko.dcvisits.app.data.repo.ApprovalsRepository
import com.eko.dcvisits.app.data.repo.AttendanceRepository
import com.eko.dcvisits.app.data.repo.AuthRepository
import com.eko.dcvisits.app.data.repo.CspRepository
import com.eko.dcvisits.app.data.repo.NearestCspRepository
import com.eko.dcvisits.app.data.repo.PhotoRepository
import com.eko.dcvisits.app.data.repo.ScorecardRepository
import com.eko.dcvisits.app.data.repo.VisitRepository
import com.eko.dcvisits.app.data.session.SessionStore
import com.eko.dcvisits.app.data.sync.OutboxRepository

/**
 * Hand-rolled DI — one graph, lazily built, no framework. Deliberate: fewer
 * moving parts on a ₹8–15k phone, and the wiring is readable in one file
 * (BUILD_PLAN §12 "boring, dominant convention").
 */
object ServiceLocator {

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun ctx(fallback: Context? = null): Context =
        appContext ?: fallback?.applicationContext
        ?: error("ServiceLocator.init() not called")

    val database: AppDatabase by lazy { AppDatabase.get(ctx()) }
    val sessionStore: SessionStore by lazy { SessionStore(ctx()) }

    private val outbox: OutboxRepository by lazy {
        OutboxRepository(
            dao = database.outboxDao(),
            session = sessionStore,
            appVersion = BuildConfig.VERSION_NAME,
        )
    }

    fun outboxRepository(context: Context? = null): OutboxRepository {
        if (appContext == null && context != null) init(context)
        return outbox
    }

    val authRepository: AuthRepository by lazy {
        AuthRepository(sessionStore, database)
    }

    val cspRepository: CspRepository by lazy {
        CspRepository(database.cspCacheDao(), sessionStore)
    }

    val nearestCspRepository: NearestCspRepository by lazy {
        NearestCspRepository(sessionStore)
    }

    val attendanceRepository: AttendanceRepository by lazy {
        AttendanceRepository(ctx(), outbox, sessionStore)
    }

    val visitRepository: VisitRepository by lazy {
        VisitRepository(ctx(), outbox, sessionStore)
    }

    val photoRepository: PhotoRepository by lazy {
        PhotoRepository(ctx(), outbox, sessionStore)
    }

    val scorecardRepository: ScorecardRepository by lazy {
        ScorecardRepository(sessionStore)
    }

    val approvalsRepository: ApprovalsRepository by lazy {
        ApprovalsRepository(sessionStore)
    }
}
