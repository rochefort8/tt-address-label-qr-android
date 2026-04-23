package com.ttqr.android

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect as AndroidRect
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ttqr.android.ui.QrScannerPreview
import com.ttqr.android.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private data class DetectedQrRow(
    val id: String,
    val name: String,
    val raw: String,
    val encodedId: String,
)

private enum class ScanTab(val title: String) {
    Scan("読み取り"),
    List("一覧"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QrScannerApp()
        }
    }
}

@Composable
private fun QrScannerApp() {
    val context = LocalContext.current
    var hasCameraPermission by rememberSaveable {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var scannedText by rememberSaveable { mutableStateOf<String?>(null) }
    var detectedQrBounds by remember { mutableStateOf<AndroidRect?>(null) }
    var selectedTabIndex by rememberSaveable { mutableStateOf(0) }
    var detectedRows by remember { mutableStateOf<List<DetectedQrRow>>(emptyList()) }
    var listRows by remember { mutableStateOf<List<DetectedQrRow>>(emptyList()) }
    var highlightedRaw by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    var isRefreshingListIds by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    fun refreshListIds() {
        if (isRefreshingListIds) {
            return
        }
        coroutineScope.launch {
            isRefreshingListIds = true
            val result = runCatching { fetchNewsletterUnreachableUserIds() }
            isRefreshingListIds = false
            result.onSuccess { ids ->
                listRows = ids.map { id ->
                    DetectedQrRow(
                        id = id,
                        name = "",
                        raw = "list:$id",
                        encodedId = "",
                    )
                }
                Toast.makeText(
                    context,
                    "IDを取得しました (${ids.size}件)",
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    "ID取得失敗: ${error.message ?: "unknown error"}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(selectedTabIndex) {
        if (ScanTab.entries[selectedTabIndex] == ScanTab.List) {
            refreshListIds()
        }
    }

    AppTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .background(Color.Black),
            ) {
                ScanListTabBar(
                    selectedTabIndex = selectedTabIndex,
                    onTabSelected = { selectedTabIndex = it },
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    when (ScanTab.entries[selectedTabIndex]) {
                        ScanTab.Scan -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f),
                                ) {
                                    if (hasCameraPermission) {
                                        QrScannerPreview(
                                            modifier = Modifier.fillMaxSize(),
                                            scanWindow = null,
                                            scanningEnabled = true,
                                            onQrDetected = { value, bounds ->
                                                detectedQrBounds = bounds
                                                val decrypted = decryptEncryptedQrPayload(value)
                                                scannedText = decrypted
                                                val row = decrypted?.toDetectedRow(encodedId = value)
                                                if (row != null) {
                                                    highlightedRaw = row.raw
                                                    if (detectedRows.none { it.raw == row.raw }) {
                                                        detectedRows = detectedRows + row
                                                    }
                                                }
                                            },
                                            onQrTracking = { bounds ->
                                                detectedQrBounds = bounds
                                                if (bounds == null) {
                                                    scannedText = null
                                                    highlightedRaw = null
                                                }
                                            },
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Transparent)
                                            .drawBehind {
                                                detectedQrBounds?.let { bounds ->
                                                    drawRect(
                                                        color = Color.White.copy(alpha = 0.9f),
                                                        topLeft = androidx.compose.ui.geometry.Offset(
                                                            x = bounds.left.toFloat(),
                                                            y = bounds.top.toFloat(),
                                                        ),
                                                        size = androidx.compose.ui.geometry.Size(
                                                            width = bounds.width().toFloat(),
                                                            height = bounds.height().toFloat(),
                                                        ),
                                                        style = Stroke(width = 8.dp.toPx()),
                                                    )
                                                    drawRect(
                                                        color = Color(0xFF00FF66),
                                                        topLeft = androidx.compose.ui.geometry.Offset(
                                                            x = bounds.left.toFloat(),
                                                            y = bounds.top.toFloat(),
                                                        ),
                                                        size = androidx.compose.ui.geometry.Size(
                                                            width = bounds.width().toFloat(),
                                                            height = bounds.height().toFloat(),
                                                        ),
                                                        style = Stroke(width = 5.dp.toPx()),
                                                    )
                                                }
                                            },
                                    )

                                    if (!hasCameraPermission) {
                                        Button(
                                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                                            modifier = Modifier.align(Alignment.Center),
                                        ) {
                                            Text("Grant Camera Permission")
                                        }
                                    }

                                    scannedText?.let { value ->
                                        Text(
                                            text = value,
                                            color = Color.White,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier
                                                .align(Alignment.TopCenter)
                                                .padding(top = 12.dp)
                                                .background(
                                                    color = Color.Black.copy(alpha = 0.6f),
                                                    shape = RectangleShape,
                                                )
                                                .padding(horizontal = 16.dp, vertical = 10.dp),
                                        )
                                    }
                                }

                                DetectedListTable(
                                    rows = detectedRows,
                                    highlightedRaw = highlightedRaw,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                )

                                ScanActionBar(
                                    enabled = detectedRows.isNotEmpty() && !isSending,
                                    onSendClick = {
                                        coroutineScope.launch {
                                            val userIds = detectedRows.map { it.id }.distinct()
                                            val encodedUserIds = detectedRows.map { it.encodedId }.filter { it.isNotBlank() }.distinct()
                                            isSending = true
                                            val result = runCatching {
                                                registerNewsletterUnreachable(userIds)
                                                postEncodedUserIdsToSpreadsheet(encodedUserIds)
                                            }
                                            isSending = false

                                            result.onSuccess {
                                                Toast.makeText(
                                                    context,
                                                    "IDを送信しました (${userIds.size}件)",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }.onFailure { error ->
                                                Toast.makeText(
                                                    context,
                                                    "送信失敗: ${error.message ?: "unknown error"}",
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            }
                                        }
                                    },
                                    onDeleteClick = { showDeleteDialog = true },
                                )
                            }
                        }

                        ScanTab.List -> {
                            DetectedListTable(
                                rows = listRows,
                                refreshing = isRefreshingListIds,
                                onRefresh = { refreshListIds() },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }

            if (showDeleteDialog) {
                AlertDialog(
                    onDismissRequest = { showDeleteDialog = false },
                    title = { Text(text = "確認") },
                    text = { Text(text = "リスト項目をすべて削除します。よろしいですか？") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                detectedRows = emptyList()
                                highlightedRaw = null
                                scannedText = null
                                detectedQrBounds = null
                                showDeleteDialog = false
                            },
                        ) {
                            Text("Yes")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteDialog = false }) {
                            Text("No")
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ScanListTabBar(
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
) {
    TabRow(
        selectedTabIndex = selectedTabIndex,
        containerColor = Color(0xFFB3151C),
        contentColor = Color.White,
        divider = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.45f)),
            )
        },
        indicator = { positions ->
            androidx.compose.material3.TabRowDefaults.Indicator(
                modifier = Modifier.tabIndicatorOffset(positions[selectedTabIndex]),
                height = 3.dp,
                color = Color.White,
            )
        },
    ) {
        ScanTab.entries.forEachIndexed { index, tab ->
            val selected = selectedTabIndex == index
            Tab(
                selected = selected,
                onClick = { onTabSelected(index) },
                selectedContentColor = Color.White,
                unselectedContentColor = Color.White.copy(alpha = 0.65f),
                modifier = Modifier
                    .height(48.dp)
                    .background(Color(0xFFB3151C)),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(
                                id = if (tab == ScanTab.Scan) {
                                    android.R.drawable.ic_menu_camera
                                } else {
                                    android.R.drawable.ic_menu_agenda
                                },
                            ),
                            contentDescription = tab.title,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = tab.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                        )
                    }
                    if (index < ScanTab.entries.lastIndex) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(1.dp)
                                .fillMaxHeight(0.65f)
                                .background(Color.White.copy(alpha = 0.55f)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanActionBar(
    enabled: Boolean,
    onSendClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(Color(0xFF111111))
            .border(1.dp, Color.White.copy(alpha = 0.25f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = onSendClick,
                enabled = enabled,
            ) {
                Icon(
                    painter = painterResource(id = android.R.drawable.ic_menu_send),
                    contentDescription = "送信",
                    tint = if (enabled) Color.White else Color.Gray,
                )
            }
        }
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = onDeleteClick,
                enabled = enabled,
            ) {
                Icon(
                    painter = painterResource(id = android.R.drawable.ic_menu_delete),
                    contentDescription = "削除",
                    tint = if (enabled) Color.White else Color.Gray,
                )
            }
        }
    }
}

@Composable
private fun DetectedListTable(
    rows: List<DetectedQrRow>,
    highlightedRaw: String? = null,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val refresh = onRefresh
    if (refresh == null) {
        Column(
            modifier = modifier
                .background(Color.Black),
        ) {
            DetectedListTableContent(
                rows = rows,
                highlightedRaw = highlightedRaw,
            )
        }
    } else {
        PullRefreshDetectedListTable(
            rows = rows,
            highlightedRaw = highlightedRaw,
            refreshing = refreshing,
            onRefresh = refresh,
            modifier = modifier,
        )
    }
}

@Composable
private fun DetectedListTableContent(
    rows: List<DetectedQrRow>,
    highlightedRaw: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = "ID",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "姓名",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            modifier = Modifier.weight(2f),
        )
    }

    if (rows.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "項目がありません",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 4.dp),
        ) {
            items(rows) { row ->
                val isHighlighted = highlightedRaw == row.raw
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isHighlighted) Color(0xFFB3151C) else Color.Transparent,
                        )
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                ) {
                    Text(
                        text = row.id,
                        color = Color.White,
                        fontSize = 20.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = row.name,
                        color = Color.White,
                        fontSize = 20.sp,
                        modifier = Modifier.weight(2f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun PullRefreshDetectedListTable(
    rows: List<DetectedQrRow>,
    highlightedRaw: String?,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pullRefreshState = rememberPullRefreshState(
        refreshing = refreshing,
        onRefresh = onRefresh,
    )
    Box(
        modifier = modifier
            .background(Color.Black)
            .pullRefresh(pullRefreshState),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            DetectedListTableContent(
                rows = rows,
                highlightedRaw = highlightedRaw,
            )
        }
        PullRefreshIndicator(
            refreshing = refreshing,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter),
            backgroundColor = Color.Black,
            contentColor = Color.White,
        )
    }
}

private fun String.toDetectedRow(encodedId: String): DetectedQrRow? {
    val parts = split(",", limit = 2)
    if (parts.size != 2) {
        return null
    }
    val id = parts[0].trim()
    val name = parts[1].trim()
    if (id.isEmpty() || name.isEmpty()) {
        return null
    }
    return DetectedQrRow(
        id = id,
        name = name,
        raw = this,
        encodedId = encodedId,
    )
}

private fun decryptEncryptedQrPayload(raw: String): String? {
    val parts = raw.split(":")
    if (parts.size != 3 || parts[0] != "v1") {
        return null
    }

    return try {
        val keyHex = BuildConfig.QR_DECRYPT_KEY_HEX.trim()
        if (keyHex.length != 64 || keyHex.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) {
            return null
        }
        val keyBytes = keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val nonce = Base64.decode(parts[1], Base64.DEFAULT)
        val cipherTag = Base64.decode(parts[2], Base64.DEFAULT)
        if (nonce.size != 12 || cipherTag.size <= 16) {
            return null
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val spec = GCMParameterSpec(128, nonce)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        val decrypted = cipher.doFinal(cipherTag)
        String(decrypted, Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }
}

private suspend fun registerNewsletterUnreachable(userIds: List<String>) {
    if (userIds.isEmpty()) {
        return
    }

    val apiKey = BuildConfig.NEWSLETTER_API_KEY.trim()
    if (apiKey.isEmpty()) {
        throw IOException("newsletter.api.key is missing in local.properties")
    }

    withContext(Dispatchers.IO) {
        val baseEndpoint = BuildConfig.NEWSLETTER_API_ENDPOINT.trim()
        if (baseEndpoint.isEmpty()) {
            throw IOException("newsletter.api.endpoint is missing in local.properties")
        }
        val endpoint = "${baseEndpoint.trimEnd('/')}/register"

        val connection = (URL(endpoint).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-API-Key", apiKey)

            val payload = JSONObject().put("user_ids", JSONArray(userIds))
            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                throw IOException("HTTP $responseCode ${errorBody.take(200)}".trim())
            }
        } finally {
            connection.disconnect()
        }
    }
}

private suspend fun fetchNewsletterUnreachableUserIds(): List<String> {
    val apiKey = BuildConfig.NEWSLETTER_API_KEY.trim()
    if (apiKey.isEmpty()) {
        throw IOException("newsletter.api.key is missing in local.properties")
    }
    val baseEndpoint = BuildConfig.NEWSLETTER_API_ENDPOINT.trim()
    if (baseEndpoint.isEmpty()) {
        throw IOException("newsletter.api.endpoint is missing in local.properties")
    }
    val endpoint = "${baseEndpoint.trimEnd('/')}/user_ids"

    return withContext(Dispatchers.IO) {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("X-API-Key", apiKey)

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                throw IOException("HTTP $responseCode ${errorBody.take(200)}".trim())
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }.trim()
            parseUserIdsResponse(body)
        } finally {
            connection.disconnect()
        }
    }
}

