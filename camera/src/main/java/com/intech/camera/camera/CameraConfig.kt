package com.intech.camera.camera

import com.intech.camera.constant.CameraFacing

data class CameraConfig(
    var width: Int = 720,
    var height: Int = 1280,
    var facing: CameraFacing = CameraFacing.FRONT
)