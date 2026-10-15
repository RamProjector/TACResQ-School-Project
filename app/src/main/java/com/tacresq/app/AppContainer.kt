package com.tacresq.app

import android.content.Context
import com.tacresq.app.data.local.SessionStore
import com.tacresq.app.data.remote.ApiClient
import com.tacresq.app.data.remote.FakeTacApi
import com.tacresq.app.data.remote.TacApi
import com.tacresq.app.data.repo.TacRepository
import com.tacresq.app.data.repo.TokenHolder
import com.tacresq.app.location.LocationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Simple manual dependency container. Hilt is not needed at this project size. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tokenHolder = TokenHolder()

    private val api: TacApi =
        if (AppConfig.USE_FAKE_API) {
            FakeTacApi { tokenHolder.value }
        } else {
            ApiClient.create { tokenHolder.value }
        }

    val repository = TacRepository(api, SessionStore(appContext), tokenHolder, appScope)
    val locationProvider = LocationProvider(appContext)
}
