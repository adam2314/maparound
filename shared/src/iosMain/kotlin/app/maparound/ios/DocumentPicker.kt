package app.maparound.ios

import platform.Foundation.NSURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject

/**
 * Shows the Files picker for one file of any kind (the caller checks what it is). The file is
 * copied into the app's temporary folder first; [done] gets its URL, or null when cancelled.
 */
fun pickDocument(done: (NSURL?) -> Unit) {
    val top = topViewController() ?: return done(null)
    val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem), asCopy = true)
    val delegate = PickerDelegate { url ->
        current = null
        done(url)
    }
    current = delegate // the picker holds its delegate weakly
    picker.delegate = delegate
    picker.allowsMultipleSelection = false
    top.presentViewController(picker, animated = true, completion = null)
}

private var current: PickerDelegate? = null

private class PickerDelegate(private val done: (NSURL?) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        done(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        done(null)
    }
}
