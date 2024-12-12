import android.Manifest
import android.app.Activity
import android.graphics.Color
import android.graphics.Paint
import android.graphics.BitmapFactory
import android.net.Uri
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.storage.FirebaseStorage
import com.travelsketch.data.dao.FirebaseClient
import com.travelsketch.data.dao.FirebaseRepository
import com.travelsketch.data.model.BoxData
import com.travelsketch.data.model.BoxType
import com.travelsketch.data.util.ReceiptClassifier
import com.travelsketch.ui.composable.loadVideoThumbnail
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class CanvasViewModel : ViewModel() {
    val canvasWidth = 10000f
    val canvasHeight = 8000f

    private var receiptClassifier: ReceiptClassifier? = null

    var isLoading = mutableStateOf(false)
    var isUploading = mutableStateOf(false)
    var canvasId = mutableStateOf("")
    var isEditable = mutableStateOf(true)
    var boxes = mutableStateListOf<BoxData>()
    var selected = mutableStateOf<BoxData?>(null)

    var isTextPlacementMode = mutableStateOf(false)
    var textToPlace = mutableStateOf("")

    var isImagePlacementMode = mutableStateOf(false)
    var imageToPlace = mutableStateOf<String?>(null)

    var isVideoPlacementMode = mutableStateOf(false)
    var videoToPlace = mutableStateOf<String?>(null)

    val bitmaps = mutableStateMapOf<String, Bitmap>()
    val invalidateCanvasState = mutableStateOf(false)

    private var context: Context? = null

    var defaultBrush = mutableStateOf(Paint().apply {
        color = Color.BLACK
        textSize = 70f
        textAlign = Paint.Align.LEFT
    })

    var selectBrush = mutableStateOf(Paint().apply {
        color = Color.BLUE
        style = Paint.Style.FILL
    })

    private var screenWidth = 0f
    private var screenHeight = 0f

    fun setContext(context: Context) {
        this.context = context
        receiptClassifier = ReceiptClassifier(context)
        Log.d("asdfasdfasdf", "Receipt classifier initialized")
    }

    fun setScreenSize(width: Float, height: Float) {
        screenWidth = width
        screenHeight = height
    }

    fun initializeCanvas(id: String) {
        Log.d("asdfasdfasdf", "Initializing canvas with ID: $id")
        canvasId.value = id
        viewAllBoxes(id)
    }

    fun loadImage(imageUrl: String) {
        if (imageUrl.isEmpty() || imageUrl == "uploading" || bitmaps.containsKey(imageUrl)) return

        Log.d("asdfasdfasdf", "Starting image load for URL: $imageUrl")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    Log.d("asdfasdfasdf", "Attempting to connect to URL: $imageUrl")
                    val url = URL(imageUrl)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    connection.doInput = true
                    connection.connect()

                    Log.d("asdfasdfasdf", "Connection established, decoding bitmap")
                    BitmapFactory.decodeStream(connection.inputStream)
                }

                bitmap?.let {
                    Log.d("asdfasdfasdf", "Bitmap successfully decoded")
                    withContext(Dispatchers.Main) {
                        bitmaps[imageUrl] = it
                        invalidateCanvasState.value = !invalidateCanvasState.value
                        Log.d("asdfasdfasdf", "Bitmap stored and canvas invalidated")
                    }
                } ?: Log.e("asdfasdfasdf", "Failed to decode bitmap")

            } catch (e: Exception) {
                Log.e("asdfasdfasdf", "Failed to load image: $imageUrl", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        receiptClassifier?.close()
        receiptClassifier = null
    }

    private var boxIdMap = mutableMapOf<String, BoxData>()

    private fun viewAllBoxes(canvasId: String) {
        Log.d("asdfasdfasdf", "Starting viewAllBoxes for canvasId: $canvasId")
        viewModelScope.launch {
            isLoading.value = true
            try {
                Log.d("asdfasdfasdf", "Reading box data from Firebase")
                val snapshot = FirebaseClient.readAllBoxData(canvasId)
                Log.d("asdfasdfasdf", "Received data from Firebase: $snapshot")

                withContext(Dispatchers.Main) {
                    boxes.clear()
                    boxIdMap.clear()
                    Log.d("asdfasdfasdf", "Cleared existing boxes")

                    snapshot?.forEach { boxData ->
                        Log.d("asdfasdfasdf", "Processing box: ${boxData.id}")
                        boxes.add(boxData)
                        boxIdMap[boxData.id] = boxData

                        if (boxData.type == BoxType.IMAGE.toString() || boxData.type == BoxType.RECEIPT.toString()) {
                            Log.d("asdfasdfasdf", "Found image/receipt box with data: ${boxData.data}")
                            if (!boxData.data.isNullOrEmpty() && boxData.data != "uploading") {
                                Log.d("asdfasdfasdf", "Starting image load for: ${boxData.data}")
                                if (boxData.data.startsWith("http")) {
                                    loadImage(boxData.data)
                                } else {
                                    Log.d("asdfasdfasdf", "Invalid image URL format: ${boxData.data}")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("asdfasdfasdf", "Error in viewAllBoxes", e)
            } finally {
                isLoading.value = false
            }
        }
    }

    private fun createImageBox(canvasX: Float, canvasY: Float) {
        viewModelScope.launch {
            val imageUri = imageToPlace.value ?: return@launch
            val uri = Uri.parse(imageUri)

            var tempBox: BoxData? = null
            try {
                isUploading.value = true
                Log.d("asdfasdfasdf", "Starting image box creation process")

                // 이미지 분류 수행
                val bitmap = MediaStore.Images.Media.getBitmap(context?.contentResolver, uri)
                val isReceipt = receiptClassifier?.classifyImage(bitmap) ?: false
                Log.d("asdfasdfasdf", "Image classification result - Is Receipt: $isReceipt")

                val (width, height) = calculateImageDimensions(context!!, uri)
                val boxId = UUID.randomUUID().toString()

                // BoxType을 분류 결과에 따라 설정
                val boxType = if (isReceipt) BoxType.RECEIPT else BoxType.IMAGE
                Log.d("asdfasdfasdf", "Setting box type as: ${boxType.name}")

                tempBox = BoxData(
                    id = boxId,
                    boxX = (canvasX - width / 2f).toInt(),
                    boxY = (canvasY - height / 2f).toInt(),
                    width = width,
                    height = height,
                    type = boxType.toString(),
                    data = "uploading"
                )

                // Firebase Storage에 업로드
                val downloadUrl = FirebaseRepository().uploadImageAndGetUrl(uri)
                Log.d("asdfasdfasdf", "Successfully uploaded to Firebase. URL: $downloadUrl")

                val finalBox = tempBox.copy(data = downloadUrl)
                val saveSuccess = FirebaseClient.writeBoxData(
                    canvasId.value,
                    boxId,
                    finalBox
                )

                if (!saveSuccess) {
                    throw Exception("Failed to save box data to database")
                }

                boxes.add(finalBox)
                boxIdMap[finalBox.id] = finalBox
                loadImage(downloadUrl)

            } catch (e: Exception) {
                Log.e("asdfasdfasdf", "Error in createImageBox", e)
                tempBox?.let {
                    boxes.remove(it)
                    boxIdMap.remove(it.id)
                }
            } finally {
                isUploading.value = false
                isImagePlacementMode.value = false
                imageToPlace.value = null
            }
        }
    }

    // ... [나머지 기존 메소드들은 동일하게 유지]

    fun toggleIsEditable() {
        isEditable.value = !isEditable.value
        if (!isEditable.value) {
            clearSelection()
        }
    }

    fun select(box: BoxData) {
        selected.value = box
    }

    fun clearSelection() {
        selected.value = null
    }

    fun startTextPlacement(text: String) {
        textToPlace.value = text
        isTextPlacementMode.value = true
    }

    fun startImagePlacement(imageUri: String) {
        imageToPlace.value = imageUri
        isImagePlacementMode.value = true
    }

    fun startVideoPlacement(videoUri: String) {
        videoToPlace.value = videoUri
        isVideoPlacementMode.value = true
    }

    fun createBox(canvasX: Float, canvasY: Float) {
        if (isTextPlacementMode.value) {
            createTextBox(canvasX, canvasY)
        } else if (isImagePlacementMode.value) {
            createImageBox(canvasX, canvasY)
        } else if (isVideoPlacementMode.value) {
            createVideoBox(canvasX, canvasY)
        }
    }

    private fun createTextBox(canvasX: Float, canvasY: Float) {
        val text = textToPlace.value
        val paint = defaultBrush.value
        val textWidth = paint.measureText(text).toInt()
        val textHeight = (paint.fontMetrics.descent - paint.fontMetrics.ascent).toInt()
        val centeredX = canvasX - textWidth / 2f
        val centeredY = canvasY - textHeight / 2f

        val box = BoxData(
            boxX = centeredX.toInt(),
            boxY = centeredY.toInt(),
            width = textWidth,
            height = textHeight,
            type = BoxType.TEXT.toString(),
            data = text
        )
        boxes.add(box)
        boxIdMap[box.id] = box

        isTextPlacementMode.value = false
        textToPlace.value = ""

        viewModelScope.launch {
            FirebaseClient.writeBoxData(
                canvasId.value,
                box.id,
                box
            )
        }
    }

    private fun createVideoBox(canvasX: Float, canvasY: Float) {
        viewModelScope.launch {
            val videoUri = videoToPlace.value ?: return@launch
            val uri = Uri.parse(videoUri)

            try {
                isUploading.value = true
                Log.d("asdfasdfasdf", "Starting video box creation")

                val width = 600
                val height = 400
                val centeredX = canvasX - width / 2f
                val centeredY = canvasY + height / 2f
                val thumbnail = loadVideoThumbnail(context!!, videoUri)

                val tempBox = BoxData(
                    boxX = centeredX.toInt(),
                    boxY = centeredY.toInt(),
                    width = width,
                    height = height,
                    type = BoxType.VIDEO.toString(),
                    data = videoUri
                )
                boxes.add(tempBox)

                thumbnail?.let {
                    bitmaps[videoUri] = it
                    invalidateCanvasState.value = !invalidateCanvasState.value
                }

                val timestamp = System.currentTimeMillis()
                val videoFileName = "video_${timestamp}.mp4"
                val storageRef = FirebaseStorage.getInstance().reference
                    .child("media/videos/$videoFileName")

                context?.contentResolver?.openInputStream(uri)?.use { inputStream ->
                    val uploadTask = storageRef.putStream(inputStream)
                    uploadTask.await()

                    val downloadUrl = storageRef.downloadUrl.await().toString()
                    Log.d("asdfasdfasdf", "Video uploaded successfully. URL: $downloadUrl")

                    val finalBox = tempBox.copy(data = downloadUrl)
                    val index = boxes.indexOf(tempBox)
                    if (index != -1) {
                        boxes[index] = finalBox
                    }

                    FirebaseClient.writeBoxData(
                        canvasId.value,
                        finalBox.id,
                        finalBox
                    )
                }
            } catch (e: Exception) {
                Log.e("asdfasdfasdf", "Error creating video box", e)
                boxes.removeIf { it.data == videoUri }
            } finally {
                isUploading.value = false
                isVideoPlacementMode.value = false
                videoToPlace.value = null
            }
        }
    }

    private suspend fun calculateImageDimensions(context: Context, uri: Uri): Pair<Int, Int> {
        return withContext(Dispatchers.IO) {
            try {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }

                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BitmapFactory.decodeStream(inputStream, null, options)
                }

                val maxSize = 500
                val width = options.outWidth
                val height = options.outHeight
                val ratio = width.toFloat() / height.toFloat()

                if (width > height) {
                    Pair(maxSize, (maxSize / ratio).toInt())
                } else {
                    Pair((maxSize * ratio).toInt(), maxSize)
                }
            } catch (e: Exception) {
                Log.e("asdfasdfasdf", "Error calculating image dimensions", e)
                Pair(500, 500)
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            selected.value?.let { box ->
                try {
                    if ((box.type == BoxType.IMAGE.toString() || box.type == BoxType.RECEIPT.toString()) &&
                        box.data.startsWith("https")) {
                        try {val storage = FirebaseStorage.getInstance()
                            val imageRef = storage.getReferenceFromUrl(box.data)
                            imageRef.delete().await()
                            Log.d("asdfasdfasdf", "Successfully deleted image from storage")
                        } catch (e: Exception) {
                            Log.e("asdfasdfasdf", "Failed to delete image from storage", e)
                        }
                    }

                    FirebaseClient.deleteBoxData(canvasId.value, box.id)
                    Log.d("asdfasdfasdf", "Successfully deleted box data from Firebase")

                    boxes.remove(box)
                    boxIdMap.remove(box.id)
                    selected.value = null
                } catch (e: Exception) {
                    Log.e("asdfasdfasdf", "Failed to delete box", e)
                }
            }
        }
    }

    fun updateBoxPosition(newX: Int, newY: Int) {
        val currentBox = selected.value ?: return

        currentBox.boxX = newX
        currentBox.boxY = newY

        val index = boxes.indexOfFirst { it.id == currentBox.id }
        if (index != -1) {
            boxes[index] = currentBox
            boxIdMap[currentBox.id] = currentBox
        }

        viewModelScope.launch {
            FirebaseClient.writeBoxData(canvasId.value, currentBox.id, currentBox)
        }
    }

    fun saveAll() {
        viewModelScope.launch {
            isLoading.value = true
            try {
                boxes.forEach { box ->
                    FirebaseClient.writeBoxData(
                        canvasId.value,
                        box.id,
                        box
                    )
                }
                Log.d("asdfasdfasdf", "All boxes saved successfully")
            } catch (e: Exception) {
                Log.e("asdfasdfasdf", "Error saving all boxes", e)
            } finally {
                isLoading.value = false
            }
        }
    }

    fun endPlacementMode() {
        isTextPlacementMode.value = false
        isImagePlacementMode.value = false
        isVideoPlacementMode.value = false
        textToPlace.value = ""
        imageToPlace.value = null
        videoToPlace.value = null
    }

    private fun reloadAllImages() {
        boxes.forEach { box ->
            if ((box.type == BoxType.IMAGE.toString() || box.type == BoxType.RECEIPT.toString()) &&
                !box.data.isNullOrEmpty() &&
                box.data != "uploading") {
                loadImage(box.data)
            }
        }
    }

    // seonghun

    fun checkStoragePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun requestStoragePermission(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ),
            PackageManager.PERMISSION_GRANTED
        )
    }

    fun createPDF(): String? {
        val pdfDocument = PdfDocument()
        val paint = Paint().apply {
            textSize = 90f
            isFilterBitmap = true
        }

        val pageInfo = PdfDocument.PageInfo.Builder(
            dpToPx(screenWidth),
            dpToPx(screenHeight),
            1).create()

        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas

        // 텍스트, 이미지, 동영상 그림
        for (box in boxes) {
            if (box.type == BoxType.TEXT.toString())
                canvas.drawText(box.data, box.boxX.toFloat(), box.boxY.toFloat(), paint)
            else if (box.type == BoxType.IMAGE.toString()) {
                if (box.data.startsWith("http") && bitmaps.containsKey(box.data)) {
                    Log.d("TEST", "감지 ${bitmaps[box.data]}")
                    val bitmap = bitmaps[box.data]
                    val scaledBitmap = Bitmap.createScaledBitmap(
                        bitmap!!,
                        dpToPx(box.width!!.toFloat()), dpToPx(box.height!!.toFloat()),
                        false
                    )

                    canvas.drawBitmap(
                        scaledBitmap,
                        dpToPx(box.boxX.toFloat()/(2.6f)).toFloat(),
                        dpToPx(box.boxY.toFloat()/7).toFloat(),
                        paint
                    )
                }
            }
        }

        pdfDocument.finishPage(page)

        val directory = context?.getExternalFilesDir(null)
        val file = File(directory, "tmp.pdf")

        try {
            // FileOutputStream을 use 블록으로 감싸고
            FileOutputStream(file).use { outputStream ->
                pdfDocument.writeTo(outputStream) // writeTo() 호출 시 outputStream을 사용
            }

            Log.d("TEST", "PDF가 ${file.absolutePath}에 저장되었습니다")
            return file.absolutePath

        } catch (e: IOException) {
            e.printStackTrace()
            Log.d("TEST", "PDF 저장 중 오류 발생")
        } finally {
            pdfDocument.close()  // pdfDocument.close()는 finally에서 호출되어야 함
        }
        return null
    }

    fun sharePdfFile(context: Context, filePath: String) {
        val file = File(filePath)

        if (file.exists()) {
            val fileUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            } else {
                Uri.fromFile(file)
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"  // 공유할 파일 타입
                putExtra(Intent.EXTRA_STREAM, fileUri)  // 파일 URI 전달
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)  // URI 읽기 권한 부여
            }

            context.startActivity(Intent.createChooser(shareIntent, "Select App to share"))
        } else {
            // 파일이 존재하지 않으면 오류 처리
            Log.d("TEST", "File doesn't exist.")
        }
    }

    private fun dpToPx(dp: Float): Int {
        val density = context!!.resources.displayMetrics.density
        return (dp * density).toInt()
    }

    // end

    init {
        Log.d("asdfasdfasdf", "CanvasViewModel initialized")
        reloadAllImages()
    }
}