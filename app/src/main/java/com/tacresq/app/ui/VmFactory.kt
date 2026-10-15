package com.tacresq.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.tacresq.app.AppContainer
import com.tacresq.app.ui.auth.AuthViewModel
import com.tacresq.app.ui.dispatcher.DispatcherViewModel
import com.tacresq.app.ui.requester.RequesterViewModel
import com.tacresq.app.ui.responder.ResponderViewModel

class VmFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val viewModel: ViewModel = when {
            modelClass.isAssignableFrom(AuthViewModel::class.java) ->
                AuthViewModel(container.repository)
            modelClass.isAssignableFrom(RequesterViewModel::class.java) ->
                RequesterViewModel(container.repository, container.locationProvider)
            modelClass.isAssignableFrom(ResponderViewModel::class.java) ->
                ResponderViewModel(container.repository)
            modelClass.isAssignableFrom(DispatcherViewModel::class.java) ->
                DispatcherViewModel(container.repository)
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        }
        return viewModel as T
    }
}
