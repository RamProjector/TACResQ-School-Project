package com.tacresq.app

import android.content.Context
import com.tacresq.app.data.local.SessionStore
import com.tacresq.app.data.remote.ApiClient
import com.tacresq.app.data.remote.FakeTacApi
import com.tacresq.app.data.remote.SupabaseTacApi
import com.tacresq.app.data.remote.TacApi
import com.tacresq.app.data.model.SessionState
import com.tacresq.app.data.repo.TacRepository
import com.tacresq.app.data.repo.TokenHolder
import com.tacresq.app.data.realtime.NoopNotificationRealtime
import com.tacresq.app.data.realtime.NotificationRealtime
import com.tacresq.app.data.realtime.SupabaseNotificationRealtime
import com.tacresq.app.data.routing.RoutingProvider
import com.tacresq.app.data.routing.RenderRoutingClientFactory
import com.tacresq.app.location.LocationProvider
import com.tacresq.app.location.ResponderLocationTrackingController
import com.tacresq.app.notifications.PushTokenCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Simple manual dependency container. Hilt is not needed at this project size. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tokenHolder = TokenHolder()

    private val api: TacApi = when {
        AppConfig.USE_FAKE_API -> FakeTacApi({ tokenHolder.value }, AppConfig.FAKE_AUTO_DISPATCH)
        AppConfig.USE_SUPABASE_API -> SupabaseTacApi.create(
            tokenProvider = { tokenHolder.value },
            useRenderBridge = AppConfig.USE_RENDER_BRIDGE,
        )
        else -> ApiClient.create { tokenHolder.value }
    }

    val repository = TacRepository(
        api,
        SessionStore(appContext, persistSessions = !AppConfig.USE_FAKE_API),
        tokenHolder,
        appScope,
    )
    val notificationRealtime: NotificationRealtime = if (AppConfig.USE_SUPABASE_API) {
        SupabaseNotificationRealtime(
            tokenHolder = tokenHolder,
            userIdProvider = {
                (repository.sessionState.value as? SessionState.LoggedIn)?.session?.user?.id
            },
            scope = appScope,
        )
    } else {
        NoopNotificationRealtime()
    }
    val pushTokenCoordinator = PushTokenCoordinator(appContext, appScope)
    val locationProvider = LocationProvider(appContext)
    val responderLocationTracking = ResponderLocationTrackingController(appContext)
    /** Fake/demo sessions and builds without an explicit route opt-in never contact live routing. */
    val routingProvider: RoutingProvider? = runCatching {
        if (AppConfig.USE_FAKE_API || !AppConfig.ROUTE_PREVIEW_ENABLED) null
        else RenderRoutingClientFactory.create(AppConfig.BASE_URL) { tokenHolder.value }
    }.getOrNull()
}
