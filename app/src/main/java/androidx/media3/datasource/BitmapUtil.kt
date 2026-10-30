package androidx.media3.datasource

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.C
import androidx.media3.common.ParserException
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.math.max

object BitmapUtil {
    @Throws(IOException::class)
    fun decode(
        data: ByteArray?, length: Int, options: BitmapFactory.Options?, maximumOutputDimension: Int
    ): Bitmap {
        var options: BitmapFactory.Options? = options
        if (maximumOutputDimension != C.LENGTH_UNSET) {
            if (options == null) {
                options = BitmapFactory.Options()
            }
            options.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(data,  0, length, options)
            var largerDimensions: Int = max(options.outWidth, options.outHeight)

            options.inJustDecodeBounds = false
            options.inSampleSize = 1
            // Only scaling by 2x is supported.
            while (largerDimensions > maximumOutputDimension) {
                options.inSampleSize *= 2
                largerDimensions /= 2
            }
        }

        var bitmap: Bitmap? = BitmapFactory.decodeByteArray(data,  0, length, options)
        if (options != null) {
            options.inSampleSize = 1
        }
        if (bitmap == null) {
            throw ParserException.createForMalformedContainer(
                "Could not decode image data", IllegalStateException()
            )
        }
        // BitmapFactory doesn't read the exif header, so we use the ExifInterface to this do ensure the
        // bitmap is correctly orientated.
        val exifInterface = ByteArrayInputStream(data).use { inputStream ->
            ExifInterface(inputStream)
        }
        val rotationDegrees: Int = exifInterface.getRotationDegrees()
        if (rotationDegrees != 0) {
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees.toFloat())
            bitmap =
                Bitmap.createBitmap(
                    bitmap,  
                    0,  
                    0,
                    bitmap.getWidth(),
                    bitmap.getHeight(),
                    matrix,  
                    false
                )
        }
        return bitmap
    }

    fun makeShared(bitmap: Bitmap): Bitmap? {
        return if (Build.VERSION.SDK_INT >= 31) bitmap.asShared() else bitmap
    }
}
