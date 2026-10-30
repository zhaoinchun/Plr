package androidx.media3.ui

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.TextUtils
import androidx.media3.common.Player
import androidx.media3.ui.PlayerNotificationManager.BitmapCallback
import androidx.media3.ui.PlayerNotificationManager.MediaDescriptionAdapter

class DefaultMediaDescriptionAdapter
    (private val pendingIntent: PendingIntent?) : MediaDescriptionAdapter {
    override fun getCurrentContentTitle(player: Player): CharSequence {
        if (!player.isCommandAvailable(Player.COMMAND_GET_METADATA)) {
            return ""
        }
        val displayTitle = player.mediaMetadata!!.displayTitle
        if (!TextUtils.isEmpty(displayTitle)) {
            return displayTitle!!
        }
        val title = player.mediaMetadata!!.title
        return title ?: ""
    }

    override fun createCurrentContentIntent(player: Player): PendingIntent? {
        return pendingIntent
    }

    override fun getCurrentContentText(player: Player): CharSequence? {
        if (!player.isCommandAvailable(Player.COMMAND_GET_METADATA)) {
            return null
        }
        val artist = player.mediaMetadata!!.artist
        if (!TextUtils.isEmpty(artist)) {
            return artist
        }
        return player.mediaMetadata!!.albumArtist
    }

    override fun getCurrentLargeIcon(player: Player, callback: BitmapCallback): Bitmap? {
        if (!player.isCommandAvailable(Player.COMMAND_GET_METADATA)) {
            return null
        }
        val data = player.mediaMetadata!!.artworkData ?: return null

        return BitmapFactory.decodeByteArray(data,  0, data.size)
    }
}
