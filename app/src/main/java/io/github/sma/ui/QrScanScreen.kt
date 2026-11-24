package io.github.sma.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.github.sma.core.crypto.QrApproval
import io.github.sma.core.steam.userMessage
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val NOT_STEAM_HINT = "Not a Steam sign-in QR code"
private const val HINT_DURATION_MS = 2_500L
private const val FRAME_SIZE_FRACTION = 0.7f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScanScreen(steamId: String, nav: NavController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewView = remember(context) { PreviewView(context) }

    var hasPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    var permissionDenied by remember { mutableStateOf(false) }
    var canAskAgain by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        permissionDenied = !granted
        // After a denial, rationale is true only if the system still allows asking again.
        canAskAgain = context.findActivity()
            ?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true
    }

    LaunchedEffect(Unit) {
        if (!context.hasCameraPermission()) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Permission may be granted in system settings while this screen is in the background.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = context.hasCameraPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Transient hint: cleared after a short delay; a new hint restarts the timer.
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(HINT_DURATION_MS)
            hint = null
        }
    }

    DisposableEffect(hasPermission) {
        if (!hasPermission) {
            return@DisposableEffect onDispose {}
        }

        val analysisExecutor = Executors.newSingleThreadExecutor()
        val finished = AtomicBoolean(false)
        val disposed = AtomicBoolean(false)
        var cameraProvider: ProcessCameraProvider? = null
        val reader = QRCodeReader()
        val hints: Map<DecodeHintType, Any> = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        )

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(analysisExecutor, ImageAnalysis.Analyzer { image ->
            try {
                val text = if (finished.get()) null else decodeQr(image, reader, hints)
                if (text != null) {
                    val link = QrApproval.parse(text)
                    if (link == null) {
                        mainExecutor.execute { hint = NOT_STEAM_HINT }
                    } else if (finished.compareAndSet(false, true)) {
                        mainExecutor.execute {
                            cameraProvider?.unbindAll()
                            val currentId = nav.currentDestination?.id
                            nav.navigate(
                                "loginrequests/$steamId?clientId=${link.clientId}&version=${link.version}"
                            ) {
                                if (currentId != null) {
                                    popUpTo(currentId) { inclusive = true }
                                }
                            }
                        }
                    }
                }
            } finally {
                image.close()
            }
        })

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed.get()) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            } catch (e: Exception) {
                cameraError = e.userMessage()
            }
        }, mainExecutor)

        onDispose {
            disposed.set(true)
            finished.set(true)
            analysis.clearAnalyzer()
            cameraProvider?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan sign-in QR") },
                actions = {
                    TextButton(onClick = { nav.popBackStack() }) { Text("Cancel") }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (!hasPermission) {
                CameraPermissionPrompt(
                    permissionDenied = permissionDenied,
                    canAskAgain = canAskAgain,
                    onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = { context.openAppSettings() },
                )
            } else if (cameraError != null) {
                Text(
                    text = cameraError.orEmpty(),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    textAlign = TextAlign.Center,
                )
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val frameSide = minOf(maxWidth, maxHeight) * FRAME_SIZE_FRACTION
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier.fillMaxSize(),
                    )
                    ScanFrame(modifier = Modifier.fillMaxSize())
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            // Place the hint 16.dp below the bottom edge of the scan frame.
                            .offset(y = (maxHeight + frameSide) / 2 + 16.dp)
                            // Same width as the scan frame instead of edge to edge.
                            .widthIn(max = frameSide),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = "Point the camera at the QR code on the Steam sign-in page",
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    hint?.let { message ->
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(24.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Text(
                                text = message,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dimmed scrim with a centered square cut-out and primary-coloured L-shaped corner markers.
 * Draws only; it has no pointer input, so touches reach the camera preview underneath.
 */
@Composable
private fun ScanFrame(modifier: Modifier = Modifier.fillMaxSize()) {
    val primary = MaterialTheme.colorScheme.primary
    val scrim = Color.Black.copy(alpha = 0.45f)
    Canvas(modifier = modifier) {
        val side = minOf(size.width, size.height) * FRAME_SIZE_FRACTION
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val right = left + side
        val bottom = top + side

        // Dim the four areas around the square.
        drawRect(scrim, Offset.Zero, Size(size.width, top))
        drawRect(scrim, Offset(0f, bottom), Size(size.width, size.height - bottom))
        drawRect(scrim, Offset(0f, top), Size(left, side))
        drawRect(scrim, Offset(right, top), Size(size.width - right, side))

        val strokeWidth = 4.dp.toPx()
        val arm = 28.dp.toPx()
        // Each corner with the direction (+1/-1) its arms extend into the square.
        val corners = listOf(
            Triple(Offset(left, top), 1f, 1f),
            Triple(Offset(right, top), -1f, 1f),
            Triple(Offset(left, bottom), 1f, -1f),
            Triple(Offset(right, bottom), -1f, -1f),
        )
        corners.forEach { (corner, dx, dy) ->
            drawLine(
                primary,
                corner,
                Offset(corner.x + dx * arm, corner.y),
                strokeWidth,
                StrokeCap.Round,
            )
            drawLine(
                primary,
                corner,
                Offset(corner.x, corner.y + dy * arm),
                strokeWidth,
                StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun CameraPermissionPrompt(
    permissionDenied: Boolean,
    canAskAgain: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (permissionDenied) {
                "Camera permission is required to scan the sign-in QR code."
            } else {
                "Allow camera access to scan the sign-in QR code."
            },
            textAlign = TextAlign.Center,
        )
        if (permissionDenied) {
            Text(
                text = "The camera is used only to read the QR code on this screen. " +
                    "Grant the permission to continue, or open app settings to enable it.",
                modifier = Modifier.padding(top = 12.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!permissionDenied || canAskAgain) {
            Button(onClick = onRequest, modifier = Modifier.padding(top = 24.dp)) {
                Text("Allow camera")
            }
        }
        if (permissionDenied) {
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.padding(top = 12.dp)) {
                Text("Open app settings")
            }
        }
    }
}

/** Reads the Y (luminance) plane of a YUV_420_888 frame and decodes a QR code from it. */
private fun decodeQr(image: ImageProxy, reader: QRCodeReader, hints: Map<DecodeHintType, Any>): String? {
    val width = image.width
    val height = image.height
    val plane = image.planes[0]
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val luminance = ByteArray(width * height)

    if (pixelStride == 1 && rowStride == width) {
        buffer.position(0)
        buffer.get(luminance, 0, width * height)
    } else if (pixelStride == 1) {
        for (row in 0 until height) {
            buffer.position(row * rowStride)
            buffer.get(luminance, row * width, width)
        }
    } else {
        for (row in 0 until height) {
            val rowOffset = row * rowStride
            for (col in 0 until width) {
                luminance[row * width + col] = buffer.get(rowOffset + col * pixelStride)
            }
        }
    }

    val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    return try {
        reader.decode(bitmap, hints).text
    } catch (e: ReaderException) {
        null
    } finally {
        reader.reset()
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
