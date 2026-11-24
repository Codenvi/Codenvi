package app.codenvi.ui.components

import android.app.Activity
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** A scanned QR code is only accepted when it is a Codenvi connection string. */
fun isCodenviString(text: String?): Boolean = text != null && text.trim().startsWith("codenvi://", ignoreCase = true)

/**
 * Opens Google's scanner screen (Play Services; no CAMERA permission in this app) and returns the QR text.
 * Cancelling is silent. [onError] gets a message the UI can show.
 */
fun scanQr(activity: Activity, onResult: (String) -> Unit, onError: (String) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    GmsBarcodeScanning.getClient(activity, options).startScan()
        .addOnSuccessListener { barcode ->
            val text = barcode.rawValue
            if (isCodenviString(text)) onResult(text!!.trim()) else onError("That QR code is not a Codenvi connection string.")
        }
        .addOnCanceledListener { }
        .addOnFailureListener { onError("Scanner unavailable on this phone. Paste the string instead.") }
}