private suspend fun postEncodedUserIdsToSpreadsheet(encodedUserIds: List<String>) {
    if (encodedUserIds.isEmpty()) {
        return
    }

    val apiKey = BuildConfig.NEWSLETTER_SPREADSHET_API_KEY.trim()
    if (apiKey.isEmpty()) {
        throw IOException("newsletter.spreadshet.api.key is missing in local.properties")
    }

    val baseEndpoint = BuildConfig.NEWSLETTER_SPREADSHET_API_ENDPOINT.trim()
    if (baseEndpoint.isEmpty()) {
        throw IOException("newsletter.spreadshet.api.endpoint is missing in local.properties")
    }
    val endpoint = "${baseEndpoint.trimEnd('/')}/"

    withContext(Dispatchers.IO) {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-API-Key", apiKey)

            val payload = JSONObject().put("encoded_user_ids", JSONArray(encodedUserIds))
            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                throw IOException("HTTP $responseCode ${errorBody.take(200)}".trim())
            }
        } finally {
            connection.disconnect()
        }
    }
}

private fun parseUserIdsResponse(body: String): List<String> {
    if (body.isEmpty()) {
        return emptyList()
    }
    if (body.startsWith("[")) {
        return jsonArrayToIds(JSONArray(body))
    }

    val json = JSONObject(body)
    val arr = json.optJSONArray("user_ids")
        ?: json.optJSONArray("ids")
        ?: json.optJSONObject("data")?.optJSONArray("user_ids")
        ?: json.optJSONObject("data")?.optJSONArray("ids")
    return arr?.let { jsonArrayToIds(it) } ?: emptyList()
}

private fun jsonArrayToIds(arr: JSONArray): List<String> {
    val ids = mutableListOf<String>()
    for (index in 0 until arr.length()) {
        val id = arr.optString(index).trim()
        if (id.isNotEmpty()) {
            ids += id
        }
    }
    return ids
}
