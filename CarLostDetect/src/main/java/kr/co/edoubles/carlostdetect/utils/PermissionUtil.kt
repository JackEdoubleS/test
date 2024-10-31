package kr.co.edoubles.carlostdetect.utils

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Permission util
 * 권한 확인을 위한 Utility Class (Camera, Notification etc..)
 */
object PermissionUtil {

    fun checkPermission(context: Context, permissionList: List<String>): Boolean {
        for (index in permissionList.indices) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    permissionList[index]
                ) == PackageManager.PERMISSION_DENIED
            ) {
                return false
            }
        }
        return true
    }

    fun requestPermission(activity: Activity, permissionList: List<String>) {
        ActivityCompat.requestPermissions(activity, permissionList.toTypedArray(), PERMISSION_CODE)
    }
}