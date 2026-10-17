package com.quantaedge.android.ui

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
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private enum class ScannerMode { ALL, QR_ONLY, BARCODE_ONLY }
private fun scannerOptions(mode: ScannerMode): BarcodeScannerOptions {
    val builder = BarcodeScannerOptions.Builder()
    return when (mode) {
        ScannerMode.QR_ONLY -> builder.setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        ScannerMode.BARCODE_ONLY -> builder.setBarcodeFormats(
            Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODE_93,
            Barcode.FORMAT_CODABAR, Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
            Barcode.FORMAT_ITF, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E
        ).build()
        ScannerMode.ALL -> builder.setBarcodeFormats(
            Barcode.FORMAT_QR_CODE, Barcode.FORMAT_AZTEC, Barcode.FORMAT_CODABAR,
            Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODE_93,
            Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
            Barcode.FORMAT_ITF, Barcode.FORMAT_PDF417, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E
        ).build()
    }
}

private fun barcodeLabel(format: Int): String = when (format) {
    Barcode.FORMAT_QR_CODE -> "QR code"
    Barcode.FORMAT_CODE_128 -> "Code 128"
    Barcode.FORMAT_CODE_39 -> "Code 39"
    Barcode.FORMAT_CODE_93 -> "Code 93"
    Barcode.FORMAT_CODABAR -> "Codabar"
    Barcode.FORMAT_EAN_13 -> "EAN-13"
    Barcode.FORMAT_EAN_8 -> "EAN-8"
    Barcode.FORMAT_ITF -> "ITF"
    Barcode.FORMAT_UPC_A -> "UPC-A"
    Barcode.FORMAT_UPC_E -> "UPC-E"
    Barcode.FORMAT_AZTEC -> "Aztec"
    Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
    Barcode.FORMAT_PDF417 -> "PDF417"
    else -> "Barcode"
}

private fun Context.unwrapActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

