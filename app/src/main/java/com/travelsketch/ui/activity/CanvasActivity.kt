package com.travelsketch.ui.activity

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.travelsketch.ui.composable.*
import com.travelsketch.ui.layout.CanvasEditLayout
import com.travelsketch.viewmodel.CanvasViewModel
import java.io.File

class CanvasActivity : ComponentActivity() {
    private val CAMERA_PERMISSION_REQUEST_CODE = 100
    private lateinit var cameraLauncher: ActivityResultLauncher<Uri>
    private lateinit var galleryLauncher: ActivityResultLauncher<String>
    private lateinit var canvasViewModel: CanvasViewModel
    private var tempImageUri: Uri? = null

    private lateinit var videoLauncher: ActivityResultLauncher<String>
    private lateinit var videoCameraLauncher: ActivityResultLauncher<Uri>
    private var tempVideoUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        canvasViewModel = ViewModelProvider(this)[CanvasViewModel::class.java]
        canvasViewModel.setContext(this)
        checkAndRequestPermissions()

        val rawCanvasId = intent.getStringExtra("CANVAS_ID")
        val isEditable = intent.getBooleanExtra("EDITABLE", false)

        if (rawCanvasId == null) {
            finish()
            return
        }

        val actualCanvasId = when {
            rawCanvasId.contains("canvasId=") ->
                rawCanvasId.substringAfter("canvasId=").substringBefore(",")
            else -> rawCanvasId
        }

        canvasViewModel.initializeCanvas(actualCanvasId)

        galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let {
                canvasViewModel.startImagePlacement(it.toString())
            }
        }

        cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) {
                tempImageUri?.let { uri ->
                    canvasViewModel.startImagePlacement(uri.toString())
                }
            }
        }

        videoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let {
                canvasViewModel.startVideoPlacement(it.toString())
            }
        }

        videoCameraLauncher = registerForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
            if (success) {
                tempVideoUri?.let { uri ->
                    canvasViewModel.startVideoPlacement(uri.toString())
                }
            }
        }

        setContent {
            val showDialog = remember { mutableStateOf(false) }
            val showImageSourceDialog = remember { mutableStateOf(false) }
            val showVideoSourceDialog = remember { mutableStateOf(false) }
            val isEditing = remember { mutableStateOf(false) }

            if (showDialog.value) {
                TextInputDialog(
                    onDismiss = { showDialog.value = false },
                    onConfirm = { text ->
                        canvasViewModel.startTextPlacement(text)
                        showDialog.value = false
                    }
                )
            }

            if (showImageSourceDialog.value) {
                ImageSourceDialog(
                    onDismiss = { showImageSourceDialog.value = false },
                    onSelectCamera = {
                        val imageFile = File.createTempFile("temp_image", ".jpg", cacheDir)
                        tempImageUri = FileProvider.getUriForFile(
                            this,
                            "${packageName}.fileprovider",
                            imageFile
                        )
                        tempImageUri?.let { cameraLauncher.launch(it) }
                        showImageSourceDialog.value = false
                    },
                    onSelectGallery = {
                        galleryLauncher.launch("image/*")
                        showImageSourceDialog.value = false
                    }
                )
            }

            if (showVideoSourceDialog.value) {
                VideoSourceDialog(
                    onDismiss = { showVideoSourceDialog.value = false },
                    onSelectCamera = {
                        val videoFile = File.createTempFile("temp_video", ".mp4", cacheDir)
                        tempVideoUri = FileProvider.getUriForFile(
                            this,
                            "${packageName}.fileprovider",
                            videoFile
                        )
                        tempVideoUri?.let { videoCameraLauncher.launch(it) }
                        showVideoSourceDialog.value = false
                    },
                    onSelectGallery = {
                        videoLauncher.launch("video/*")
                        showVideoSourceDialog.value = false
                    }
                )
            }

            CanvasEditLayout(
                canvas = {
                    CanvasScreen(
                        viewModel = canvasViewModel,
                        onTapForBox = { canvasPos ->
                            if (isEditing.value && isEditable) {
                                canvasViewModel.createBox(canvasPos.x, canvasPos.y)
                            }
                        },
                        editable = isEditable
                    )
                },
                button = {
                    if (isEditable) {
                        Button(
                            onClick = {
                                isEditing.value = !isEditing.value
                                canvasViewModel.toggleIsEditable()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White
                            ),
                            modifier = Modifier
                                .padding(8.dp)
                                .border(
                                    width = 2.dp,
                                    color = Color.Black,
                                    shape = RoundedCornerShape(8.dp)
                                )
                        ) {
                            Text(
                                text = if (isEditing.value) "Done" else "Edit",
                                color = Color.Black
                            )
                        }
                    }
                },
                editor = {
                    if (isEditing.value && isEditable) {
                        Editor(
                            canvasViewModel = canvasViewModel,
                            showDialog = showDialog,
                            showImageSourceDialog = showImageSourceDialog,
                            showVideoSourceDialog = showVideoSourceDialog,
                            createAndSharePdf = { createAndSharePdf() }
                        )
                    }
                },
                statusBar = {
                    StatusBar(canvasViewModel)
                }
            )
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (!isGranted) {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
            }
        }

    private fun checkAndRequestPermissions() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun createAndSharePdf() {
        val path = canvasViewModel.createPDF() ?: return

        if (!canvasViewModel.checkStoragePermission(this)) {
            canvasViewModel.requestStoragePermission(this)
        }
        canvasViewModel.sharePdfFile(this, path)
    }
}
