package com.intech.camera.camera

import android.annotation.SuppressLint
import android.util.Log
import androidx.annotation.Keep
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraXConfig

@Keep
class ConfigProvider: CameraXConfig.Provider {
    @SuppressLint("UnsafeExperimentalUsageError")
    override fun getCameraXConfig(): CameraXConfig {
        return CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setMinimumLoggingLevel(Log.ERROR)
            .build()
    }
}