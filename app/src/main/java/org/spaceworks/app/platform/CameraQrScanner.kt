package org.spaceworks.app.platform

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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

/** One scan per review step. A new sessionKey creates a fresh gate for an explicit "Scan again" action. */
class QrScanGate {
    private val claimed = AtomicBoolean()
    fun accept(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 4096 } ?: return null
        return value.takeIf { claimed.compareAndSet(false, true) }
    }
}

/** CameraX + bundled ML Kit, QR-only. Scanning resolves a value; it never confirms an issue/check-in. */
@Composable
fun CameraQrScanner(
    onCode: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    sessionKey: Any = Unit,
    enabled: Boolean = true,
) {
    val text = rememberMobileText()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var permission by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var failure by remember(sessionKey, attempt) { mutableStateOf<String?>(null) }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission = it; denied = !it
    }
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permission = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when {
            !enabled -> Text(text(CameraStrings.CAMERA_PAUSED))
            !permission -> {
                Text(if (denied) text(CameraStrings.CAMERA_DENIED) else text(CameraStrings.CAMERA_ALLOW_EXPLANATION))
                Button(onClick = {
                    if (denied) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    else permissionRequest.launch(Manifest.permission.CAMERA)
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (denied) text(CameraStrings.CAMERA_SETTINGS) else text(CameraStrings.CAMERA_ALLOW)) }
            }
            failure != null -> {
                Text(failure.orEmpty())
                Button(onClick = { attempt++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text(text(CameraStrings.CAMERA_RETRY)) }
            }
            else -> {
                Text(text(CameraStrings.CAMERA_AIM))
                CameraPreview(sessionKey = sessionKey to attempt, onCode = onCode, onError = {
                    failure = it; onError(it)
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 420.dp))
            }
        }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraPreview(sessionKey: Any, onCode: (String) -> Unit, onError: (String) -> Unit, modifier: Modifier) {
    val text = rememberMobileText()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val code = rememberUpdatedState(onCode)
    val error = rememberUpdatedState(onError)
    val previewView = remember(context) { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    val previewDescription=text(CameraStrings.CAMERA_PREVIEW)
    val cameraFailure=text(CameraStrings.CAMERA_FAILURE)
    AndroidView(factory = { previewView }, modifier = modifier.semantics { contentDescription = previewDescription })

    DisposableEffect(context, owner, previewView, sessionKey) {
        val active = AtomicBoolean(true)
        val reportedError = AtomicBoolean()
        val gate = QrScanGate()
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)

        fun fail() {
            if (active.get() && reportedError.compareAndSet(false, true)) error.value(cameraFailure)
        }
        analysis.setAnalyzer(executor) { frame ->
            val image = frame.image
            if (!active.get() || image == null) frame.close()
            else {
                try {
                    scanner.process(InputImage.fromMediaImage(image, frame.imageInfo.rotationDegrees))
                        .addOnSuccessListener(main) { results ->
                            if (active.get()) results.firstNotNullOfOrNull { gate.accept(it.rawValue) }?.let { code.value(it) }
                        }
                        .addOnFailureListener(main) { fail() }
                        .addOnCompleteListener(main) { frame.close() }
                } catch (_: Exception) { frame.close(); main.execute { fail() } }
            }
        }
        future.addListener({
            if (active.get()) {
                try {
                    provider = future.get()
                    val selector = when {
                        provider!!.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                        provider!!.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                        else -> throw PlatformUnavailable("No camera is available.")
                    }
                    provider!!.bindToLifecycle(owner, selector, preview, analysis)
                } catch (_: Exception) { fail() }
            }
        }, main)
        onDispose {
            active.set(false)
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            scanner.close()
            executor.shutdown()
        }
    }
}
