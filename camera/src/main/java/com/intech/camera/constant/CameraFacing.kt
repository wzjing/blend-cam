package com.intech.camera.constant

import androidx.camera.core.CameraSelector

enum class CameraFacing(val value: Int) {
    FRONT(CameraSelector.LENS_FACING_FRONT),
    BACK(CameraSelector.LENS_FACING_BACK)
}