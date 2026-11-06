package com.privatechat.app.notes.util

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerResult

/** ML Kit document scanner (Play-services based, no model in the APK). */
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
        return GmsDocumentScanner.getClient(options)
    }

    fun launch(
        activity: Activity,
        scanner: GmsDocumentScanner,
        launcher: ActivityResultLauncher<IntentSenderRequest>
    ) {
        launcher.launch(
            IntentSenderRequest.Builder(scanner.getStartScanIntent(activity)).build()
        )
    }

    /** The scanned PDF's Uri, or null when the scan was cancelled/failed. */
    fun pdfUriFromResult(data: Intent?): Uri? = runCatching {
        GmsDocumentScannerResult.fromActivityResultIntent(data)?.pdf?.uri
    }.getOrNull()
}
