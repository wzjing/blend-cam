package com.intech.camera.camera

import com.intech.camera.frame.CameraFrame

interface IEffectProcessor {
    fun render(frame: CameraFrame)

    fun reset()

    fun release()
}