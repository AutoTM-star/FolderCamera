package com.example.foldercamera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 카메라 화면: 셔터를 누를 때마다 "사진(Pictures)/선택한폴더" 에 저장.
 * 뒤로가기를 누를 때까지 계속 찍을 수 있음.
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var folder: String
    private lateinit var previewView: PreviewView
    private lateinit var flashOverlay: View
    private lateinit var countText: TextView
    private lateinit var orientationListener: OrientationEventListener

    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var savedCount = 0

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, "카메라 권한이 있어야 촬영할 수 있어요", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        folder = intent.getStringExtra(EXTRA_FOLDER) ?: run { finish(); return }

        previewView = findViewById(R.id.previewView)
        flashOverlay = findViewById(R.id.flashOverlay)
        countText = findViewById(R.id.countText)
        findViewById<TextView>(R.id.folderName).text = "📁 $folder"

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.shutterButton).setOnClickListener { takePhoto() }
        findViewById<View>(R.id.switchButton).setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
        }

        // 화면은 세로 고정이지만, 폰을 눕혀서 찍으면 사진은 가로로 저장되도록
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                imageCapture?.targetRotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStart() {
        super.onStart()
        orientationListener.enable()
    }

    override fun onStop() {
        super.onStop()
        orientationListener.disable()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            var selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            if (!provider.hasCamera(selector)) {
                Toast.makeText(this, "이 방향의 카메라가 없어요", Toast.LENGTH_SHORT).show()
                lensFacing = CameraSelector.LENS_FACING_BACK
                selector = CameraSelector.DEFAULT_BACK_CAMERA
            }

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, capture)
                imageCapture = capture
            } catch (e: Exception) {
                Toast.makeText(this, "카메라를 열 수 없어요: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return

        val fileName = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            // 여기서 저장 폴더가 정해짐: 내장메모리/Pictures/폴더이름
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$folder")
        }

        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).build()

        playFlash()

        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    savedCount++
                    countText.text = "${savedCount}장 저장됨"
                    countText.visibility = View.VISIBLE
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(
                        this@CameraActivity,
                        "저장 실패: ${exception.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun playFlash() {
        flashOverlay.animate().cancel()
        flashOverlay.alpha = 0.7f
        flashOverlay.animate().alpha(0f).setDuration(180).start()
    }

    companion object {
        const val EXTRA_FOLDER = "folder"
    }
}
