package org.openstrap.app.ui.glow

import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The share screen's photo: a fresh pick reads as Loading until its own answer arrives, so Save never exports without it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharePhotoTest {
    private val first = Uri.parse("content://photos/1")
    private val second = Uri.parse("content://photos/2")
    private val bitmap = ImageBitmap(1, 1)

    @Test fun `no pick is None whatever was decoded before`() {
        assertEquals(SharePhoto.None, sharePhotoFor(null, SharePhoto.Ready(first, bitmap)))
    }

    @Test fun `a pick waits as Loading until its own answer comes`() {
        assertEquals(SharePhoto.Loading, sharePhotoFor(first, SharePhoto.None))
        assertEquals(SharePhoto.Loading, sharePhotoFor(second, SharePhoto.Ready(first, bitmap)))
        assertEquals(SharePhoto.Loading, sharePhotoFor(second, SharePhoto.Failed(first)))
    }

    @Test fun `the answer for the picked photo is shown, ready or failed`() {
        val ready = SharePhoto.Ready(first, bitmap)
        assertEquals(ready, sharePhotoFor(first, ready))
        assertEquals(SharePhoto.Failed(second), sharePhotoFor(second, SharePhoto.Failed(second)))
    }
}
