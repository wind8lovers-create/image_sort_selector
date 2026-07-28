package com.example.image_sort_selector

import android.Manifest
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.image_sort_selector.ui.theme.Image_sort_selectorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImageInfo(
    val uri: android.net.Uri,
    val name: String,
    val saturation: Float,
    val value: Float,
    val dateAdded: Long, // DATE_TAKEN(撮影日時)が取れればそちらを優先。取れない場合はDATE_ADDED(登録日時)
    val sizeBytes: Long,
    val pHash: Long
)
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
                var sortedResults by remember { mutableStateOf<Map<String, List<android.net.Uri>>>(emptyMap()) }
                var infoList by remember { mutableStateOf<List<ImageInfo>>(emptyList()) }
                var sortMode by remember { mutableStateOf("size") }
                var selectedUris by remember { mutableStateOf<Set<android.net.Uri>>(emptySet()) }
                // フォルダ選択機能まわりのstate(空集合=全フォルダ対象)
                var availableFolders by remember { mutableStateOf<List<String>>(emptyList()) }
                var selectedFolders by remember { mutableStateOf<Set<String>>(emptySet()) }
                // pHashで「似ている」と判定するハミング距離の閾値(小さいほど厳しく=ほぼ同一画像だけをまとめる)
                var phashThreshold by remember { mutableStateOf(10f) }
                // オンのときは「似ている画像が他にもある(グループが2枚以上)」ものだけ表示する
                var hideUnmatched by remember { mutableStateOf(true) }
                // 詳細設定(スライダー・フォルダ選択)パネルの開閉状態。デフォルトは閉じておき、画像一覧の表示スペースを確保する
                var settingsExpanded by remember { mutableStateOf(false) }
                // 選択移動の保存先フォルダ名(Pictures/ImageSortSelector/の下に作られる)
                var moveFolderName by remember { mutableStateOf("Moved") }
                // 一度に安全に削除・移動できる目安の枚数(これを超えたら警告を出す)
                val safeSelectionLimit = 500
                val filteredList = remember(infoList, threshold, valueThreshold) {
                    infoList.filter { info ->
                        info.saturation >= threshold && info.value >= valueThreshold
                    }
                }

                // pHash(perceptual hash)のハミング距離が近い画像同士を同じグループにまとめる
                // (以前の「彩度・明度を5刻みで丸めてグループ化」より、見た目が似ている画像を検出しやすい)
                val groupedList = remember(filteredList, sortMode, phashThreshold) {
                    val clusters = mutableListOf<MutableList<ImageInfo>>()
                    val distanceLimit = phashThreshold.toInt()
                    filteredList.forEach { info ->
                        val existingCluster = clusters.find { cluster ->
                            cluster.any { hammingDistance(it.pHash, info.pHash) <= distanceLimit }
                        }
                        if (existingCluster != null) {
                            existingCluster.add(info)
                        } else {
                            clusters.add(mutableListOf(info))
                        }
                    }
                    clusters
                        .map { group ->
                            if (sortMode == "size") group.sortedBy { it.sizeBytes }
                            else group.sortedBy { it.dateAdded }
                        }
                        .sortedBy { group -> group.minOf { it.dateAdded } }
                }

                // hideUnmatchedがオンのときは、似ている画像が他にない(1枚だけの)グループを非表示にする
                val displayedGroups = remember(groupedList, hideUnmatched) {
                    if (hideUnmatched) groupedList.filter { it.size > 1 } else groupedList
                }
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

                // 処理中(isProcessing=true)の間だけ画面が自動で暗くならないようにする
                // 大量の画像を処理する際に、途中でブラックアウトして処理が中断されるのを防ぐ
                val view = LocalView.current
                DisposableEffect(isProcessing) {
                    view.keepScreenOn = isProcessing
                    onDispose { view.keepScreenOn = false }
                }

                // 「選択削除」ボタンでシステムの削除確認ダイアログを出し、結果を受け取るランチャー
                val deleteLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartIntentSenderForResult()
                ) { result ->
                    if (result.resultCode == android.app.Activity.RESULT_OK) {
                        // 削除OKされた分を画面のリストからも取り除く
                        infoList = infoList.filter { !selectedUris.contains(it.uri) }
                        selectedUris = emptySet()
                        Toast.makeText(context, "削除しました", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "削除をキャンセルしました", Toast.LENGTH_SHORT).show()
                    }
                }

                // 「選択移動」ボタンでシステムの書き込み許可ダイアログを出し、OKされたら実際に移動処理を行うランチャー
                val moveLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartIntentSenderForResult()
                ) { result ->
                    if (result.resultCode == android.app.Activity.RESULT_OK) {
                        val urisToMove = selectedUris.toList()
                        val folderNameForMove = moveFolderName.ifBlank { "Moved" }
                        coroutineScope.launch {
                            withContext(Dispatchers.IO) {
                                moveImagesToFolder(context, urisToMove, folderNameForMove)
                            }
                            infoList = infoList.filter { !selectedUris.contains(it.uri) }
                            selectedUris = emptySet()
                            Toast.makeText(context, "「$folderNameForMove」へ移動しました", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "移動をキャンセルしました", Toast.LENGTH_SHORT).show()
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding).padding(16.dp)) {
                        // 詳細設定パネルの開閉ヘッダー(タップで開閉。普段は閉じておいて画像一覧のスペースを確保する)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { settingsExpanded = !settingsExpanded },
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Text(text = if (settingsExpanded) "▼ 詳細設定" else "▶ 詳細設定")
                        }

                        if (settingsExpanded) {
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
                            Text(text = "似ている判定の厳しさ(小さいほど厳密): ${phashThreshold.toInt()}")
                            Slider(
                                value = phashThreshold,
                                onValueChange = { phashThreshold = it },
                                valueRange = 0f..30f
                            )

                            // フォルダ選択機能: 対象フォルダを絞り込みたいときに使う(何も選ばなければ全フォルダが対象)
                            // 他のボタンと区別しやすいよう青色にしている
                            Button(
                                onClick = {
                                    availableFolders = getAllFolderNames(context)
                                },
                                enabled = !isProcessing,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = androidx.compose.ui.graphics.Color(0xFF1E88E5),
                                    contentColor = androidx.compose.ui.graphics.Color.White
                                )
                            ) {
                                Text("フォルダ一覧を取得")
                            }
                            if (availableFolders.isNotEmpty()) {
                                Text(text = "対象フォルダ: ${selectedFolders.size}/${availableFolders.size}件選択中(0件なら全フォルダ対象)")
                                LazyColumn(modifier = Modifier.height(150.dp)) {
                                    items(availableFolders) { folder ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    selectedFolders = if (selectedFolders.contains(folder)) {
                                                        selectedFolders - folder
                                                    } else {
                                                        selectedFolders + folder
                                                    }
                                                },
                                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = selectedFolders.contains(folder),
                                                onCheckedChange = { checked ->
                                                    selectedFolders = if (checked) {
                                                        selectedFolders + folder
                                                    } else {
                                                        selectedFolders - folder
                                                    }
                                                }
                                            )
                                            Text(text = folder)
                                        }
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = {
                                isProcessing = true
                                progressText = "情報収集中..."
                                coroutineScope.launch {
                                    val collected = withContext(Dispatchers.IO) {
                                        collectAllImageInfo(context, selectedFolders) { current, total ->
                                            withContext(Dispatchers.Main) {
                                                progressText = "$current / $total 枚 情報収集中..."
                                            }
                                        }
                                    }
                                    infoList = collected
                                    progressText = "情報収集完了! ${collected.size}枚"
                                    isProcessing = false
                                }
                            },
                            enabled = !isProcessing
                        ) {
                            Text("画像情報読み込み")
                        }

                        if (progressText.isNotEmpty()) {
                            Text(text = progressText)
                        }
                        if (infoList.isNotEmpty()) {
                            Text(text = "絞り込み結果: ${filteredList.size} / ${infoList.size} 枚(${displayedGroups.size}/${groupedList.size}グループ表示中)")

                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(text = "似ている画像だけ表示")
                                Switch(
                                    checked = hideUnmatched,
                                    onCheckedChange = { hideUnmatched = it }
                                )
                            }

                            Row {
                                Button(onClick = { sortMode = "size" }) {
                                    Text("サイズ順")
                                }
                                Button(onClick = { sortMode = "date" }) {
                                    Text("日付順")
                                }
                            }

                            // 選択枚数が多すぎると削除・移動のリクエストがシステム側で失敗する可能性があるため警告する
                            if (selectedUris.size > safeSelectionLimit) {
                                Text(
                                    text = "⚠ 選択数が多すぎます(${selectedUris.size}枚)。一度に処理すると失敗する可能性があります。${safeSelectionLimit}枚程度までに分けることをおすすめします。",
                                    color = androidx.compose.ui.graphics.Color.Red
                                )
                            }

                            // 選択移動の保存先フォルダ名を入力する欄(Pictures/ImageSortSelector/フォルダ名/ に保存される)
                            OutlinedTextField(
                                value = moveFolderName,
                                onValueChange = { moveFolderName = it },
                                label = { Text("移動先フォルダ名") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            // 選択削除・選択移動ボタン(選択中が0枚のときは押せないようにする)
                            Row {
                                Button(
                                    onClick = {
                                        val pendingIntent = MediaStore.createDeleteRequest(
                                            context.contentResolver,
                                            selectedUris.toList()
                                        )
                                        deleteLauncher.launch(
                                            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                                        )
                                    },
                                    enabled = selectedUris.isNotEmpty()
                                ) {
                                    Text("選択削除 (${selectedUris.size})")
                                }
                                Button(
                                    onClick = {
                                        val pendingIntent = MediaStore.createWriteRequest(
                                            context.contentResolver,
                                            selectedUris.toList()
                                        )
                                        moveLauncher.launch(
                                            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                                        )
                                    },
                                    enabled = selectedUris.isNotEmpty()
                                ) {
                                    Text("選択移動 → ${moveFolderName.ifBlank { "Moved" }} (${selectedUris.size})")
                                }
                            }

                            LazyColumn {
                                items(displayedGroups) { group ->
                                    LazyRow {
                                        items(group) { info ->
                                            Column(
                                                modifier = Modifier.padding(4.dp),
                                                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                                            ) {
                                                val isSelected = selectedUris.contains(info.uri)
                                                Box {
                                                    AsyncImage(
                                                        model = info.uri,
                                                        contentDescription = null,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier
                                                            .size(100.dp)
                                                            .border(
                                                                width = if (isSelected) 3.dp else 0.dp,
                                                                color = if (isSelected) androidx.compose.ui.graphics.Color.Red else androidx.compose.ui.graphics.Color.Transparent
                                                            )
                                                            .clickable {
                                                                selectedUris = if (isSelected) {
                                                                    selectedUris - info.uri
                                                                } else {
                                                                    selectedUris + info.uri
                                                                }
                                                            }
                                                    )
                                                    if (isSelected) {
                                                        Text(
                                                            text = "✓",
                                                            color = androidx.compose.ui.graphics.Color.White,
                                                            modifier = Modifier
                                                                .align(androidx.compose.ui.Alignment.TopEnd)
                                                                .background(androidx.compose.ui.graphics.Color.Red)
                                                                .padding(2.dp)
                                                        )
                                                    }
                                                }
                                                Text(text = formatFileSize(info.sizeBytes), fontSize = 10.sp)
                                                Text(text = formatDate(info.dateAdded), fontSize = 10.sp)
                                            }                                        }
                                    }                                }
                            }
                        }

                        if (sortedResults.isNotEmpty()) {
                            LazyColumn {
                                items(sortedResults.keys.toList()) { folderName ->
                                    Text(text = "$folderName (${sortedResults[folderName]?.size ?: 0}枚)")
                                    LazyRow {
                                        items(sortedResults[folderName] ?: emptyList()) { uri ->
                                            AsyncImage(
                                                model = uri,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .padding(4.dp)
                                                    .size(100.dp)
                                            )
                                        }
                                    }
                                }
                            }
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
    ): Map<String, List<android.net.Uri>> {
        val results = mutableMapOf<String, MutableList<android.net.Uri>>()

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

                val result = analyzeImage(context, imageUri, name, threshold, valueThreshold)
                if (result != null) {
                    val (folderName, savedUri) = result
                    results.getOrPut(folderName) { mutableListOf() }.add(savedUri)
                }

                count++
                onProgress(count, total)
            }
        }

        return results
    }
    private fun analyzeImage(
        context: android.content.Context,
        uri: android.net.Uri,
        name: String,
        threshold: Int,
        valueThreshold: Int
    ): Pair<String, android.net.Uri>? {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
        inputStream.close()

        if (bitmap == null) {
            Log.d("ImageSortSelector", "$name: 読み込み失敗")
            return null
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

        val savedUri = saveToFolder(context, bitmap, folderName, name)

        bitmap.recycle()

        return if (savedUri != null) folderName to savedUri else null
    }
    private fun collectImageInfo(context: android.content.Context, uri: android.net.Uri, name: String, mediaStoreDate: Long, sizeBytes: Long): ImageInfo? {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
        inputStream.close()

        if (bitmap == null) {
            Log.d("ImageSortSelector", "$name: 読み込み失敗")
            return null
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
        val pHash = computeAverageHash(bitmap)

        bitmap.recycle()

        // ファイルのEXIF情報から撮影日時を直接読み取る。MediaStoreのDATE_TAKENが
        // (まとめてインポート/復元された等の理由で)実態とズレている場合の保険として、
        // EXIFの値が取れればそちらを優先する
        val exifDate = readExifDateSeconds(context, uri)
        val effectiveDate = exifDate ?: mediaStoreDate

        return ImageInfo(
            uri = uri,
            name = name,
            saturation = avgSaturation,
            value = avgValue,
            dateAdded = effectiveDate,
            sizeBytes = sizeBytes,
            pHash = pHash
        )
    }
    private suspend fun collectAllImageInfo(
        context: android.content.Context,
        selectedFolders: Set<String>,
        onProgress: suspend (Int, Int) -> Unit
    ): List<ImageInfo> {
        val infoList = mutableListOf<ImageInfo>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE
        )

        // フォルダが選択されているときだけ、そのフォルダに絞り込む条件を作る(空なら全フォルダ対象)
        var selection: String? = null
        var selectionArgs: Array<String>? = null
        if (selectedFolders.isNotEmpty()) {
            val placeholders = selectedFolders.joinToString(",") { "?" }
            selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} IN ($placeholders)"
            selectionArgs = selectedFolders.toTypedArray()
        }

        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            MediaStore.Images.Media.DATE_ADDED + " DESC"
        )

        cursor?.use {
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val dateTakenColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val dateModifiedColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

            // 動作確認用に上限を12000枚に設定(端末の全画像枚数をカバーできる想定)
            val total = minOf(it.count, 12000)
            var count = 0

            while (it.moveToNext() && count < total) {
                val id = it.getLong(idColumn)
                val name = it.getString(nameColumn)
                val dateAdded = it.getLong(dateColumn)
                // DATE_TAKEN(撮影日時、ミリ秒)が取れればそちらを優先。
                // 取れなければDATE_MODIFIED(ファイル最終更新日時、秒)、それも0ならDATE_ADDEDで代用する
                val dateTakenMillis = it.getLong(dateTakenColumn)
                val dateModified = it.getLong(dateModifiedColumn)
                val effectiveDate = when {
                    dateTakenMillis > 0 -> dateTakenMillis / 1000
                    dateModified > 0 -> dateModified
                    else -> dateAdded
                }
                val sizeBytes = it.getLong(sizeColumn)


                val imageUri = android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )

                val info = collectImageInfo(context, imageUri, name, effectiveDate, sizeBytes)
                if (info != null) {
                    infoList.add(info)
                }

                count++
                onProgress(count, total)
            }
        }

        return infoList
    }
    private fun saveToFolder(context: android.content.Context, bitmap: android.graphics.Bitmap, folderName: String, fileName: String): android.net.Uri? {
        val resolver = context.contentResolver
        val contentValues = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ImageSortSelector/$folderName")
        }

        return try {
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { outStream ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, outStream)
                }
                uri
            } else {
                Log.d("ImageSortSelector", "$fileName: URIの発行に失敗")
                null
            }
        } catch (e: Exception) {
            Log.d("ImageSortSelector", "$fileName: 保存失敗 - ${e.message}")
            null
        }
    }

    // 端末内の画像が入っているフォルダ名(バケット名)の一覧を重複なしで取得する
    private fun getAllFolderNames(context: android.content.Context): List<String> {
        val folderSet = mutableSetOf<String>()
        val projection = arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null
        )

        cursor?.use {
            val bucketColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (it.moveToNext()) {
                val bucketName = it.getString(bucketColumn)
                if (bucketName != null) {
                    folderSet.add(bucketName)
                }
            }
        }

        return folderSet.toList().sorted()
    }

    // 選択した画像のRELATIVE_PATHを書き換えて「移動」させる
    // (moveLauncherでMediaStore.createWriteRequestの許可が下りた後に呼ばれる想定)
    private fun moveImagesToFolder(
        context: android.content.Context,
        uris: List<android.net.Uri>,
        folderName: String
    ) {
        val resolver = context.contentResolver
        uris.forEach { uri ->
            val values = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ImageSortSelector/$folderName/")
            }
            try {
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                Log.d("ImageSortSelector", "移動失敗: ${e.message}")
            }
        }
    }
}

