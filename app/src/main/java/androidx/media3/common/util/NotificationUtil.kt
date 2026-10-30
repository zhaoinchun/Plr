package androidx.media3.common.util

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.IntDef
import androidx.annotation.StringRes
import com.google.common.base.Preconditions

@SuppressLint("InlinedApi")
object NotificationUtil {

    const val IMPORTANCE_UNSPECIFIED: Int = NotificationManager.IMPORTANCE_UNSPECIFIED

    const val IMPORTANCE_NONE: Int = NotificationManager.IMPORTANCE_NONE

    const val IMPORTANCE_MIN: Int = NotificationManager.IMPORTANCE_MIN

    const val IMPORTANCE_LOW: Int = NotificationManager.IMPORTANCE_LOW

    const val IMPORTANCE_DEFAULT: Int = NotificationManager.IMPORTANCE_DEFAULT

    const val IMPORTANCE_HIGH: Int = NotificationManager.IMPORTANCE_HIGH

    fun createNotificationChannel(
        context: Context,
        id: String?,
        @StringRes nameResourceId: Int,
        @StringRes descriptionResourceId: Int,
        importance: @Importance Int
    ) {
        if (Build.VERSION.SDK_INT >= 26) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            @SuppressLint("WrongConstant") val channel// Setting NotificationUtil.Importance on platform API.
                    =
                NotificationChannel(id, context.getString(nameResourceId), importance)
            if (descriptionResourceId != 0) {
                channel.setDescription(context.getString(descriptionResourceId))
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun setNotification(context: Context, id: Int, notification: Notification?) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (notification != null) {
            notificationManager.notify(id, notification)
        } else {
            notificationManager.cancel(id)
        }
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [IMPORTANCE_UNSPECIFIED, IMPORTANCE_NONE, IMPORTANCE_MIN, IMPORTANCE_LOW, IMPORTANCE_DEFAULT, IMPORTANCE_HIGH
        ]
    )
    annotation class Importance
}
