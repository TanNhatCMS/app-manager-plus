package com.revanced.net.revancedmanager.data.manager

sealed class PackageEvent {
    data class Installed(val packageName: String) : PackageEvent()
    data class Updated(val packageName: String) : PackageEvent()
    data class Uninstalled(val packageName: String) : PackageEvent()
}
