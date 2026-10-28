package com.growthinternal.app;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34)
public class PhotosTest {
    @Test public void avatarCircleUsesCacheAndDecodesPhoto() {
        Bitmap a1 = Avatars.circle("Andi", null, 48);
        Bitmap a2 = Avatars.circle("Andi", null, 48);
        Bitmap b = Avatars.circle("Budi", null, 48);
        assertSame(a1, a2);
        assertNotSame(a1, b);

        byte[] jpeg = ScreenshotTest.sampleJpeg();
        Bitmap small = Photos.decode(jpeg, 160);
        assertNotNull(small);
        assertTrue(small.getWidth() <= 320 && small.getHeight() <= 320);

        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        Bitmap.createScaledBitmap(small, Avatars.PHOTO_PX, Avatars.PHOTO_PX, true)
                .compress(Bitmap.CompressFormat.JPEG, 75, bos);
        byte[] avatarBytes = bos.toByteArray();
        Bitmap p1 = Avatars.circle("Andi", avatarBytes, 64);
        Bitmap p2 = Avatars.circle("Andi", avatarBytes, 64);
        assertSame(p1, p2);
        assertEquals(64, p1.getWidth());
    }

    @Test public void photosDecodeRejectsInvalidInput() {
        assertNull(Photos.decode(null, 720));
        assertNull(Photos.decode(new byte[0], 720));
        assertNull(Photos.decode(new byte[] {1, 2, 3, 4}, 720));
    }
}
