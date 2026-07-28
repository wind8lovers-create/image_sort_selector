package com.example.image_sort_selector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.image_sort_selector.ui.theme.Image_sort_selectorTheme
import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import android.provider.MediaStore
import android.util.Log
import androidx.compose.material3.Slider
import androidx.compose.material3.Button
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            Image_sort_selectorTheme {
                val context = LocalContext.current
                var threshold by remember { mutableStateOf(50f) }
                var valueThreshold by remember { mutableStateOf(50f) }
                var isProcessing by remember { mutableStateOf(false) }
                var progressText by remember { mutableStateOf("") }
                val coroutineScope = rememberCoroutineScope()

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (isGranted) {
                        Toast.makeText(context, "許可されました!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "許可されませんでした", Toast.LENGTH_SHORT).show()
                    }
                }

                LaunchedEffect(Unit) {
                    permissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding).padding(16.dp)) {
                        Text(text = "彩度の閾値: ${threshold.toInt()}")
                        Slider(
                            value = threshold,
                            onValueChange = { threshold = it },
                            valueRange = 0f..100f
                        )
                        Text(text = "明度の閾値: ${valueThreshold.toInt()}")
                        Slider(
                            value = valueThreshold,
                            onValueChange = { valueThreshold = it },
                            valueRange = 0f..100f
                        )

                        Button(
                            onClick = {
                                isProcessing = true
                                progressText = "開始します..."
                                coroutineScope.launch {
                                    withContext(Dispatchers.IO) {
                                        listImages(context, threshold.toInt(), valueThreshold.toInt()) { current, total ->
                                            withContext(Dispatchers.Main) {
                                                progressText = "$current / $total 枚 処理中..."
                                            }
                                        }
                                    }
                                    progressText = "完了しました!"
                                    isProcessing = false
                                }
                            },
                            enabled = !isProcessing
                        ) {
                            Text(if (isProcessing) "処理中..." else "仕分け開始")
                        }

                        if (progressText.isNotEmpty()) {
                            Text(text = progressText)
                        }
                    }
                }
            }
        }
    }

    private suspend fun listImages(
        context: android.content.Context,
        threshold: Int,
        valueThreshold: Int,
        onProgress: suspend (Int, Int) -> Unit
    ) {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME
        )

        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            MediaStore.Images.Media.DATE_ADDED + " DESC"
        )

        cursor?.use {
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val total = 30
            var count = 0
            while (it.moveToNext() && count < total) {
                val id = it.getLong(idColumn)
                val name = it.getString(nameColumn)

                val imageUri = android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )

                analyzeImage(context, imageUri, name, threshold, valueThreshold)
                count++
                onProgress(count, total)
            }
        }
    }

    private fun analyzeImage(context: android.content.Context, uri: android.net.Uri, name: String, threshold: Int, valueThreshold: Int) {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return
        val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
        inputStream.close()

        if (bitmap == null) {
            Log.d("ImageSortSelector", "$name: 読み込み失敗")
            return
        }

        var totalSaturation = 0f
        var totalValue = 0f
        var pixelCount = 0
        val hsv = FloatArray(3)

        val step = 5
        for (x in 0 until bitmap.width step step) {
            for (y in 0 until bitmap.height step step) {
                val pixel = bitmap.getPixel(x, y)
                android.graphics.Color.colorToHSV(pixel, hsv)
                totalSaturation += hsv[1]
                totalValue += hsv[2]
                pixelCount++
            }
        }

        val avgSaturation = (totalSaturation / pixelCount) * 100
        val avgValue = (totalValue / pixelCount) * 100

        val saturationLabel = if (avgSaturation >= threshold) "vivid" else "calm"
        val valueLabel = if (avgValue >= valueThreshold) "bright" else "dark"
        val folderName = "filter_${saturationLabel}_${valueLabel}"

        Log.d(
            "ImageSortSelector",
            "$name → 彩度平均: ${"%.1f".format(avgSaturation)}, 明度平均: ${"%.1f".format(avgValue)} → $folderName へ"
        )

        saveToFolder(context, bitmap, folderName, name)

        bitmap.recycle()
    }

    private fun saveToFolder(
        context: android.content.Context,
        bitmap: android.graphics.Bitmap,
        folderName: String,
        fileName: String
    ) {
        val resolver = context.contentResolver
        val contentValues = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ImageSortSelector/$folderName")
        }

        try {
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { outStream ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, outStream)
                }
            } else {
                Log.d("ImageSortSelector", "$fileName: URIの発行に失敗")
            }
        } catch (e: Exception) {
            Log.d("ImageSortSelector", "$fileName: 保存失敗 - ${e.message}")
        }
    }

}
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    Image_sort_selectorTheme {
        Greeting("Android")
    }
}