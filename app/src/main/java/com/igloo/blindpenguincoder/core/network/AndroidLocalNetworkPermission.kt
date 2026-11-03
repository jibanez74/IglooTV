package com.igloo.blindpenguincoder.core.network

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.edit

const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

fun androidLocalNetworkPermission(context: Context): LocalNetworkPermissionCoordinator {
    val appContext = context.applicationContext
    val preferences = appContext.getSharedPreferences("local_network_permission", Context.MODE_PRIVATE)
    return LocalNetworkPermissionCoordinator(
        permissionGranted = {
            Build.VERSION.SDK_INT < 37 ||
                ContextCompat.checkSelfPermission(appContext, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED
        },
        wasRequested = { preferences.getBoolean("requested", false) },
        markRequested = { preferences.edit { putBoolean("requested", true) } },
    )
}
