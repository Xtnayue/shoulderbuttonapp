package com.shoulderbutton.mapper

import android.app.Application
import android.os.Build
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

class ShoulderMapperApp : Application() {

    override fun onCreate() {
        super.onCreate()
        initHiddenApiBypass()
    }

    private fun initHiddenApiBypass() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("")
                Log.i("ShoulderMapperApp", "HiddenApiBypass applied successfully")
            } catch (e: Exception) {
                Log.w("ShoulderMapperApp", "Failed to apply HiddenApiBypass", e)
            }
        }
    }
}
