package com.intech.camera

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.intech.camera.camera.CameraConfig
import com.intech.camera.camera.ICamera
import com.intech.camera.camera.BlendCamera
import com.intech.camera.camera.IEffectProcessor
import com.intech.camera.util.Logger

internal val logger = Logger.newInstance("base:media:camera")

object CameraFactory {

    @JvmOverloads
    @JvmStatic
    fun newCamera(context: Context, lifecycle: LifecycleOwner? = null, processor: IEffectProcessor? = null, config: CameraConfig = CameraConfig()): ICamera {
        return BlendCamera(context, lifecycle, processor, config)
    }

}