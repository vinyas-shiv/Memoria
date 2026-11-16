package com.youme.memoria.Gallery

import android.database.ContentObserver
import android.os.Handler
import android.util.Log
import com.youme.memoria.MediaScannerHelper

class GalleryObserver(
    handler: Handler
) : ContentObserver(handler) {

    override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        MediaScannerHelper.invalidateCache()
        Log.d("GalleryObserver", "MediaStore changed, cache invalidated")
    }
}
