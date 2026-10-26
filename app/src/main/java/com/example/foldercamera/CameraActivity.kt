package com.example.foldercamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 카메라 화면: 셔터(또는 음량 버튼)를 누를 때마다 "사진(Pictures)/선택한폴더" 에 저장.
 *  - 두 손가락 줌 / 0.5×·1×·2×… 버튼
 *  - 화면 터치로 초점 맞추기
 *  - 플래시 끔/자동/켬
 *  - "삼성 카메라" 버튼: 폰 기본 카메라 앱으로 찍고 같은 폴더에 저장 (뒤로가기 전까지 연속)
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var folder: String
    private var site: Site? = null          // 공유 현장이면 OneDrive로 자동 업로드
    private lateinit var uploadText: TextView
    private lateinit var previewView: PreviewView
    private lateinit var flashOverlay: View
    private lateinit var focusRing: View
    private lateinit var countText: TextView
    private lateinit var flashButton: TextView
    private lateinit var zoomRow: LinearLayout
    private lateinit var orientationListener: OrientationEventListener

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var savedCount = 0

    // 줌 버튼
    private val zoomPresets = mutableListOf<Float>()
    private val zoomButtons = mutableListOf<TextView>()
    private var builtZoomRange: Pair<Float, Float>? = null

    // 기본(삼성) 카메라로 찍을 때 미리 만들어 둔 저장 위치
    private var pendingSystemUri: Uri? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, "카메라 권한이 있어야 촬영할 수 있어요", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    private val systemCameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = pendingSystemUri
            pendingSystemUri = null
            if (uri == null) return@registerForActivityResult
            if (success) {
                onPhotoSaved(uri)
                launchSystemCamera()   // 연속 촬영: 뒤로가기를 누르면 이 화면으로 돌아옴
            } else {
                contentResolver.delete(uri, null, null)   // 취소 → 빈 파일 정리
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        folder = intent.getStringExtra(EXTRA_FOLDER) ?: run { finish(); return }
        site = intent.getStringExtra(EXTRA_SITE_CODE)?.let { SiteStore.find(this, it) }

        if (savedInstanceState != null) {
            pendingSystemUri = BundleCompat.getParcelable(savedInstanceState, KEY_PENDING, Uri::class.java)
            savedCount = savedInstanceState.getInt(KEY_COUNT)
            lensFacing = savedInstanceState.getInt(KEY_LENS, lensFacing)
            flashMode = savedInstanceState.getInt(KEY_FLASH, flashMode)
        }

        previewView = findViewById(R.id.previewView)
        flashOverlay = findViewById(R.id.flashOverlay)
        focusRing = findViewById(R.id.focusRing)
        countText = findViewById(R.id.countText)
        flashButton = findViewById(R.id.flashButton)
        zoomRow = findViewById(R.id.zoomRow)
        uploadText = findViewById(R.id.uploadText)
        findViewById<TextView>(R.id.folderName).text =
            site?.let { "🏗 ${it.name} › $folder" } ?: "📁 $folder"
        updateCountText()
        updateFlashButton()
        observeUploads()

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.shutterButton).setOnClickListener { takePhoto() }
        findViewById<View>(R.id.systemCameraButton).setOnClickListener { launchSystemCamera() }
        findViewById<View>(R.id.switchButton).setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
        }
        flashButton.setOnClickListener {
            flashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                else -> ImageCapture.FLASH_MODE_OFF
            }
            imageCapture?.flashMode = flashMode
            updateFlashButton()
        }

        setupTouchGestures()

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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(KEY_PENDING, pendingSystemUri)
        outState.putInt(KEY_COUNT, savedCount)
        outState.putInt(KEY_LENS, lensFacing)
        outState.putInt(KEY_FLASH, flashMode)
    }

    override fun onStart() {
        super.onStart()
        orientationListener.enable()
    }

    override fun onStop() {
        super.onStop()
        orientationListener.disable()
    }

    // ───────── 음량 버튼으로 촬영 ─────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.repeatCount == 0) takePhoto()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return true   // 음량 조절 창이 뜨지 않게
        }
        return super.onKeyUp(keyCode, event)
    }

    // ───────── 카메라 시작 ─────────

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
                .setFlashMode(flashMode)
                .build()

            try {
                camera?.cameraInfo?.zoomState?.removeObservers(this)
                provider.unbindAll()
                val cam = provider.bindToLifecycle(this, selector, preview, capture)
                camera = cam
                imageCapture = capture

                flashButton.visibility =
                    if (cam.cameraInfo.hasFlashUnit()) View.VISIBLE else View.INVISIBLE

                builtZoomRange = null
                cam.cameraInfo.zoomState.observe(this) { state ->
                    val range = state.minZoomRatio to state.maxZoomRatio
                    if (range != builtZoomRange) {
                        buildZoomButtons(cam, state.minZoomRatio, state.maxZoomRatio)
                        builtZoomRange = range
                    }
                    updateZoomButtons(state.zoomRatio)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "카메라를 열 수 없어요: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ───────── 줌 & 터치 초점 ─────────

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchGestures() {
        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return false
                    val state = cam.cameraInfo.zoomState.value ?: return false
                    val target = (state.zoomRatio * detector.scaleFactor)
                        .coerceIn(state.minZoomRatio, state.maxZoomRatio)
                    cam.cameraControl.setZoomRatio(target)
                    return true
                }
            })

        val tapDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    focusAt(e.x, e.y)
                    return true
                }
            })

        previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                tapDetector.onTouchEvent(event)
            }
            true
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        cam.cameraControl.startFocusAndMetering(action)

        focusRing.animate().cancel()
        focusRing.x = x - focusRing.width / 2f
        focusRing.y = y - focusRing.height / 2f + previewView.top
        focusRing.alpha = 1f
        focusRing.scaleX = 1.3f
        focusRing.scaleY = 1.3f
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(200).withEndAction {
            focusRing.animate().alpha(0f).setStartDelay(800).setDuration(300).start()
        }.start()
    }

    private fun buildZoomButtons(cam: Camera, min: Float, max: Float) {
        zoomRow.removeAllViews()
        zoomButtons.clear()
        zoomPresets.clear()

        if (min < 0.95f) zoomPresets.add(min)                       // 초광각 (0.5× / 0.6×)
        listOf(1f, 2f, 5f, 10f).filter { it <= max + 0.01f }.forEach { zoomPresets.add(it) }
        if (zoomPresets.size <= 1) return                           // 줌이 안 되는 카메라

        val size = dp(44)
        zoomPresets.forEach { ratio ->
            val button = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = dp(5)
                    marginEnd = dp(5)
                }
                gravity = android.view.Gravity.CENTER
                textSize = 12f
                setBackgroundResource(R.drawable.zoom_chip)
                setOnClickListener { cam.cameraControl.setZoomRatio(ratio) }
            }
            zoomButtons.add(button)
            zoomRow.addView(button)
        }
    }

    private fun updateZoomButtons(current: Float) {
        if (zoomButtons.isEmpty()) return
        // 현재 배율 이하인 버튼 중 가장 큰 것을 "선택됨"으로 (삼성 카메라와 비슷한 방식)
        var active = 0
        zoomPresets.forEachIndexed { i, preset -> if (current + 0.01f >= preset) active = i }
        zoomButtons.forEachIndexed { i, button ->
            val selected = i == active
            button.isSelected = selected
            button.setTextColor(if (selected) Color.parseColor("#FFD54F") else Color.WHITE)
            button.text = if (selected) formatZoom(current, true) else formatZoom(zoomPresets[i], false)
        }
    }

    private fun formatZoom(ratio: Float, precise: Boolean): String {
        val whole = kotlin.math.abs(ratio - Math.round(ratio)) < 0.05f
        return if (ratio >= 1f && whole && !precise) "${Math.round(ratio)}×"
        else String.format(Locale.US, "%.1f×", ratio)
    }

    // ───────── 촬영 ─────────

    private fun newPhotoValues(): ContentValues {
        var fileName = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val currentSite = site
        // 공유 현장: 파일 이름 앞에 촬영자 이름 (누가 찍었는지 알 수 있게)
        val uploader = SiteStore.uploader(this)
        if (currentSite != null && uploader.isNotBlank()) fileName = "${uploader}_$fileName"
        // 저장 폴더: 내장메모리/Pictures/폴더이름  (공유 현장은 Pictures/현장이름/폴더이름)
        val relativePath = if (currentSite != null)
            "${Environment.DIRECTORY_PICTURES}/${currentSite.name}/$folder"
        else
            "${Environment.DIRECTORY_PICTURES}/$folder"
        return ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$fileName.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return

        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            newPhotoValues()
        ).build()

        playFlash()

        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onPhotoSaved(output.savedUri)
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

    /** 폰 기본 카메라(삼성 카메라)를 열어서 찍은 사진을 선택한 폴더에 저장 */
    private fun launchSystemCamera() {
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, newPhotoValues())
        if (uri == null) {
            Toast.makeText(this, "저장 위치를 만들 수 없어요", Toast.LENGTH_LONG).show()
            return
        }
        pendingSystemUri = uri
        try {
            systemCameraLauncher.launch(uri)
        } catch (e: ActivityNotFoundException) {
            contentResolver.delete(uri, null, null)
            pendingSystemUri = null
            Toast.makeText(this, "기본 카메라 앱을 찾을 수 없어요", Toast.LENGTH_LONG).show()
        }
    }

    private fun onPhotoSaved(uri: Uri?) {
        savedCount++
        updateCountText()
        val currentSite = site
        if (currentSite != null && uri != null) {
            UploadWorker.enqueue(this, uri, currentSite, folder)
        }
    }

    /** 공유 현장: 업로드 대기 중인 사진 수 표시 */
    private fun observeUploads() {
        val currentSite = site ?: return
        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData(UploadWorker.tag(currentSite.code))
            .observe(this) { infos ->
                val waiting = infos.count { !it.state.isFinished }
                val failed = infos.count { it.state == WorkInfo.State.FAILED }
                uploadText.visibility = View.VISIBLE
                uploadText.text = when {
                    waiting > 0 -> "☁ 업로드 대기 ${waiting}장"
                    failed > 0 -> "⚠ 업로드 실패 ${failed}장"
                    else -> "☁ OneDrive 업로드 완료"
                }
            }
    }

    private fun updateCountText() {
        countText.text = "${savedCount}장 저장됨"
        countText.visibility = if (savedCount > 0) View.VISIBLE else View.GONE
    }

    private fun updateFlashButton() {
        flashButton.text = when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> "⚡ 자동"
            ImageCapture.FLASH_MODE_ON -> "⚡ 켬"
            else -> "⚡ 끔"
        }
    }

    private fun playFlash() {
        flashOverlay.animate().cancel()
        flashOverlay.alpha = 0.7f
        flashOverlay.animate().alpha(0f).setDuration(180).start()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_FOLDER = "folder"
        const val EXTRA_SITE_CODE = "site_code"
        private const val KEY_PENDING = "pending_uri"
        private const val KEY_COUNT = "saved_count"
        private const val KEY_LENS = "lens"
        private const val KEY_FLASH = "flash"
    }
}
