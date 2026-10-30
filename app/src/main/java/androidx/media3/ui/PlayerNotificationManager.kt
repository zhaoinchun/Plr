package androidx.media3.ui

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.media.session.MediaSession
import android.os.Handler
import android.os.Looper
import android.os.Message
import androidx.annotation.DrawableRes
import androidx.annotation.IntDef
import androidx.annotation.IntRange
import androidx.core.app.NotificationBuilderWithBuilderAccessor
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.BadgeIconType
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.NotificationUtil
import androidx.media3.common.util.NotificationUtil.createNotificationChannel

import androidx.media3.common.util.Util.createHandler
import androidx.media3.common.util.Util.handlePauseButtonAction
import androidx.media3.common.util.Util.handlePlayButtonAction
import androidx.media3.common.util.Util.registerReceiverNotExported
import androidx.media3.common.util.Util.shouldShowPlayButton
import com.example.plr.R
import com.google.common.base.Preconditions

class PlayerNotificationManager(
    context: Context,
    private val channelId: String,
    private val notificationId: Int,
    private val mediaDescriptionAdapter: MediaDescriptionAdapter,
    private val notificationListener: NotificationListener?,
    private val customActionReceiver: CustomActionReceiver?,
    @field:DrawableRes private var smallIconResourceId: Int,
    playActionIconResourceId: Int,
    pauseActionIconResourceId: Int,
    stopActionIconResourceId: Int,
    rewindActionIconResourceId: Int,
    fastForwardActionIconResourceId: Int,
    previousActionIconResourceId: Int,
    nextActionIconResourceId: Int,
    private val groupKey: String?
) {
    interface MediaDescriptionAdapter {
        fun getCurrentContentTitle(player: Player): CharSequence
        fun createCurrentContentIntent(player: Player): PendingIntent?
        fun getCurrentContentText(player: Player): CharSequence?
        fun getCurrentSubText(player: Player): CharSequence? {
            return null
        }

        fun getCurrentLargeIcon(player: Player, callback: BitmapCallback): Bitmap?
    }

    interface CustomActionReceiver {
        fun createCustomActions(
            context: Context,
            instanceId: Int
        ): MutableMap<String, NotificationCompat.Action>

        fun getCustomActions(player: Player): MutableList<String>
        fun onCustomAction(player: Player, action: String, intent: Intent)
    }

    interface NotificationListener {
        fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {}
        fun onNotificationPosted(
            notificationId: Int, notification: Notification, ongoing: Boolean
        ) {
        }
    }

    class Builder(
        val context: Context,
        @IntRange(from = 1) val notificationId: Int,
        val channelId: String
    ) {
        var notificationListener: NotificationListener? = null
        var customActionReceiver: CustomActionReceiver? = null
        var mediaDescriptionAdapter: MediaDescriptionAdapter =
            DefaultMediaDescriptionAdapter(null)
        var channelNameResourceId: Int = 0
        var channelDescriptionResourceId: Int = 0
        var channelImportance = NotificationUtil.IMPORTANCE_LOW
        var smallIconResourceId = R.drawable.exo_notification_small_icon
        var rewindActionIconResourceId = R.drawable.exo_notification_rewind
        var playActionIconResourceId = R.drawable.exo_notification_play
        var pauseActionIconResourceId = R.drawable.exo_notification_pause
        var stopActionIconResourceId = R.drawable.exo_notification_stop
        var fastForwardActionIconResourceId = R.drawable.exo_notification_fastforward
        var previousActionIconResourceId = R.drawable.exo_notification_previous
        var nextActionIconResourceId = R.drawable.exo_notification_next
        var groupKey: String? = null

        init {
            Preconditions.checkArgument(notificationId > 0)
        }

        fun setChannelNameResourceId(channelNameResourceId: Int): Builder {
            this.channelNameResourceId = channelNameResourceId
            return this
        }

        fun setChannelDescriptionResourceId(channelDescriptionResourceId: Int): Builder {
            this.channelDescriptionResourceId = channelDescriptionResourceId
            return this
        }

        fun setChannelImportance(channelImportance: @NotificationUtil.Importance Int): Builder {
            this.channelImportance = channelImportance
            return this
        }

        fun setNotificationListener(notificationListener: NotificationListener?): Builder {
            this.notificationListener = notificationListener
            return this
        }

        fun setCustomActionReceiver(customActionReceiver: CustomActionReceiver?): Builder {
            this.customActionReceiver = customActionReceiver
            return this
        }

        fun setSmallIconResourceId(smallIconResourceId: Int): Builder {
            this.smallIconResourceId = smallIconResourceId
            return this
        }

        fun setPlayActionIconResourceId(playActionIconResourceId: Int): Builder {
            this.playActionIconResourceId = playActionIconResourceId
            return this
        }

        fun setPauseActionIconResourceId(pauseActionIconResourceId: Int): Builder {
            this.pauseActionIconResourceId = pauseActionIconResourceId
            return this
        }

        fun setStopActionIconResourceId(stopActionIconResourceId: Int): Builder {
            this.stopActionIconResourceId = stopActionIconResourceId
            return this
        }

        fun setRewindActionIconResourceId(rewindActionIconResourceId: Int): Builder {
            this.rewindActionIconResourceId = rewindActionIconResourceId
            return this
        }

        fun setFastForwardActionIconResourceId(fastForwardActionIconResourceId: Int): Builder {
            this.fastForwardActionIconResourceId = fastForwardActionIconResourceId
            return this
        }

        fun setPreviousActionIconResourceId(previousActionIconResourceId: Int): Builder {
            this.previousActionIconResourceId = previousActionIconResourceId
            return this
        }

        fun setNextActionIconResourceId(nextActionIconResourceId: Int): Builder {
            this.nextActionIconResourceId = nextActionIconResourceId
            return this
        }

        fun setGroup(groupKey: String?): Builder {
            this.groupKey = groupKey
            return this
        }

        fun setMediaDescriptionAdapter(mediaDescriptionAdapter: MediaDescriptionAdapter): Builder {
            this.mediaDescriptionAdapter = mediaDescriptionAdapter
            return this
        }

        fun build(): PlayerNotificationManager {
            if (channelNameResourceId != 0) {
                createNotificationChannel(
                    context,
                    channelId,
                    channelNameResourceId,
                    channelDescriptionResourceId,
                    channelImportance
                )
            }

            return PlayerNotificationManager(
                context,
                channelId,
                notificationId,
                mediaDescriptionAdapter,
                notificationListener,
                customActionReceiver,
                smallIconResourceId,
                playActionIconResourceId,
                pauseActionIconResourceId,
                stopActionIconResourceId,
                rewindActionIconResourceId,
                fastForwardActionIconResourceId,
                previousActionIconResourceId,
                nextActionIconResourceId,
                groupKey
            )
        }
    }

    inner class BitmapCallback
    constructor(private val notificationTag: Int) {

        fun onBitmap(bitmap: Bitmap?) {
            if (bitmap != null) {
                postUpdateNotificationBitmap(bitmap, notificationTag)
            }
        }
    }

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [NotificationCompat.VISIBILITY_PRIVATE, NotificationCompat.VISIBILITY_PUBLIC, NotificationCompat.VISIBILITY_SECRET
        ]
    )
    annotation class Visibility

    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
    @IntDef(
        value = [NotificationCompat.PRIORITY_DEFAULT, NotificationCompat.PRIORITY_MAX, NotificationCompat.PRIORITY_HIGH, NotificationCompat.PRIORITY_LOW, NotificationCompat.PRIORITY_MIN
        ]
    )
    annotation class Priority

    private val context: Context = context.applicationContext
    private val mainHandler: Handler
    private val notificationManager: NotificationManagerCompat
    private val intentFilter: IntentFilter
    private val playerListener: Player.Listener
    private val notificationBroadcastReceiver: NotificationBroadcastReceiver
    private val playbackActions: MutableMap<String, NotificationCompat.Action>
    private val customActions: MutableMap<String, NotificationCompat.Action>
    private val dismissPendingIntent: PendingIntent?
    private val instanceId: Int = instanceIdCounter++

    private var builder: NotificationCompat.Builder? = null
    private var builderActions: MutableList<NotificationCompat.Action>? = null
    private var player: Player? = null
    private var isNotificationStarted = false
    private var currentNotificationTag = 0
    private var mediaSessionToken: MediaSession.Token? = null
    private var usePreviousAction = true
    private var useNextAction = true
    private var usePreviousActionInCompactView = false
    private var useNextActionInCompactView = false
    private var useRewindAction = true
    private var useFastForwardAction = true
    private var useRewindActionInCompactView = false
    private var useFastForwardActionInCompactView = false
    private var usePlayPauseActions = true
    private var showPlayButtonIfSuppressed = true
    private var useStopAction = false
    private var badgeIconType = NotificationCompat.BADGE_ICON_SMALL
    private var colorized = true
    private var defaults = 0
    private var color = Color.TRANSPARENT
    private var visibility = NotificationCompat.VISIBILITY_PUBLIC
    private var priority: @Priority Int = NotificationCompat.PRIORITY_LOW
    private var useChronometer = true

    init {
        // This fails the nullness checker because handleMessage() is 'called' while `this` is still
        // @UnderInitialization. No tasks are scheduled on mainHandler before the constructor completes,
        // so this is safe and we can suppress the warning.
        val mainHandler = createHandler(
            Looper.getMainLooper(),
            Handler.Callback { msg: Message? -> this.handleMessage(msg!!) })
        this.mainHandler = mainHandler
        notificationManager = NotificationManagerCompat.from(context)
        playerListener = PlayerListener()
        notificationBroadcastReceiver = NotificationBroadcastReceiver()
        intentFilter = IntentFilter()

        // initialize actions
        playbackActions =
            createPlaybackActions(
                context,
                instanceId,
                playActionIconResourceId,
                pauseActionIconResourceId,
                stopActionIconResourceId,
                rewindActionIconResourceId,
                fastForwardActionIconResourceId,
                previousActionIconResourceId,
                nextActionIconResourceId
            )
        for (action in playbackActions.keys) {
            intentFilter.addAction(action)
        }
        customActions =
            customActionReceiver?.createCustomActions(context, instanceId)
                ?: mutableMapOf()
        for (action in customActions.keys) {
            intentFilter.addAction(action)
        }
        dismissPendingIntent = createBroadcastIntent(ACTION_DISMISS, context, instanceId)
        intentFilter.addAction(ACTION_DISMISS)
    }

    fun setPlayer(player: Player?) {
        Preconditions.checkState(Looper.myLooper() == Looper.getMainLooper())
        Preconditions.checkArgument(player == null || player.applicationLooper == Looper.getMainLooper())
        if (this.player === player) {
            return
        }
        if (this.player != null) {
            this.player!!.removeListener(playerListener)
            if (player == null) {
                stopNotification( false)
            }
        }
        this.player = player
        if (player != null) {
            player.addListener(playerListener)
            postStartOrUpdateNotification()
        }
    }

    fun setUseNextAction(useNextAction: Boolean) {
        if (this.useNextAction != useNextAction) {
            this.useNextAction = useNextAction
            invalidate()
        }
    }

    fun setUsePreviousAction(usePreviousAction: Boolean) {
        if (this.usePreviousAction != usePreviousAction) {
            this.usePreviousAction = usePreviousAction
            invalidate()
        }
    }

    fun setUseNextActionInCompactView(useNextActionInCompactView: Boolean) {
        if (this.useNextActionInCompactView != useNextActionInCompactView) {
            this.useNextActionInCompactView = useNextActionInCompactView
            if (useNextActionInCompactView) {
                useFastForwardActionInCompactView = false
            }
            invalidate()
        }
    }

    fun setUsePreviousActionInCompactView(usePreviousActionInCompactView: Boolean) {
        if (this.usePreviousActionInCompactView != usePreviousActionInCompactView) {
            this.usePreviousActionInCompactView = usePreviousActionInCompactView
            if (usePreviousActionInCompactView) {
                useRewindActionInCompactView = false
            }
            invalidate()
        }
    }

    fun setUseFastForwardAction(useFastForwardAction: Boolean) {
        if (this.useFastForwardAction != useFastForwardAction) {
            this.useFastForwardAction = useFastForwardAction
            invalidate()
        }
    }

    fun setUseRewindAction(useRewindAction: Boolean) {
        if (this.useRewindAction != useRewindAction) {
            this.useRewindAction = useRewindAction
            invalidate()
        }
    }

    fun setUseFastForwardActionInCompactView(
        useFastForwardActionInCompactView: Boolean
    ) {
        if (this.useFastForwardActionInCompactView != useFastForwardActionInCompactView) {
            this.useFastForwardActionInCompactView = useFastForwardActionInCompactView
            if (useFastForwardActionInCompactView) {
                useNextActionInCompactView = false
            }
            invalidate()
        }
    }

    fun setUseRewindActionInCompactView(useRewindActionInCompactView: Boolean) {
        if (this.useRewindActionInCompactView != useRewindActionInCompactView) {
            this.useRewindActionInCompactView = useRewindActionInCompactView
            if (useRewindActionInCompactView) {
                usePreviousActionInCompactView = false
            }
            invalidate()
        }
    }

    fun setUsePlayPauseActions(usePlayPauseActions: Boolean) {
        if (this.usePlayPauseActions != usePlayPauseActions) {
            this.usePlayPauseActions = usePlayPauseActions
            invalidate()
        }
    }

    fun setShowPlayButtonIfPlaybackIsSuppressed(showPlayButtonIfSuppressed: Boolean) {
        if (this.showPlayButtonIfSuppressed != showPlayButtonIfSuppressed) {
            this.showPlayButtonIfSuppressed = showPlayButtonIfSuppressed
            invalidate()
        }
    }

    fun setUseStopAction(useStopAction: Boolean) {
        if (this.useStopAction == useStopAction) {
            return
        }
        this.useStopAction = useStopAction
        invalidate()
    }

    fun setMediaSessionToken(token: MediaSession.Token?) {
        if (this.mediaSessionToken != token) {
            mediaSessionToken = token
            invalidate()
        }
    }

    fun setBadgeIconType(@BadgeIconType badgeIconType: Int) {
        if (this.badgeIconType == badgeIconType) {
            return
        }
        when (badgeIconType) {
            NotificationCompat.BADGE_ICON_NONE, NotificationCompat.BADGE_ICON_SMALL, NotificationCompat.BADGE_ICON_LARGE -> this.badgeIconType =
                badgeIconType

            else -> throw IllegalArgumentException()
        }
        invalidate()
    }

    fun setColorized(colorized: Boolean) {
        if (this.colorized != colorized) {
            this.colorized = colorized
            invalidate()
        }
    }

    fun setDefaults(defaults: Int) {
        if (this.defaults != defaults) {
            this.defaults = defaults
            invalidate()
        }
    }

    fun setColor(color: Int) {
        if (this.color != color) {
            this.color = color
            invalidate()
        }
    }

    fun setPriority(priority: @Priority Int) {
        if (this.priority == priority) {
            return
        }
        when (priority) {
            NotificationCompat.PRIORITY_DEFAULT, NotificationCompat.PRIORITY_MAX, NotificationCompat.PRIORITY_HIGH, NotificationCompat.PRIORITY_LOW, NotificationCompat.PRIORITY_MIN -> this.priority =
                priority

            else -> throw IllegalArgumentException()
        }
        invalidate()
    }

    fun setSmallIcon(@DrawableRes smallIconResourceId: Int) {
        if (this.smallIconResourceId != smallIconResourceId) {
            this.smallIconResourceId = smallIconResourceId
            invalidate()
        }
    }

    fun setUseChronometer(useChronometer: Boolean) {
        if (this.useChronometer != useChronometer) {
            this.useChronometer = useChronometer
            invalidate()
        }
    }

    fun setVisibility(visibility: @Visibility Int) {
        if (this.visibility == visibility) {
            return
        }
        when (visibility) {
            NotificationCompat.VISIBILITY_PRIVATE, NotificationCompat.VISIBILITY_PUBLIC, NotificationCompat.VISIBILITY_SECRET -> this.visibility =
                visibility

            else -> throw IllegalStateException()
        }
        invalidate()
    }

    fun invalidate() {
        if (isNotificationStarted) {
            postStartOrUpdateNotification()
        }
    }

    // This class is generally used with a media session which does not require notification
    // permission.
    // https://developer.android.com/develop/ui/views/notifications/notification-permission#exemptions-media-sessions
    @SuppressLint("MissingPermission")
    private fun startOrUpdateNotification(player: Player, bitmap: Bitmap?) {
        val ongoing = getOngoing(player)
        builder = createNotification(player, builder, ongoing, bitmap)
        if (builder == null) {
            stopNotification( false)
            return
        }
        val notification = builder!!.build()
        notificationManager.notify(notificationId, notification)
        if (!isNotificationStarted) {
            registerReceiverNotExported(context, notificationBroadcastReceiver, intentFilter)
        }
        notificationListener?.onNotificationPosted(
            notificationId, notification, ongoing || !isNotificationStarted
        )
        isNotificationStarted = true
    }

    private fun stopNotification(dismissedByUser: Boolean) {
        if (isNotificationStarted) {
            isNotificationStarted = false
            mainHandler.removeMessages(MSG_START_OR_UPDATE_NOTIFICATION)
            notificationManager.cancel(notificationId)
            context.unregisterReceiver(notificationBroadcastReceiver)
            notificationListener?.onNotificationCancelled(notificationId, dismissedByUser)
        }
    }

    fun createNotification(
        player: Player,
        builder: NotificationCompat.Builder?,
        ongoing: Boolean,
        largeIcon: Bitmap?
    ): NotificationCompat.Builder? {
        var builder = builder
        var largeIcon = largeIcon
        if (player.playbackState == Player.STATE_IDLE && player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)
            && player.currentTimeline.isEmpty
        ) {
            builderActions = null
            return null
        }

        val actionNames = getActions(player)
        val actions: MutableList<NotificationCompat.Action> =
            ArrayList<NotificationCompat.Action>(actionNames.size)
        for (i in actionNames.indices) {
            val actionName = actionNames[i]
            val action =
                if (playbackActions.containsKey(actionName))
                    playbackActions[actionName]
                else
                    customActions[actionName]
            if (action != null) {
                actions.add(action)
            }
        }

        if (builder == null || actions != builderActions) {
            builder = NotificationCompat.Builder(context, channelId)
            builderActions = actions
            for (i in actions.indices) {
                builder.addAction(actions[i])
            }
        }

        val actionIndicesForCompactView = getActionIndicesForCompactView(actionNames, player)
        builder.setStyle(MediaStyle(mediaSessionToken, actionIndicesForCompactView))
        builder.setDeleteIntent(dismissPendingIntent)

        builder
            .setBadgeIconType(badgeIconType)
            .setOngoing(ongoing)
            .setColor(color)
            .setColorized(colorized)
            .setSmallIcon(smallIconResourceId)
            .setVisibility(visibility)
            .setPriority(priority)
            .setDefaults(defaults)

        if (useChronometer
            && player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            && player.isPlaying
            && !player.isPlayingAd && !player.isCurrentMediaItemDynamic && player.playbackParameters!!.speed == 1f
        ) {
            builder
                .setWhen(System.currentTimeMillis() - player.contentPosition)
                .setShowWhen(true)
                .setUsesChronometer(true)
        } else {
            builder.setShowWhen(false).setUsesChronometer(false)
        }

        // Set media specific notification properties from MediaDescriptionAdapter.
        builder.setContentTitle(mediaDescriptionAdapter.getCurrentContentTitle(player))
        builder.setContentText(mediaDescriptionAdapter.getCurrentContentText(player))
        builder.setSubText(mediaDescriptionAdapter.getCurrentSubText(player))
        if (largeIcon == null) {
            largeIcon =
                mediaDescriptionAdapter.getCurrentLargeIcon(
                    player, BitmapCallback(++currentNotificationTag)
                )
        }
        setLargeIcon(builder, largeIcon)
        builder.setContentIntent(mediaDescriptionAdapter.createCurrentContentIntent(player))

        if (groupKey != null) {
            builder.setGroup(groupKey)
        }

        builder.setOnlyAlertOnce(true)
        return builder
    }

    fun getActions(player: Player): MutableList<String?> {
        val enablePrevious = player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)
        val enableRewind = player.isCommandAvailable(Player.COMMAND_SEEK_BACK)
        val enableFastForward = player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)
        val enableNext = player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)

        val stringActions: MutableList<String?> = ArrayList<String?>()
        if (usePreviousAction && enablePrevious) {
            stringActions.add(ACTION_PREVIOUS)
        }
        if (useRewindAction && enableRewind) {
            stringActions.add(ACTION_REWIND)
        }
        if (usePlayPauseActions) {
            if (shouldShowPlayButton(player, showPlayButtonIfSuppressed)) {
                stringActions.add(ACTION_PLAY)
            } else {
                stringActions.add(ACTION_PAUSE)
            }
        }
        if (useFastForwardAction && enableFastForward) {
            stringActions.add(ACTION_FAST_FORWARD)
        }
        if (useNextAction && enableNext) {
            stringActions.add(ACTION_NEXT)
        }
        if (customActionReceiver != null) {
            stringActions.addAll(customActionReceiver.getCustomActions(player)!!)
        }
        if (useStopAction) {
            stringActions.add(ACTION_STOP)
        }
        return stringActions
    }

    @Suppress("unused")
    fun getActionIndicesForCompactView(
        actionNames: MutableList<String?>,
        player: Player?
    ): IntArray {
        val pauseActionIndex = actionNames.indexOf(ACTION_PAUSE)
        val playActionIndex = actionNames.indexOf(ACTION_PLAY)
        val leftSideActionIndex =
            if (usePreviousActionInCompactView)
                actionNames.indexOf(ACTION_PREVIOUS)
            else
                (if (useRewindActionInCompactView) actionNames.indexOf(ACTION_REWIND) else -1)
        val rightSideActionIndex =
            if (useNextActionInCompactView)
                actionNames.indexOf(ACTION_NEXT)
            else
                (if (useFastForwardActionInCompactView) actionNames.indexOf(ACTION_FAST_FORWARD) else -1)

        val actionIndices = IntArray(3)
        var actionCounter = 0
        if (leftSideActionIndex != -1) {
            actionIndices[actionCounter++] = leftSideActionIndex
        }
        val shouldShowPlayButton = shouldShowPlayButton(player, showPlayButtonIfSuppressed)
        if (pauseActionIndex != -1 && !shouldShowPlayButton) {
            actionIndices[actionCounter++] = pauseActionIndex
        } else if (playActionIndex != -1 && shouldShowPlayButton) {
            actionIndices[actionCounter++] = playActionIndex
        }
        if (rightSideActionIndex != -1) {
            actionIndices[actionCounter++] = rightSideActionIndex
        }
        return actionIndices.copyOf(actionCounter)
    }

    fun getOngoing(player: Player): Boolean {
        val playbackState = player.playbackState
        return (playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY)
                && player.playWhenReady
    }

    private fun postStartOrUpdateNotification() {
        if (!mainHandler.hasMessages(MSG_START_OR_UPDATE_NOTIFICATION)) {
            mainHandler.sendEmptyMessage(MSG_START_OR_UPDATE_NOTIFICATION)
        }
    }

    private fun postUpdateNotificationBitmap(bitmap: Bitmap?, notificationTag: Int) {
        mainHandler
            .obtainMessage(
                MSG_UPDATE_NOTIFICATION_BITMAP, notificationTag, C.INDEX_UNSET,  bitmap
            )
            .sendToTarget()
    }

    private fun handleMessage(msg: Message): Boolean {
        when (msg.what) {
            MSG_START_OR_UPDATE_NOTIFICATION -> if (player != null) {
                startOrUpdateNotification(player!!,  null)
            }

            MSG_UPDATE_NOTIFICATION_BITMAP -> if (player != null && isNotificationStarted && currentNotificationTag == msg.arg1) {
                startOrUpdateNotification(player!!, msg.obj as Bitmap?)
            }

            else -> return false
        }
        return true
    }

    private inner class PlayerListener : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_REPEAT_MODE_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_MEDIA_METADATA_CHANGED
                )
            ) {
                postStartOrUpdateNotification()
            }
        }
    }

    private inner class NotificationBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            val player = this@PlayerNotificationManager.player
            if (player == null || !isNotificationStarted ||
                intent.getIntExtra(EXTRA_INSTANCE_ID, instanceId) != instanceId
            ) {
                return
            }
            val action = intent.getAction()
            if (ACTION_PLAY == action) {
                handlePlayButtonAction(player)
            } else if (ACTION_PAUSE == action) {
                handlePauseButtonAction(player)
            } else if (ACTION_PREVIOUS == action) {
                if (player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)) {
                    player.seekToPrevious()
                }
            } else if (ACTION_REWIND == action) {
                if (player.isCommandAvailable(Player.COMMAND_SEEK_BACK)) {
                    player.seekBack()
                }
            } else if (ACTION_FAST_FORWARD == action) {
                if (player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)) {
                    player.seekForward()
                }
            } else if (ACTION_NEXT == action) {
                if (player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)) {
                    player.seekToNext()
                }
            } else if (ACTION_STOP == action) {
                if (player.isCommandAvailable(Player.COMMAND_STOP)) {
                    player.stop()
                }
                if (player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
                    player.clearMediaItems()
                }
            } else if (ACTION_DISMISS == action) {
                stopNotification( true)
            } else if (action != null && customActionReceiver != null && customActions.containsKey(
                    action
                )
            ) {
                customActionReceiver.onCustomAction(player, action, intent)
            }
        }
    }

    private class MediaStyle(
        private val token: MediaSession.Token?,
        private val actionsToShowInCompact: IntArray
    ) : NotificationCompat.Style() {
        override fun apply(builder: NotificationBuilderWithBuilderAccessor) {
            val style = Notification.MediaStyle()
            style.setShowActionsInCompactView(*actionsToShowInCompact)
            if (token != null) {
                style.setMediaSession(token)
            }
            builder.getBuilder().setStyle(style)
        }
    }

    companion object {
        const val ACTION_PLAY: String = "androidx.media3.ui.notification.play"
        const val ACTION_PAUSE: String = "androidx.media3.ui.notification.pause"
        const val ACTION_PREVIOUS: String = "androidx.media3.ui.notification.prev"
        const val ACTION_NEXT: String = "androidx.media3.ui.notification.next"
        const val ACTION_FAST_FORWARD: String = "androidx.media3.ui.notification.ffwd"
        const val ACTION_REWIND: String = "androidx.media3.ui.notification.rewind"
        const val ACTION_STOP: String = "androidx.media3.ui.notification.stop"
        const val EXTRA_INSTANCE_ID: String = "INSTANCE_ID"
        private const val ACTION_DISMISS = "androidx.media3.ui.notification.dismiss"

        // Internal messages.
        private const val MSG_START_OR_UPDATE_NOTIFICATION = 1
        private const val MSG_UPDATE_NOTIFICATION_BITMAP = 2
        private var instanceIdCounter = 0

        private fun createPlaybackActions(
            context: Context,
            instanceId: Int,
            playActionIconResourceId: Int,
            pauseActionIconResourceId: Int,
            stopActionIconResourceId: Int,
            rewindActionIconResourceId: Int,
            fastForwardActionIconResourceId: Int,
            previousActionIconResourceId: Int,
            nextActionIconResourceId: Int
        ): MutableMap<String, NotificationCompat.Action> {
            val actions: MutableMap<String, NotificationCompat.Action> = HashMap()
            actions[ACTION_PLAY] = NotificationCompat.Action(
                playActionIconResourceId,
                context.getString(R.string.exo_controls_play_description),
                createBroadcastIntent(ACTION_PLAY, context, instanceId)
            )
            actions[ACTION_PAUSE] = NotificationCompat.Action(
                pauseActionIconResourceId,
                context.getString(R.string.exo_controls_pause_description),
                createBroadcastIntent(ACTION_PAUSE, context, instanceId)
            )
            actions[ACTION_STOP] = NotificationCompat.Action(
                stopActionIconResourceId,
                context.getString(R.string.exo_controls_stop_description),
                createBroadcastIntent(ACTION_STOP, context, instanceId)
            )
            actions[ACTION_REWIND] = NotificationCompat.Action(
                rewindActionIconResourceId,
                context.getString(R.string.exo_controls_rewind_description),
                createBroadcastIntent(ACTION_REWIND, context, instanceId)
            )
            actions[ACTION_FAST_FORWARD] = NotificationCompat.Action(
                fastForwardActionIconResourceId,
                context.getString(R.string.exo_controls_fastforward_description),
                createBroadcastIntent(ACTION_FAST_FORWARD, context, instanceId)
            )
            actions[ACTION_PREVIOUS] = NotificationCompat.Action(
                previousActionIconResourceId,
                context.getString(R.string.exo_controls_previous_description),
                createBroadcastIntent(ACTION_PREVIOUS, context, instanceId)
            )
            actions[ACTION_NEXT] = NotificationCompat.Action(
                nextActionIconResourceId,
                context.getString(R.string.exo_controls_next_description),
                createBroadcastIntent(ACTION_NEXT, context, instanceId)
            )
            return actions
        }

        // Warning is spurious.
        private fun createBroadcastIntent(
            action: String?, context: Context, instanceId: Int
        ): PendingIntent? {
            val intent = Intent(action).setPackage(context.packageName)
            intent.putExtra(EXTRA_INSTANCE_ID, instanceId)
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(context, instanceId, intent, pendingFlags)
        }

        private fun setLargeIcon(builder: NotificationCompat.Builder, largeIcon: Bitmap?) {
            builder.setLargeIcon(largeIcon)
        }
    }
}
