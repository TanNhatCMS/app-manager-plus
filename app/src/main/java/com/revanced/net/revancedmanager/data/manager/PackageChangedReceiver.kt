package com.revanced.net.revancedmanager.data.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PackageChangedReceiver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _packageEvents = MutableSharedFlow<PackageEvent>(extraBufferCapacity = 64)
    val packageEvents: SharedFlow<PackageEvent> = _packageEvents

    private var isRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val packageName = intent?.data?.schemeSpecificPart ?: return
            when (intent.action) {
                Intent.ACTION_PACKAGE_ADDED -> _packageEvents.tryEmit(PackageEvent.Installed(packageName))
                Intent.ACTION_PACKAGE_REPLACED -> _packageEvents.tryEmit(PackageEvent.Updated(packageName))
                Intent.ACTION_PACKAGE_REMOVED -> _packageEvents.tryEmit(PackageEvent.Uninstalled(packageName))
            }
        }
    }

    fun register() {
        if (isRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        context.registerReceiver(receiver, filter)
        isRegistered = true
    }

    fun unregister() {
        if (!isRegistered) return
        runCatching { context.unregisterReceiver(receiver) }
        isRegistered = false
    }
}
