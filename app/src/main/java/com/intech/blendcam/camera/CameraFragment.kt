package com.intech.blendcam.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.intech.blendcam.databinding.FragmentCameraBinding
import com.intech.camera.CameraFactory
import com.intech.camera.camera.CameraConfig
import com.intech.camera.camera.ICamera
import com.intech.camera.constant.CameraFacing
import java.io.File

class CameraFragment: Fragment() {

    private val TAG = "CameraFragment"

    private var mCamera: ICamera? = null

    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    private val REQUEST_CODE_PERMISSIONS = 10

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private var mPhotoUrl: String = ""

    private var mLensFacing = CameraFacing.FRONT

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentCameraBinding.inflate(layoutInflater)
        initView()
        return _binding?.root
    }

    override fun onStart() {
        super.onStart()
        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) {
                    startCamera()
                } else {
                    Toast.makeText(requireContext(), "No Permission", Toast.LENGTH_SHORT).show()
                }
            }.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun initView() {
        _binding = binding

        binding.shotBtn.setOnClickListener {
            mPhotoUrl = File(requireContext().getExternalFilesDir(null), "photo_beauty_${System.currentTimeMillis()}.jpg").absolutePath
            mCamera?.takePhoto(mPhotoUrl) { success, width, height ->
                Log.i(TAG, "takePhoto :: success")
                if (success) {
//                    PreviewActivity.playPhoto(this, mPhotoUrl, width to height)
                    File(mPhotoUrl).deleteOnExit()
                }
            }
        }

        binding.flipCameraBtn.setOnClickListener {
            mLensFacing = if (mLensFacing == CameraFacing.FRONT) CameraFacing.BACK else CameraFacing.FRONT
            mCamera?.setCameraFacing(mLensFacing)
        }

        binding.beautyToggleBtn.setOnCheckedChangeListener { _, isChecked ->
            mCamera?.enableFaceBeauty(isChecked)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        if (mCamera == null) {
            mCamera = CameraFactory.newCamera(
                requireContext(),
                lifecycle = this,
                config = CameraConfig(720, 1280)
            )
            mCamera?.setPreview(binding.textureView)
            mCamera?.startWhenPrepared()
        }
    }

}