/** Branded on-device QR/barcode scanner; accepted certificate codes are verified server-side. */
@OptIn(ExperimentalGetImage::class)
@Composable
fun PremiumCodeScannerScreen(
    language: String,
    onBack: () -> Unit,
    onCodeScanned: (rawValue: String, format: String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val config = LocalConfiguration.current
    val hindi = language == "hi"
    val landscape = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val scanInk = MaterialTheme.colorScheme.background
    val scanPanel = MaterialTheme.colorScheme.surfaceVariant
    val scanPrimary = MaterialTheme.colorScheme.primary
    val scanOnPrimary = MaterialTheme.colorScheme.onPrimary
    val scanMuted = MaterialTheme.colorScheme.onSurfaceVariant
    val scanText = MaterialTheme.colorScheme.onBackground
    val scanBorder = MaterialTheme.colorScheme.outlineVariant
    // The scan frame sits on black camera preview; preserve a visible accent for very dark brand colours.
    val scanFocus = if (scanPrimary.luminance() < 0.179f) Color.White else scanPrimary
    var mode by rememberSaveable { mutableStateOf(ScannerMode.ALL) }
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var askedForPermission by rememberSaveable { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf("") }
    var torchEnabled by rememberSaveable { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var retryToken by remember { mutableStateOf(0) }

    val activity = remember(context) { context.unwrapActivity() }
    val currentCallback by rememberUpdatedState(onCodeScanned)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val claimed = remember(mode, retryToken) { AtomicBoolean(false) }
    val scanner = remember(mode, retryToken) { BarcodeScanning.getClient(scannerOptions(mode)) }
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            keepScreenOn = true
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        askedForPermission = true
        cameraError = ""
    }
    val permanentlyDenied = askedForPermission && !permissionGranted &&
        activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) != true

    DisposableEffect(executor) { onDispose { executor.shutdown() } }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(camera, torchEnabled) {
        camera?.takeIf { it.cameraInfo.hasFlashUnit() }?.let { runCatching { it.cameraControl.enableTorch(torchEnabled) } }
    }

    DisposableEffect(permissionGranted, mode, retryToken, lifecycleOwner, previewView, scanner, executor) {
        if (!permissionGranted) {
            camera = null
            onDispose { scanner.close() }
        } else {
            var disposed = false
            var provider: ProcessCameraProvider? = null
            var analysis: ImageAnalysis? = null
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (disposed) return@addListener
                try {
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analyzer = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis = analyzer
                    analyzer.setAnalyzer(executor) { frame ->
                        if (claimed.get()) {
                            frame.close()
                            return@setAnalyzer
                        }
                        val mediaImage = frame.image
                        if (mediaImage == null) {
                            frame.close()
                            return@setAnalyzer
                        }
                        try {
                            scanner.process(InputImage.fromMediaImage(mediaImage, frame.imageInfo.rotationDegrees))
                                .addOnSuccessListener { items ->
                                    val hit = items.firstOrNull { !it.rawValue.isNullOrBlank() }
                                    val raw = hit?.rawValue?.trim()
                                    if (hit != null && !raw.isNullOrBlank() && claimed.compareAndSet(false, true)) {
                                        currentCallback(raw, barcodeLabel(hit.format))
                                    }
                                }
                                .addOnFailureListener { }
                                .addOnCompleteListener { frame.close() }
                        } catch (_: Exception) {
                            frame.close()
                        }
                    }
                    cameraProvider.unbindAll()
                    camera = cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)
                    cameraError = ""
                    if (camera?.cameraInfo?.hasFlashUnit() != true) torchEnabled = false
                    else runCatching { camera?.cameraControl?.enableTorch(torchEnabled) }
                } catch (_: Exception) {
                    camera = null
                    cameraError = if (hindi) "कैमरा शुरू नहीं हो पाया। दूसरे ऐप में कैमरा बंद करके फिर कोशिश करें।"
                    else "The camera could not start. Close other camera apps and try again."
                }
            }, ContextCompat.getMainExecutor(context))
            onDispose {
                disposed = true
                analysis?.clearAnalyzer()
                provider?.unbindAll()
                scanner.close()
            }
        }
    }

    val sweep = rememberInfiniteTransition(label = "qe-scan-line")
    val lineY by sweep.animateFloat(
        0.08f, 0.92f,
        animationSpec = infiniteRepeatable(tween(1750, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "qe-scan-line-position"
    )
    val frameHeight = if (landscape) 148.dp else (config.screenHeightDp.dp - 410.dp).coerceIn(220.dp, 350.dp)
    val layoutModifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = if (landscape) 14.dp else 20.dp, vertical = 10.dp)
    Surface(Modifier.fillMaxSize(), color = scanInk) {
        Column(
            layoutModifier.then(if (landscape) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            verticalArrangement = Arrangement.spacedBy(if (landscape) 8.dp else 13.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Surface(color = scanPanel, shape = CircleShape) {
                    TextButton(onClick = onBack, modifier = Modifier.size(46.dp), contentPadding = PaddingValues(0.dp)) {
                        Text("×", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Light)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(if (hindi) "स्मार्ट स्कैन" else "SMART SCAN", color = scanFocus, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                Text(if (hindi) "QR और बारकोड स्कैन करें" else "Scan QR & barcodes", color = scanText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (!landscape) Text(
                    if (hindi) "प्रमाणपत्र का कोड फ्रेम में रखें। उसकी स्थिति QuantaEdge सर्वर से सत्यापित होगी।"
                    else "Align a certificate code inside the frame. Its status is verified securely with QuantaEdge.",
                    color = scanMuted, style = MaterialTheme.typography.bodySmall
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ScannerMode.ALL, ScannerMode.QR_ONLY, ScannerMode.BARCODE_ONLY).forEach { option ->
                    val label = when (option) {
                        ScannerMode.ALL -> if (hindi) "सभी कोड" else "Auto detect"
                        ScannerMode.QR_ONLY -> "QR"
                        ScannerMode.BARCODE_ONLY -> if (hindi) "बारकोड" else "Barcode"
                    }
                    if (mode == option) Button(
                        onClick = { mode = option; cameraError = "" },
                        modifier = Modifier.weight(1f).height(40.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = scanPrimary, contentColor = scanOnPrimary)
                    ) { Text(label, fontSize = 13.sp, maxLines = 1) }
                    else OutlinedButton(
                        onClick = { mode = option; cameraError = "" },
                        modifier = Modifier.weight(1f).height(40.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        border = BorderStroke(1.dp, scanBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) { Text(label, fontSize = 13.sp, maxLines = 1) }
                }
            }
            if (!permissionGranted) {
                Card(Modifier.fillMaxWidth().height(frameHeight), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = scanPanel)) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("▦", color = ScanOrange, fontSize = 48.sp, fontWeight = FontWeight.Light)
                        Text(if (hindi) "कैमरा अनुमति चाहिए" else "Camera access needed", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (hindi) "QR या बारकोड पढ़ने के लिए QuantaEdge को कैमरा अनुमति दें। तस्वीरें सेव या अपलोड नहीं होतीं।"
                            else "Allow camera access to read QR codes and barcodes. Images are not saved or uploaded.",
                            color = ScanMuted, style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = {
                                if (permanentlyDenied) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
                                else { askedForPermission = true; permissionLauncher.launch(Manifest.permission.CAMERA) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = ScanOrange, contentColor = Color.White)
                        ) { Text(if (permanentlyDenied) { if (hindi) "सेटिंग्स खोलें" else "Open settings" } else { if (hindi) "कैमरा चालू करें" else "Enable camera" }) }
                    }
                }
            } else {
                Box(
                    Modifier.fillMaxWidth().height(frameHeight).clip(RoundedCornerShape(28.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(28.dp)).background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxWidth(if (landscape) 0.70f else 0.78f).height(if (landscape) 120.dp else 210.dp).align(Alignment.Center)) {
                        Canvas(Modifier.fillMaxSize()) {
                            val s = 3.dp.toPx()
                            val c = 30.dp.toPx()
                            val i = s / 2f
                            val l = i; val t = i; val r = size.width - i; val b = size.height - i
                            val orange = scanFocus
                            drawLine(orange, androidx.compose.ui.geometry.Offset(l, t), androidx.compose.ui.geometry.Offset(l+c, t), s, cap = StrokeCap.Round)
                            drawLine(orange, androidx.compose.ui.geometry.Offset(l, t), androidx.compose.ui.geometry.Offset(l, t+c), s, cap = StrokeCap.Round)
                            drawLine(orange, androidx.compose.ui.geometry.Offset(r-c, t), androidx.compose.ui.geometry.Offset(r, t), s, cap = StrokeCap.Round)
                            drawLine(orange, androidx.compose.ui.geometry.Offset(r, t), androidx.compose.ui.geometry.Offset(r, t+c), s, cap = StrokeCap.Round)
                            drawLine(orange, androidx.compose.ui.geometry.Offset(l, b), androidx.compose.ui.geometry.Offset(l+c, b), s, cap = StrokeCap.Round)
                            drawLine(orange, androidx.compose.ui.geometry.Offset(l, b-c), androidx.compose.ui.geometry.Offset(l, b), s, cap = StrokeCap.Round)
                            drawLine(orange, androidx.compose.ui.geometry.Offset(r-c, b), androidx.compose.ui.geometry.Offset(r, b), s, cap = StrokeCap.Round)
                            drawLine(orange, androidx.compose.ui.geometry.Offset(r, b-c), androidx.compose.ui.geometry.Offset(r, b), s, cap = StrokeCap.Round)
                            val y = size.height * lineY
                            drawLine(orange.copy(alpha = 0.25f), androidx.compose.ui.geometry.Offset(12.dp.toPx(), y), androidx.compose.ui.geometry.Offset(size.width-12.dp.toPx(), y), 9.dp.toPx(), cap = StrokeCap.Round)
                            drawLine(Color(0xFFFFC28A), androidx.compose.ui.geometry.Offset(12.dp.toPx(), y), androidx.compose.ui.geometry.Offset(size.width-12.dp.toPx(), y), 2.dp.toPx(), cap = StrokeCap.Round)
                        }
                    }
                    Surface(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp), color = Color.Black.copy(alpha = 0.66f), contentColor = Color.White, shape = RoundedCornerShape(50)) {
                        Text(if (hindi) "कोड को फ्रेम के भीतर रखें" else "Keep the code inside the frame", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = Color.White, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (cameraError.isNotBlank()) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer), shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(cameraError, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { retryToken += 1 }) { Text(if (hindi) "फिर कोशिश" else "Retry", color = MaterialTheme.colorScheme.onErrorContainer) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { torchEnabled = !torchEnabled },
                    enabled = camera?.cameraInfo?.hasFlashUnit() == true,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text(if (torchEnabled) { if (hindi) "टॉर्च चालू" else "Flash on" } else { if (hindi) "टॉर्च" else "Flashlight" }) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (hindi) "कैमरा आपके डिवाइस पर ही रहता है" else "Camera stays on your device", color = scanText, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Text(if (hindi) "केवल मान्य प्रमाणपत्र कोड सत्यापन के लिए भेजा जाता है।" else "Only an accepted certificate code is sent for verification.", color = ScanMuted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
