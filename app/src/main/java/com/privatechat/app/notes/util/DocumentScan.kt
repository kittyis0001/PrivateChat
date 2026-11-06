package com.privatechat.app.notes.util

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * ML Kit document scanner (Play-services based, no model in the APK).
 *
 * API verified against Google's ML Kit docs
 * (developers.google.com/ml-kit/vision/doc-scanner/android):
 * the client comes from [GmsDocumentScanning.getClient], the result from
 * [GmsDocumentScanningResult.fromActivityResultIntent], and
 * [GmsDocumentScanner.getStartScanIntent] returns a Task — the scanner
 * intent is launched from its success listener, never synchronously.
 */
object DocumentScan {

    fun createScanner(activity: AppCompatActivity): GmsDocumentScanner {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(10)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        return GmsDocumentScanning.getClient(options)
    }

    /**
     * Starts the scanner UI. The scan result arrives through [launcher];
     * [onError] runs when the scanner cannot start (e.g. the Play
     * services model is not ready yet) — the caller shows a toast then.
     */
    fun launch(
        activity: Activity,
        scanner: GmsDocumentScanner,
        launcher: ActivityResultLauncher<IntentSenderRequest>,
        onError: (Exception) -> Unit
    ) {
        scanner.getStartScanIntent(activity)
            .addOnSuccessListener { intentSender ->
                launcher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { e -> onError(e) }
    }

    /** The scanned PDF's Uri, or null when the scan was cancelled/failed. */
    fun pdfUriFromResult(data: Intent?): Uri? = runCatching {
        GmsDocumentScanningResult.fromActivityResultIntent(data)?.pdf?.uri
    }.getOrNull()

    /** The scanned pages' JPEG Uris (one per page), for OCR. Empty when cancelled/failed. */
    fun pageUrisFromResult(data: Intent?): List<Uri> = runCatching {
        GmsDocumentScanningResult.fromActivityResultIntent(data)?.pages
            ?.mapNotNull { it.imageUri } ?: emptyList()
    }.getOrDefault(emptyList())
}