// 画像を8x8のグレースケールに縮小し、各ピクセルが平均より明るいか暗いかをビットで表した
// 簡易perceptual hash(average hash)を計算する。見た目が似ている画像ほど、このハッシュの
// ハミング距離(異なるビットの数)が小さくなる
private fun computeAverageHash(bitmap: android.graphics.Bitmap): Long {
    val size = 8
    val resized = android.graphics.Bitmap.createScaledBitmap(bitmap, size, size, true)

    val grays = IntArray(size * size)
    var total = 0
    for (y in 0 until size) {
        for (x in 0 until size) {
            val pixel = resized.getPixel(x, y)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val gray = (r + g + b) / 3
            grays[y * size + x] = gray
            total += gray
        }
    }
    resized.recycle()

    val average = total / (size * size)
    var hash = 0L
    for (i in grays.indices) {
        if (grays[i] >= average) {
            hash = hash or (1L shl i)
        }
    }
    return hash
}

// 2つのpHashの間で異なるビットの数(ハミング距離)を数える。0に近いほど似ている画像
private fun hammingDistance(a: Long, b: Long): Int {
    return java.lang.Long.bitCount(a xor b)
}

// 画像ファイルのEXIFメタデータから撮影日時(秒単位のUnixタイム)を直接読み取る
// MediaStoreのDATE_TAKENが不正確なケース(まとめてインポート/復元された画像など)への対策
private fun readExifDateSeconds(context: android.content.Context, uri: android.net.Uri): Long? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = androidx.exifinterface.media.ExifInterface(stream)
            val dateString = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME)
            dateString?.let {
                val sdf = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.getDefault())
                sdf.parse(it)?.time?.div(1000)
            }
        }
    } catch (e: Exception) {
        Log.d("ImageSortSelector", "EXIF読み取り失敗($uri): ${e.message}")
        null
    }
}

private fun formatFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) "%.1fMB".format(mb) else "%.0fKB".format(kb)
}

private fun formatDate(epochSeconds: Long): String {
    val sdf = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(epochSeconds * 1000))
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