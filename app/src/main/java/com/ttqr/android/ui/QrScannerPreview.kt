package com.ttqr.android.ui

import android.annotation.SuppressLint
import android.graphics.Rect
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.google.mlkit.vision.barcode.BarcodeScanning
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun QrScannerPreview(
    modifier: Modifier = Modifier,
    scanWindow: ComposeRect?,
    scanningEnabled: Boolean,
    onQrDetected: (String, Rect) -> Unit,
    onQrTracking: (Rect?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }
    val latestOnQrDetected = rememberUpdatedState(onQrDetected)
    val latestOnQrTracking = rememberUpdatedState(onQrTracking)

    val controller = remember(context, lifecycleOwner) {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            bindToLifecycle(lifecycleOwner)
        }
    }

    DisposableEffect(controller, scanner, executor, scanningEnabled) {
        if (scanningEnabled) {
            controller.setImageAnalysisAnalyzer(
                executor,
                buildAnalyzer(
                    executor = executor,
                    scanner = scanner,
                    scanWindow = scanWindow,
                    onQrDetected = { value, bounds -> latestOnQrDetected.value(value, bounds) },
                    onQrTracking = { bounds -> latestOnQrTracking.value(bounds) },
                ),
            )
        } else {
            controller.clearImageAnalysisAnalyzer()
        }

        onDispose {
            controller.clearImageAnalysisAnalyzer()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            scanner.close()
            executor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            FrameLayout(viewContext).apply {
                clipChildren = true
                clipToPadding = true

                val previewView = PreviewView(viewContext).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    this.controller = controller
                    // Initial center focus to improve first detection stability.
                    post { triggerFocus(controller, this, width / 2f, height / 2f) }
                    setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_UP) {
                            triggerFocus(controller, this, event.x, event.y)
                            true
                        } else {
                            false
                        }
                    }
                }

                addView(
                    previewView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
    )
}

private fun triggerFocus(
    controller: LifecycleCameraController,
    previewView: PreviewView,
    x: Float,
    y: Float,
) {
    val meteringPoint = previewView.meteringPointFactory.createPoint(x, y)
    val action = FocusMeteringAction.Builder(meteringPoint)
        .setAutoCancelDuration(2, TimeUnit.SECONDS)
        .build()
    controller.cameraControl?.startFocusAndMetering(action)
}

@SuppressLint("UnsafeOptInUsageError")
private fun buildAnalyzer(
    executor: ExecutorService,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    scanWindow: ComposeRect?,
    onQrDetected: (String, Rect) -> Unit,
    onQrTracking: (Rect?) -> Unit,
): ImageAnalysis.Analyzer {
    return MlKitAnalyzer(
        listOf(scanner),
        CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED,
        executor,
    ) { result ->
        val barcodeResults = result?.getValue(scanner).orEmpty()
        val matchedBarcode = barcodeResults.firstOrNull { barcode ->
            val bounds = barcode.boundingBox ?: return@firstOrNull false
            scanWindow?.contains(bounds.toComposeRect()) ?: true
        }
        val matchedBounds = matchedBarcode?.boundingBox

        onQrTracking(matchedBounds)

        val firstValue = matchedBarcode?.rawValue
        if (firstValue != null && matchedBounds != null) {
            onQrDetected(firstValue, matchedBounds)
        }
    }
}

private fun ComposeRect.contains(other: ComposeRect): Boolean {
    return other.left >= left &&
        other.top >= top &&
        other.right <= right &&
        other.bottom <= bottom
}

private fun Rect.toComposeRect(): ComposeRect {
    return ComposeRect(
        left = left.toFloat(),
        top = top.toFloat(),
        right = right.toFloat(),
        bottom = bottom.toFloat(),
    )
}
