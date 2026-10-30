package androidx.media3.common.util

import android.Manifest.permission
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaDrm
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.provider.MediaStore
import android.security.NetworkSecurityPolicy
import android.telephony.TelephonyManager
import android.text.TextUtils
import android.util.Base64
import android.util.SparseArray
import android.util.SparseBooleanArray
import android.util.SparseIntArray
import android.util.SparseLongArray
import android.view.Display
import android.view.WindowManager
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.C.AudioUsage
import androidx.media3.common.C.AuxiliaryTrackType
import androidx.media3.common.C.BufferFlags
import androidx.media3.common.C.ColorRange
import androidx.media3.common.C.ColorTransfer
import androidx.media3.common.C.FormatSupport
import androidx.media3.common.C.PcmEncoding
import androidx.media3.common.C.RoleFlags
import androidx.media3.common.C.SelectionFlags
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.MimeTypes
import androidx.media3.common.MimeTypes.getTrackTypeOfCodec
import androidx.media3.common.MimeTypes.isDolbyVisionCodec
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioManagerCompat
import androidx.media3.common.audio.AudioProcessor
import com.google.common.base.Ascii
import com.google.common.base.Preconditions
import com.google.common.io.BaseEncoding
import com.google.common.io.ByteStreams
import com.google.common.math.DoubleMath
import com.google.common.math.LongMath
import com.google.common.primitives.Ints
import com.google.common.primitives.UnsignedBytes
import com.google.common.util.concurrent.AsyncFunction
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.google.errorprone.annotations.CheckReturnValue
import org.checkerframework.checker.initialization.qual.UnknownInitialization

import org.checkerframework.checker.nullness.qual.PolyNull
import org.checkerframework.dataflow.qual.Pure
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Arrays
import java.util.Calendar
import java.util.Collections
import java.util.Formatter
import java.util.GregorianCalendar
import java.util.Locale
import java.util.MissingResourceException
import java.util.Objects
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.regex.Pattern
import java.util.zip.DataFormatException
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import kotlin.Any
import kotlin.Array
import kotlin.Boolean
import kotlin.ByteArray
import kotlin.Char
import kotlin.Comparable
import kotlin.Double
import kotlin.Error
import kotlin.Exception
import kotlin.Float
import kotlin.IllegalArgumentException
import kotlin.IllegalStateException
import kotlin.Int
import kotlin.IntArray
import kotlin.Long
import kotlin.LongArray
import kotlin.NoSuchElementException
import kotlin.NumberFormatException
import kotlin.RuntimeException
import kotlin.String
import kotlin.Throwable
import kotlin.Throws
import kotlin.arrayOf
import kotlin.arrayOfNulls
import kotlin.code
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.MutableList
import kotlin.collections.copyOf
import kotlin.collections.indices
import kotlin.collections.toTypedArray
import kotlin.intArrayOf
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.plus
import kotlin.require
import kotlin.toBits

object Util {
    val DEVICE_DEBUG_INFO: String =
        Build.DEVICE + ", " + Build.MODEL + ", " + Build.MANUFACTURER + ", " + Build.VERSION.SDK_INT

    val EMPTY_BYTE_ARRAY: ByteArray = ByteArray(0)
    val EMPTY_LONG_ARRAY: LongArray = LongArray(0)

    private const val TAG = "Util"
    private val XS_DATE_TIME_PATTERN: Pattern = Pattern.compile(
        ("(\\d\\d\\d\\d)\\-(\\d\\d)\\-(\\d\\d)[Tt ]"
                + "(\\d\\d):(\\d\\d):(\\d\\d)([\\.,](\\d+))?"
                + "([Zz]|((\\+|\\-)(\\d?\\d):?(\\d\\d)?))?")
    )
    private val XS_DURATION_PATTERN: Pattern = Pattern.compile(
        "^(-)?P(([0-9]*)Y)?(([0-9]*)M)?(([0-9]*)D)?"
                + "(T(([0-9]*)H)?(([0-9]*)M)?(([0-9.]*)S)?)?$"
    )
    private val ESCAPED_CHARACTER_PATTERN: Pattern = Pattern.compile("%([A-Fa-f0-9]{2})")

    // https://docs.microsoft.com/en-us/azure/media-services/previous/media-services-deliver-content-overview#URLs
    private val ISM_PATH_PATTERN: Pattern =
        Pattern.compile("(?:.*\\.)?isml?(?:/(manifest(.*))?)?", Pattern.CASE_INSENSITIVE)
    private const val ISM_HLS_FORMAT_EXTENSION = "format=m3u8-aapl"
    private const val ISM_DASH_FORMAT_EXTENSION = "format=mpd-time-csf"

    private const val ZLIB_INFLATE_HEADER = 0x78

    // Replacement map of ISO language codes used for normalization.
    private var languageTagReplacementMap: HashMap<String?, String?>? = null

    fun toByteArray(vararg values: Int): ByteArray {
        val array = ByteArray(values.size * 4)
        var index = 0
        for (value in values) {
            array[index++] = (value shr 24).toByte()
            array[index++] = (value shr 16).toByte()
            array[index++] = (value shr 8).toByte()
            array[index++] = (value).toByte()
        }
        return array
    }

    fun toByteArray(value: Float): ByteArray {
        return Ints.toByteArray(value.toBits())
    }

    fun registerReceiverNotExported(
        context: Context, receiver: BroadcastReceiver?, filter: IntentFilter?
    ): Intent? {
        if (Build.VERSION.SDK_INT < 33) {
            return context.registerReceiver(receiver, filter)
        } else {
            return context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        }
    }

    fun startForegroundService(context: Context, intent: Intent?): ComponentName? {
        if (Build.VERSION.SDK_INT >= 26) {
            return context.startForegroundService(intent)
        } else {
            return context.startService(intent)
        }
    }

    fun setForegroundServiceNotification(
        service: Service,
        notificationId: Int,
        notification: Notification,
        foregroundServiceType: Int,
        foregroundServiceManifestType: String?
    ) {
        if (Build.VERSION.SDK_INT >= 29) {
            Api29.startForeground(
                service,
                notificationId,
                notification,
                foregroundServiceType,
                foregroundServiceManifestType
            )
        } else {
            service.startForeground(notificationId, notification)
        }
    }

    fun stopForeground(service: Service, removeNotification: Boolean) {
        if (Build.VERSION.SDK_INT >= 24) {
            Api24.stopForeground(service, removeNotification)
        } else {
            service.stopForeground(removeNotification)
        }
    }

    fun ensureNotificationChannel(
        notificationManager: NotificationManager, channelId: String?, channelName: String?
    ) {
        if (Build.VERSION.SDK_INT < 26
            || notificationManager.getNotificationChannel(channelId) != null
        ) {
            return
        }
        Api26.createNotificationChannel(notificationManager, channelId, channelName)
    }

    fun maybeRequestReadStoragePermission(
        activity: Activity, vararg mediaItems: MediaItem
    ): Boolean {
        for (mediaItem in mediaItems) {
            if (mediaItem.localConfiguration == null) {
                continue
            }
            if (maybeRequestReadStoragePermission(activity, mediaItem.localConfiguration.uri)) {
                return true
            }
            val subtitleConfigs: List<SubtitleConfiguration?> =
                mediaItem.localConfiguration.subtitleConfigurations
            for (i in subtitleConfigs.indices) {
                if (maybeRequestReadStoragePermission(activity, subtitleConfigs[i]!!.uri)) {
                    return true
                }
            }
        }
        return false
    }

    private fun maybeRequestReadStoragePermission(activity: Activity, uri: Uri): Boolean {
        if (!isReadStoragePermissionRequestNeeded(activity, uri)) {
            return false
        }
        if (Build.VERSION.SDK_INT < 33) {
            return requestExternalStoragePermission(activity)
        } else {
            return requestReadMediaPermissions(activity)
        }
    }

    private fun isReadStoragePermissionRequestNeeded(activity: Activity, uri: Uri): Boolean {
        if (isLocalFileUri(uri)) {
            return !isAppSpecificStorageFileUri(activity, uri)
        }
        if (isMediaStoreExternalContentUri(uri)) {
            return true
        }
        return false
    }

    private fun isAppSpecificStorageFileUri(activity: Activity, uri: Uri): Boolean {
        try {
            val uriPath = uri.getPath()
            if (uriPath == null) {
                return false
            }
            val filePath = File(uriPath).getCanonicalPath()
            val internalAppDirectoryPath = activity.getFilesDir().getCanonicalPath()
            val externalAppDirectory = activity.getExternalFilesDir( null)
            val externalAppDirectoryPath =
                if (externalAppDirectory == null) null else externalAppDirectory.getCanonicalPath()
            return filePath.startsWith(internalAppDirectoryPath)
                    || (externalAppDirectoryPath != null && filePath.startsWith(
                externalAppDirectoryPath
            ))
        } catch (e: IOException) {
            // Error while querying canonical paths.
            return false
        }
    }

    private fun isMediaStoreExternalContentUri(uri: Uri): Boolean {
        if (uri.getScheme() != ContentResolver.SCHEME_CONTENT || uri.getAuthority() != MediaStore.AUTHORITY) {
            return false
        }
        val pathSegments = uri.getPathSegments()
        if (pathSegments.isEmpty()) {
            return false
        }
        val firstPathSegment = pathSegments[0]
        return MediaStore.VOLUME_EXTERNAL == firstPathSegment
                || MediaStore.VOLUME_EXTERNAL_PRIMARY == firstPathSegment
    }

    fun checkCleartextTrafficPermitted(vararg mediaItems: MediaItem): Boolean {
        if (Build.VERSION.SDK_INT < 24) {
            // We assume cleartext traffic is permitted.
            return true
        }
        for (mediaItem in mediaItems) {
            if (mediaItem.localConfiguration == null) {
                continue
            }
            if (isTrafficRestricted(mediaItem.localConfiguration.uri)) {
                return false
            }
            for (i in mediaItem.localConfiguration.subtitleConfigurations.indices) {
                if (isTrafficRestricted(mediaItem.localConfiguration.subtitleConfigurations[i].uri)) {
                    return false
                }
            }
        }
        return true
    }

    fun isLocalFileUri(uri: Uri): Boolean {
        val scheme = uri.getScheme()
        return TextUtils.isEmpty(scheme) || scheme == ContentResolver.SCHEME_FILE
    }

    val isRunningOnEmulator: Boolean
        get() {
            val deviceName =
                Ascii.toLowerCase(Build.DEVICE)
            return deviceName.contains("emulator")
                    || deviceName.contains("emu64a")
                    || deviceName.contains("emu64x")
                    || deviceName.contains("generic")
                    || deviceName.contains("vsoc")
        }

    fun <T> contentEquals(
        sparseArray1: SparseArray<T>?, sparseArray2: SparseArray<T>?
    ): Boolean {
        if (sparseArray1 == null) {
            return sparseArray2 == null
        } else if (sparseArray2 == null) {
            return false
        }

        if (Build.VERSION.SDK_INT >= 31) {
            return sparseArray1.contentEquals(sparseArray2)
        }

        val size = sparseArray1.size()
        if (size != sparseArray2.size()) {
            return false
        }

        for (index in 0..<size) {
            val key = sparseArray1.keyAt(index)
            if (sparseArray1.valueAt(index) != sparseArray2.get(key)) {
                return false
            }
        }

        return true
    }

    fun <T> contentHashCode(sparseArray: SparseArray<T?>): Int {
        if (Build.VERSION.SDK_INT >= 31) {
            return sparseArray.contentHashCode()
        }
        var hash = 17
        for (index in 0..<sparseArray.size()) {
            hash = 31 * hash + sparseArray.keyAt(index)
            hash = 31 * hash + Objects.hashCode(sparseArray.valueAt(index))
        }
        return hash
    }

    fun contains(items: Array<Any?>, item: Any?): Boolean {
        for (arrayItem in items) {
            if (arrayItem == item) {
                return true
            }
        }
        return false
    }

    fun <T> contains(sparseArray: SparseArray<T?>, key: Int): Boolean {
        return sparseArray.indexOfKey(key) >= 0
    }

    fun contains(sparseArray: SparseIntArray, key: Int): Boolean {
        return sparseArray.indexOfKey(key) >= 0
    }

    fun contains(sparseArray: SparseBooleanArray, key: Int): Boolean {
        return sparseArray.indexOfKey(key) >= 0
    }

    fun <T> removeRange(list: MutableList<T>, fromIndex: Int, toIndex: Int) {
        require(!(fromIndex < 0 || toIndex > list.size || fromIndex > toIndex))
        if (fromIndex != toIndex) {
            // Checking index inequality prevents an unnecessary allocation.
            list.subList(fromIndex, toIndex).clear()
        }
    }

    fun <T> nullSafeArrayCopy(input: Array<T?>, length: Int): Array<T?> {
        Preconditions.checkArgument(length <= input.size)
        return input.copyOf<T?>(length)
    }

    fun <T> nullSafeArrayCopyOfRange(input: Array<T>, from: Int, to: Int): Array<T> {
        Preconditions.checkArgument(0 <= from)
        Preconditions.checkArgument(to <= input.size)
        return Arrays.copyOfRange<T?>(input, from, to)
    }

    fun <T> nullSafeArrayAppend(original: Array<T?>, newElement: T?): Array<T?> {
        val result = original.copyOf<T?>(original.size + 1)
        result[original.size] = newElement
        return result
    }

    fun <T> nullSafeArrayConcatenation(first: Array<T?>, second: Array<T?>): Array<T?> {
        val concatenation = first.copyOf<T?>(first.size + second.size)
        System.arraycopy( 
            second,  
            0,  
            concatenation,  
            first.size,  
            second.size
        )
        return concatenation
    }

    fun createHandlerForCurrentLooper(
        callback: @UnknownInitialization Handler.Callback? = null
    ): Handler {
        return createHandler(Looper.myLooper()!!, callback)
    }

    fun createHandlerForCurrentOrMainLooper(
        callback: @UnknownInitialization Handler.Callback? = null
    ): Handler {
        return Util.createHandler(currentOrMainLooper!!, callback)
    }

    fun createHandler(
        looper: Looper, callback: @UnknownInitialization Handler.Callback?
    ): Handler {
        return Handler(looper, callback)
    }

    fun postOrRun(handler: Handler, runnable: Runnable): Boolean {
        val looper = handler.getLooper()
        if (!looper.getThread().isAlive()) {
            return false
        }
        if (looper == Looper.myLooper()) {
            runnable.run()
            return true
        } else {
            return handler.post(runnable)
        }
    }

    fun postOrRun(handler: HandlerWrapper, runnable: Runnable): Boolean {
        val looper = handler.looper
        if (!looper.getThread().isAlive()) {
            return false
        }
        if (looper == Looper.myLooper()) {
            runnable.run()
            return true
        } else {
            return handler.post(runnable)
        }
    }

    fun <T> postOrRunWithCompletion(
        handler: Handler, runnable: Runnable, successValue: T?
    ): ListenableFuture<T?> {
        val outputFuture = SettableFuture.create<T?>()
        postOrRun(
            handler,
            Runnable {
                try {
                    if (outputFuture.isCancelled()) {
                        return@Runnable
                    }
                    runnable.run()
                    outputFuture.set(successValue)
                } catch (e: Throwable) {
                    outputFuture.setException(e)
                }
            })
        return outputFuture
    }

    fun <T, U> transformFutureAsync(
        future: ListenableFuture<U?>, transformFunction: AsyncFunction<U?, T?>
    ): ListenableFuture<T?> {
        // This is a simplified copy of Guava's Futures.transformAsync.
        val outputFuture = SettableFuture.create<T?>()
        outputFuture.addListener(
            Runnable {
                if (outputFuture.isCancelled()) {
                    future.cancel( false)
                }
            },
            MoreExecutors.directExecutor()
        )
        future.addListener(
            Runnable {
                val inputFutureResult: U?
                try {
                    inputFutureResult = Futures.getDone<U?>(future)
                } catch (cancellationException: CancellationException) {
                    outputFuture.cancel( false)
                    return@Runnable
                } catch (exception: ExecutionException) {
                    val cause = exception.cause
                    outputFuture.setException(if (cause == null) exception else cause)
                    return@Runnable
                } catch (error: RuntimeException) {
                    outputFuture.setException(error)
                    return@Runnable
                } catch (error: Error) {
                    outputFuture.setException(error)
                    return@Runnable
                }
                try {
                    outputFuture.setFuture(transformFunction.apply(inputFutureResult))
                } catch (exception: Throwable) {
                    outputFuture.setException(exception)
                }
            },
            MoreExecutors.directExecutor()
        )
        return outputFuture
    }

    val currentOrMainLooper: Looper
        get() {
            val myLooper = Looper.myLooper()
            return myLooper ?: Looper.getMainLooper()
        }

    fun newSingleThreadExecutor(threadName: String): ExecutorService? {
        return Executors.newSingleThreadExecutor(ThreadFactory { runnable: Runnable? ->
            Thread(
                runnable,
                threadName
            )
        })
    }

    fun newSingleThreadScheduledExecutor(threadName: String): ScheduledExecutorService? {
        return Executors.newSingleThreadScheduledExecutor(ThreadFactory { runnable: Runnable? ->
            Thread(
                runnable,
                threadName
            )
        })
    }

    fun closeQuietly(closeable: Closeable?) {
        try {
            if (closeable != null) {
                closeable.close()
            }
        } catch (e: IOException) {
            // Ignore.
        }
    }

    fun readBoolean(parcel: Parcel): Boolean {
        return parcel.readInt() != 0
    }

    fun writeBoolean(parcel: Parcel, value: Boolean) {
        parcel.writeInt(if (value) 1 else 0)
    }

    fun getLocaleLanguageTag(locale: Locale): String {
        return locale.toLanguageTag()
    }

    fun normalizeLanguageCode(language: @PolyNull String): @PolyNull String {
        // Locale data (especially for API < 21) may produce tags with '_' instead of the
        // standard-conformant '-'.
        var normalizedTag: String? = language.replace('_', '-')
        if (normalizedTag!!.isEmpty() || normalizedTag == C.LANGUAGE_UNDETERMINED) {
            // Tag isn't valid, keep using the original.
            normalizedTag = language
        }
        normalizedTag = Ascii.toLowerCase(normalizedTag)
        var mainLanguage = splitAtFirst(normalizedTag, "-")[0]
        if (languageTagReplacementMap == null) {
            languageTagReplacementMap = createIsoLanguageReplacementMap()
        }
        val replacedLanguage = languageTagReplacementMap!!.get(mainLanguage)
        if (replacedLanguage != null) {
            normalizedTag =
                replacedLanguage + normalizedTag.substring( mainLanguage!!.length)
            mainLanguage = replacedLanguage
        }
        if ("no" == mainLanguage || "i" == mainLanguage || "zh" == mainLanguage) {
            normalizedTag = maybeReplaceLegacyLanguageTags(normalizedTag)
        }
        return normalizedTag
    }

    @Throws(IOException::class)
    fun loadAsset(context: Context, assetPath: String): String {
        var inputStream: InputStream? = null
        try {
            inputStream = context.getAssets().open(assetPath)
            return fromUtf8Bytes(ByteStreams.toByteArray(inputStream))
        } finally {
            closeQuietly(inputStream)
        }
    }

    @Throws(IOException::class)
    fun loadRawResource(context: Context, resId: Int): String {
        var inputStream: InputStream? = null
        try {
            inputStream = context.getResources().openRawResource(resId)
            return fromUtf8Bytes(ByteStreams.toByteArray(inputStream))
        } finally {
            closeQuietly(inputStream)
        }
    }

    fun fromUtf8Bytes(bytes: ByteArray?): String {
        return kotlin.text.String(bytes!!, StandardCharsets.UTF_8)
    }

    fun fromUtf8Bytes(bytes: ByteArray?, offset: Int, length: Int): String {
        return kotlin.text.String(bytes!!, offset, length, StandardCharsets.UTF_8)
    }

    fun getUtf8Bytes(value: String): ByteArray {
        return value.toByteArray(StandardCharsets.UTF_8)
    }

    fun split(value: String, regex: String): Array<String> {
        return value.split(regex.toRegex()).toTypedArray()
    }

    fun splitAtFirst(value: String, regex: String): Array<String> {
        return value.split(regex.toRegex(), limit = 2).toTypedArray()
    }

    fun isLinebreak(c: Int): Boolean {
        return c == '\n'.code || c == '\r'.code
    }

    fun formatInvariant(format: String, vararg args: Any?): String {
        return String.format(Locale.US, format, *args)
    }

    fun ceilDivide(numerator: Int, denominator: Int): Int {
        return (numerator + denominator - 1) / denominator
    }

    fun ceilDivide(numerator: Long, denominator: Long): Long {
        return (numerator + denominator - 1) / denominator
    }

    fun constrainValue(value: Int, min: Int, max: Int): Int {
        return max(min, min(value, max))
    }

    fun constrainValue(value: Long, min: Long, max: Long): Long {
        return max(min, min(value, max))
    }

    fun constrainValue(value: kotlin.Float, min: kotlin.Float, max: kotlin.Float): kotlin.Float {
        return max(min, min(value, max))
    }

    fun constrainValue(value: Double, min: Double, max: Double): Double {
        return max(min, min(value, max))
    }

    fun addWithOverflowDefault(x: Long, y: Long, overflowResult: Long): Long {
        val result = LongMath.saturatedAdd(x, y)
        return if ((result == Long.MIN_VALUE && x + y != Long.MIN_VALUE)
            || (result == Long.MAX_VALUE && x + y != Long.MAX_VALUE)
        )
            overflowResult
        else
            result
    }

    fun subtractWithOverflowDefault(x: Long, y: Long, overflowResult: Long): Long {
        val result = LongMath.saturatedSubtract(x, y)
        return if ((result == Long.MIN_VALUE && x - y != Long.MIN_VALUE)
            || (result == Long.MAX_VALUE && x - y != Long.MAX_VALUE)
        )
            overflowResult
        else
            result
    }

    fun percentInt(numerator: Long, denominator: Long): Int {
        val numeratorTimes100 = LongMath.saturatedMultiply(numerator, 100)
        val result =
            if (numeratorTimes100 != Long.MAX_VALUE && numeratorTimes100 != Long.MIN_VALUE)
                numeratorTimes100 / denominator
            else
                (numerator / (denominator / 100))
        return Ints.saturatedCast(result)
    }

    fun percentFloat(numerator: Long, denominator: Long): kotlin.Float {
        if (denominator != 0L && numerator == denominator) {
            return 100f
        }
        return (numerator.toFloat() / denominator) * 100
    }

    fun linearSearch(array: IntArray, value: Int): Int {
        for (i in array.indices) {
            if (array[i] == value) {
                return i
            }
        }
        return C.INDEX_UNSET
    }

    fun linearSearch(array: LongArray, value: Long): Int {
        for (i in array.indices) {
            if (array[i] == value) {
                return i
            }
        }
        return C.INDEX_UNSET
    }

    fun binarySearchFloor(
        array: IntArray, value: Int, inclusive: Boolean, stayInBounds: Boolean
    ): Int {
        var index = Arrays.binarySearch(array, value)
        if (index < 0) {
            index = -(index + 2)
        } else {
            while (--index >= 0 && array[index] == value) {
            }
            if (inclusive) {
                index++
            }
        }
        return if (stayInBounds) max(0, index) else index
    }

    fun binarySearchFloor(
        array: LongArray, value: Long, inclusive: Boolean, stayInBounds: Boolean
    ): Int {
        var index = Arrays.binarySearch(array, value)
        if (index < 0) {
            index = -(index + 2)
        } else {
            while (--index >= 0 && array[index] == value) {
            }
            if (inclusive) {
                index++
            }
        }
        return if (stayInBounds) max(0, index) else index
    }

    fun <T : Comparable<in T>?> binarySearchFloor(
        list: MutableList<out Comparable<in T>?>,
        value: T,
        inclusive: Boolean,
        stayInBounds: Boolean
    ): Int {
        var index = Collections.binarySearch<T>(list, value)
        if (index < 0) {
            index = -(index + 2)
        } else {
            while (--index >= 0 && list.get(index)!!.compareTo(value) == 0) {
            }
            if (inclusive) {
                index++
            }
        }
        return if (stayInBounds) max(0, index) else index
    }

    fun binarySearchFloor(
        longArray: androidx.media3.common.util.LongArray,
        value: Long,
        inclusive: Boolean,
        stayInBounds: Boolean
    ): Int {
        var lowIndex = 0
        var highIndex = longArray.size() - 1

        while (lowIndex <= highIndex) {
            val midIndex = (lowIndex + highIndex) ushr 1
            if (longArray.get(midIndex) < value) {
                lowIndex = midIndex + 1
            } else {
                highIndex = midIndex - 1
            }
        }

        if (inclusive && highIndex + 1 < longArray.size() && longArray.get(highIndex + 1) == value) {
            highIndex++
        } else if (stayInBounds && highIndex == -1) {
            highIndex = 0
        }

        return highIndex
    }

    fun binarySearchCeil(
        array: IntArray, value: Int, inclusive: Boolean, stayInBounds: Boolean
    ): Int {
        var index = Arrays.binarySearch(array, value)
        if (index < 0) {
            index = index.inv()
        } else {
            while (++index < array.size && array[index] == value) {
            }
            if (inclusive) {
                index--
            }
        }
        return if (stayInBounds) min(array.size - 1, index) else index
    }

    fun binarySearchCeil(
        array: LongArray, value: Long, inclusive: Boolean, stayInBounds: Boolean
    ): Int {
        var index = Arrays.binarySearch(array, value)
        if (index < 0) {
            index = index.inv()
        } else {
            while (++index < array.size && array[index] == value) {
            }
            if (inclusive) {
                index--
            }
        }
        return if (stayInBounds) min(array.size - 1, index) else index
    }

    fun <T : Comparable<in T?>?> binarySearchCeil(
        list: MutableList<out Comparable<in T?>?>,
        value: T?,
        inclusive: Boolean,
        stayInBounds: Boolean
    ): Int {
        var index = Collections.binarySearch<T?>(list, value)
        if (index < 0) {
            index = index.inv()
        } else {
            val listSize = list.size
            while (++index < listSize && list.get(index)!!.compareTo(value) == 0) {
            }
            if (inclusive) {
                index--
            }
        }
        return if (stayInBounds) min(list.size - 1, index) else index
    }

    fun isSorted(values: LongArray): Boolean {
        for (i in 0..<values.size - 1) {
            if (values[i] > values[i + 1]) {
                return false
            }
        }
        return true
    }

    fun minValue(sparseLongArray: SparseLongArray): Long {
        if (sparseLongArray.size() == 0) {
            throw NoSuchElementException()
        }
        var min = Long.MAX_VALUE
        for (i in 0..<sparseLongArray.size()) {
            min = min(min, sparseLongArray.valueAt(i))
        }
        return min
    }

    fun maxValue(sparseLongArray: SparseLongArray): Long {
        if (sparseLongArray.size() == 0) {
            throw NoSuchElementException()
        }
        var max = Long.MIN_VALUE
        for (i in 0..<sparseLongArray.size()) {
            max = max(max, sparseLongArray.valueAt(i))
        }
        return max
    }

    fun nsToMs(timeNs: Long): Long {
        return if (timeNs == C.TIME_UNSET || timeNs == C.TIME_END_OF_SOURCE)
            timeNs
        else
            (timeNs / 1000000)
    }

    fun usToMs(timeUs: Long): Long {
        return if (timeUs == C.TIME_UNSET || timeUs == C.TIME_END_OF_SOURCE) timeUs else (timeUs / 1000)
    }

    fun msToUs(timeMs: Long): Long {
        return if (timeMs == C.TIME_UNSET || timeMs == C.TIME_END_OF_SOURCE) timeMs else (timeMs * 1000)
    }

    fun msToUs(timeMs: Int): Long {
        val t = timeMs.toLong()
        return if (t == C.TIME_UNSET || t == C.TIME_END_OF_SOURCE) t else (t * 1000)
    }

    @Pure
    fun sampleCountToDurationUs(sampleCount: Long, sampleRate: Int): Long {
        return scaleLargeValue(
            sampleCount,
            C.MICROS_PER_SECOND,
            sampleRate.toLong(),
            RoundingMode.DOWN
        )
    }

    fun durationUsToSampleCount(durationUs: Long, sampleRate: Int): Long {
        return scaleLargeValue(
            durationUs,
            sampleRate.toLong(),
            C.MICROS_PER_SECOND,
            RoundingMode.UP
        )
    }

    fun parseXsDuration(value: String): Long {
        val matcher = XS_DURATION_PATTERN.matcher(value)
        if (matcher.matches()) {
            val negated = !TextUtils.isEmpty(matcher.group(1))
            // Durations containing years and months aren't completely defined. We assume there are
            // 30.4368 days in a month, and 365.242 days in a year.
            val years = matcher.group(3)
            var durationSeconds = if (years != null) years.toDouble() * 31556908 else 0.0
            val months = matcher.group(5)
            durationSeconds += if (months != null) months.toDouble() * 2629739 else 0.0
            val days = matcher.group(7)
            durationSeconds += if (days != null) days.toDouble() * 86400 else 0.0
            val hours = matcher.group(10)
            durationSeconds += if (hours != null) hours.toDouble() * 3600 else 0.0
            val minutes = matcher.group(12)
            durationSeconds += if (minutes != null) minutes.toDouble() * 60 else 0.0
            val seconds = matcher.group(14)
            durationSeconds += if (seconds != null) seconds.toDouble() else 0.0
            val durationMillis = (durationSeconds * 1000).toLong()
            return if (negated) -durationMillis else durationMillis
        } else {
            return (value.toDouble() * 3600 * 1000).toLong()
        }
    }

    // incompatible types in argument.
    // dereference of possibly-null reference matcher.group(9)
    @Throws(ParserException::class)
    fun parseXsDateTime(value: String): Long {
        val matcher = XS_DATE_TIME_PATTERN.matcher(value)
        if (!matcher.matches()) {
            throw ParserException.createForMalformedContainer(
                "Invalid date/time format: " + value,  null
            )
        }

        var timezoneShift: Int
        if (matcher.group(9) == null) {
            // No time zone specified.
            timezoneShift = 0
        } else if (matcher.group(9).equals("Z", ignoreCase = true)) {
            timezoneShift = 0
        } else {
            timezoneShift = matcher.group(12).toInt() * 60
            val timezoneOffsetMinutes = matcher.group(13)
            if (timezoneOffsetMinutes != null) {
                timezoneShift += timezoneOffsetMinutes.toInt()
            }
            if ("-" == matcher.group(11)) {
                timezoneShift *= -1
            }
        }

        val dateTime: Calendar = GregorianCalendar(TimeZone.getTimeZone("GMT"))

        dateTime.clear()
        // Note: The month value is 0-based, hence the -1 on group(2)
        dateTime.set(
            matcher.group(1).toInt(),
            matcher.group(2).toInt() - 1,
            matcher.group(3).toInt(),
            matcher.group(4).toInt(),
            matcher.group(5).toInt(),
            matcher.group(6).toInt()
        )
        if (!TextUtils.isEmpty(matcher.group(8))) {
            val bd = BigDecimal("0." + matcher.group(8))
            // we care only for milliseconds, so movePointRight(3)
            dateTime.set(Calendar.MILLISECOND, bd.movePointRight(3).toInt())
        }

        var time = dateTime.getTimeInMillis()
        if (timezoneShift != 0) {
            time -= timezoneShift * 60000L
        }

        return time
    }

    fun scaleLargeValue(
        value: Long, multiplier: Long, divisor: Long, roundingMode: RoundingMode
    ): Long {
        if (value == 0L || multiplier == 0L) {
            return 0
        }
        if (divisor >= multiplier && (divisor % multiplier) == 0L) {
            val divisionFactor = LongMath.divide(divisor, multiplier, RoundingMode.UNNECESSARY)
            return LongMath.divide(value, divisionFactor, roundingMode)
        } else if (divisor < multiplier && (multiplier % divisor) == 0L) {
            val multiplicationFactor =
                LongMath.divide(multiplier, divisor, RoundingMode.UNNECESSARY)
            return LongMath.saturatedMultiply(value, multiplicationFactor)
        } else if (divisor >= value && (divisor % value) == 0L) {
            val divisionFactor = LongMath.divide(divisor, value, RoundingMode.UNNECESSARY)
            return LongMath.divide(multiplier, divisionFactor, roundingMode)
        } else if (divisor < value && (value % divisor) == 0L) {
            val multiplicationFactor = LongMath.divide(value, divisor, RoundingMode.UNNECESSARY)
            return LongMath.saturatedMultiply(multiplier, multiplicationFactor)
        } else {
            return scaleLargeValueFallback(value, multiplier, divisor, roundingMode)
        }
    }

    fun scaleLargeValues(
        values: MutableList<Long?>, multiplier: Long, divisor: Long, roundingMode: RoundingMode
    ): LongArray {
        val result = LongArray(values.size)
        if (multiplier == 0L) {
            // Array is initialized with all zeroes by default.
            return result
        }
        if (divisor >= multiplier && (divisor % multiplier) == 0L) {
            val divisionFactor = LongMath.divide(divisor, multiplier, RoundingMode.UNNECESSARY)
            for (i in result.indices) {
                result[i] = LongMath.divide(values[i]!!, divisionFactor, roundingMode)
            }
            return result
        } else if (divisor < multiplier && (multiplier % divisor) == 0L) {
            val multiplicationFactor =
                LongMath.divide(multiplier, divisor, RoundingMode.UNNECESSARY)
            for (i in result.indices) {
                result[i] = LongMath.saturatedMultiply(values[i]!!, multiplicationFactor)
            }
            return result
        } else {
            for (i in result.indices) {
                val value: Long = values[i]!!
                if (value == 0L) {
                    // Array is initialized with all zeroes by default.
                    continue
                }
                if (divisor >= value && (divisor % value) == 0L) {
                    val divisionFactor = LongMath.divide(divisor, value, RoundingMode.UNNECESSARY)
                    result[i] = LongMath.divide(multiplier, divisionFactor, roundingMode)
                } else if (divisor < value && (value % divisor) == 0L) {
                    val multiplicationFactor =
                        LongMath.divide(value, divisor, RoundingMode.UNNECESSARY)
                    result[i] = LongMath.saturatedMultiply(multiplier, multiplicationFactor)
                } else {
                    result[i] = scaleLargeValueFallback(value, multiplier, divisor, roundingMode)
                }
            }
            return result
        }
    }

    fun scaleLargeValuesInPlace(
        values: LongArray, multiplier: Long, divisor: Long, roundingMode: RoundingMode
    ) {
        if (multiplier == 0L) {
            Arrays.fill(values, 0)
            return
        }
        if (divisor >= multiplier && (divisor % multiplier) == 0L) {
            val divisionFactor = LongMath.divide(divisor, multiplier, RoundingMode.UNNECESSARY)
            for (i in values.indices) {
                values[i] = LongMath.divide(values[i], divisionFactor, roundingMode)
            }
        } else if (divisor < multiplier && (multiplier % divisor) == 0L) {
            val multiplicationFactor =
                LongMath.divide(multiplier, divisor, RoundingMode.UNNECESSARY)
            for (i in values.indices) {
                values[i] = LongMath.saturatedMultiply(values[i], multiplicationFactor)
            }
        } else {
            for (i in values.indices) {
                if (values[i] == 0L) {
                    continue
                }
                if (divisor >= values[i] && (divisor % values[i]) == 0L) {
                    val divisionFactor =
                        LongMath.divide(divisor, values[i], RoundingMode.UNNECESSARY)
                    values[i] = LongMath.divide(multiplier, divisionFactor, roundingMode)
                } else if (divisor < values[i] && (values[i] % divisor) == 0L) {
                    val multiplicationFactor =
                        LongMath.divide(values[i], divisor, RoundingMode.UNNECESSARY)
                    values[i] = LongMath.saturatedMultiply(multiplier, multiplicationFactor)
                } else {
                    values[i] =
                        scaleLargeValueFallback(values[i], multiplier, divisor, roundingMode)
                }
            }
        }
    }

    private fun scaleLargeValueFallback(
        value: Long, multiplier: Long, divisor: Long, roundingMode: RoundingMode
    ): Long {
        val numerator = LongMath.saturatedMultiply(value, multiplier)
        if (numerator != Long.MAX_VALUE && numerator != Long.MIN_VALUE) {
            return LongMath.divide(numerator, divisor, roundingMode)
        } else {
            // Directly multiplying value and multiplier will overflow a long, so we try and cancel
            // with GCD and try directly multiplying again below. If that still overflows we fall
            // through to floating point arithmetic.
            val gcdOfMultiplierAndDivisor = LongMath.gcd(abs(multiplier), abs(divisor))
            val simplifiedMultiplier =
                LongMath.divide(multiplier, gcdOfMultiplierAndDivisor, RoundingMode.UNNECESSARY)
            var simplifiedDivisor =
                LongMath.divide(divisor, gcdOfMultiplierAndDivisor, RoundingMode.UNNECESSARY)
            val gcdOfValueAndSimplifiedDivisor =
                LongMath.gcd(abs(value), abs(simplifiedDivisor))
            val simplifiedValue =
                LongMath.divide(value, gcdOfValueAndSimplifiedDivisor, RoundingMode.UNNECESSARY)
            simplifiedDivisor =
                LongMath.divide(
                    simplifiedDivisor, gcdOfValueAndSimplifiedDivisor, RoundingMode.UNNECESSARY
                )
            val simplifiedNumerator =
                LongMath.saturatedMultiply(simplifiedValue, simplifiedMultiplier)
            if (simplifiedNumerator != Long.MAX_VALUE && simplifiedNumerator != Long.MIN_VALUE) {
                return LongMath.divide(simplifiedNumerator, simplifiedDivisor, roundingMode)
            } else {
                val multiplicationFactor = simplifiedMultiplier.toDouble() / simplifiedDivisor
                val result = simplifiedValue * multiplicationFactor
                // Clamp values that are too large to be represented by 64-bit signed long. If we don't
                // explicitly clamp then DoubleMath.roundToLong will throw ArithmeticException.
                if (result > Long.MAX_VALUE) {
                    return Long.MAX_VALUE
                } else if (result < Long.MIN_VALUE) {
                    return Long.MIN_VALUE
                } else {
                    return DoubleMath.roundToLong(result, roundingMode)
                }
            }
        }
    }

    // TODO: b/372204124 - Consider switching this (and impls below) to HALF_UP rounding to reduce
    //   round-trip errors when switching between time bases with different resolutions.
    fun scaleLargeTimestamp(timestamp: Long, multiplier: Long, divisor: Long): Long {
        return scaleLargeValue(timestamp, multiplier, divisor, RoundingMode.DOWN)
    }

    fun scaleLargeTimestamps(
        timestamps: MutableList<Long?>,
        multiplier: Long,
        divisor: Long
    ): LongArray {
        return scaleLargeValues(timestamps, multiplier, divisor, RoundingMode.DOWN)
    }

    fun scaleLargeTimestampsInPlace(timestamps: LongArray, multiplier: Long, divisor: Long) {
        scaleLargeValuesInPlace(timestamps, multiplier, divisor, RoundingMode.DOWN)
    }

    fun getMediaDurationForPlayoutDuration(playoutDuration: Long, speed: kotlin.Float): Long {
        if (speed == 1f) {
            return playoutDuration
        }
        return Math.round(playoutDuration.toDouble() * speed)
    }

    fun getPlayoutDurationForMediaDuration(mediaDuration: Long, speed: kotlin.Float): Long {
        if (speed == 1f) {
            return mediaDuration
        }
        return Math.round(mediaDuration.toDouble() / speed)
    }

    fun getIntegerCodeForString(string: String): Int {
        val length = string.length
        Preconditions.checkArgument(length <= 4)
        var result = 0
        for (i in 0..<length) {
            result = result shl 8
            result = result or string[i].code
        }
        return result
    }

    fun toUnsignedLong(x: Int): Long {
        // x is implicitly casted to a long before the bit operation is executed but this does not
        // impact the method correctness.
        return x.toLong() and 0xFFFFFFFFL
    }

    fun toUnsignedInt(x: Long): Int {
        val unsignedIntMaxValue = 4294967295L
        Preconditions.checkState(x <= unsignedIntMaxValue)
        return x.toInt()
    }

    fun toLong(mostSignificantBits: Int, leastSignificantBits: Int): Long {
        return (toUnsignedLong(mostSignificantBits) shl 32) or toUnsignedLong(leastSignificantBits)
    }

    fun getBytesFromHexString(hexString: String): ByteArray {
        return BaseEncoding.base16().ignoreCase().decode(hexString)
    }

    fun toHexString(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): String {
        return BaseEncoding.base16().lowerCase().encode(bytes, offset, length)
    }

    fun toFourccString(fourcc: Int): String {
        return String(Ints.toByteArray(fourcc), StandardCharsets.US_ASCII)
    }

    fun getUserAgent(context: Context, applicationName: String?): String {
        var versionName: String?
        try {
            val packageName = context.getPackageName()
            val info = context.getPackageManager().getPackageInfo(packageName, 0)
            versionName = info.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            versionName = "?"
        }
        return (applicationName
                + "/"
                + versionName
                + " (Linux;Android "
                + Build.VERSION.RELEASE
                + ") "
                + MediaLibraryInfo.VERSION_SLASHY)
    }

    fun getCodecCountOfType(codecs: String?, trackType: @C.TrackType Int): Int {
        val codecArray = splitCodecs(codecs)
        var count = 0
        for (codec in codecArray) {
            if (trackType == getTrackTypeOfCodec(codec)) {
                count++
            }
        }
        return count
    }

    fun getCodecsOfType(codecs: String?, trackType: @C.TrackType Int): String? {
        val codecArray = splitCodecs(codecs)
        if (codecArray.size == 0) {
            return null
        }
        val builder = StringBuilder()
        for (codec in codecArray) {
            if (trackType == getTrackTypeOfCodec(codec)) {
                if (builder.length > 0) {
                    builder.append(",")
                }
                builder.append(codec)
            }
        }
        return if (builder.length > 0) builder.toString() else null
    }

    fun getColorInfoForDolbyVision(
        codecs: String?,
        supplementalCodecs: String?,
        supplementalProfiles: String?
    ): ColorInfo? {
        if (codecs == null) {
            return null
        }

        var colorSpace: @C.ColorSpace Int = Format.NO_VALUE
        var colorRange: @ColorRange Int = Format.NO_VALUE
        var colorTransfer: @ColorTransfer Int = Format.NO_VALUE

        if (!isDolbyVisionCodec(codecs, supplementalCodecs)) {
            return null
        }

        if (codecs.startsWith("dvhe") || codecs.startsWith("dvh1") || codecs.startsWith("dav1")) {
            // profiles 5, 10.0 and 20.0
            colorSpace = C.COLOR_SPACE_BT2020
            colorTransfer = C.COLOR_TRANSFER_ST2084
            colorRange = C.COLOR_RANGE_FULL
        } else if (supplementalProfiles != null) {
            if (supplementalProfiles == "db1p") {
                // BL signal cross-compatibility ID = 1 (e.g profile 8.1)
                colorSpace = C.COLOR_SPACE_BT2020
                colorTransfer = C.COLOR_TRANSFER_ST2084
                colorRange = C.COLOR_RANGE_LIMITED
            } else if (supplementalProfiles.startsWith("db4")) { // db4g or db4h
                // BL signal cross-compatibility ID = 4 (e.g profile 8.4)
                colorSpace = C.COLOR_SPACE_BT2020
                colorTransfer = C.COLOR_TRANSFER_HLG
                colorRange = C.COLOR_RANGE_LIMITED
            }
        }

        if (colorSpace == Format.NO_VALUE) {
            return null
        }

        return ColorInfo.Builder()
            .setColorSpace(colorSpace)
            .setColorRange(colorRange)
            .setColorTransfer(colorTransfer)
            .build()
    }

    fun getCodecsWithoutType(codecs: String?, trackType: @C.TrackType Int): String? {
        val codecArray = splitCodecs(codecs)
        if (codecArray.size == 0) {
            return null
        }
        val builder = StringBuilder()
        for (codec in codecArray) {
            if (trackType != getTrackTypeOfCodec(codec)) {
                if (builder.length > 0) {
                    builder.append(",")
                }
                builder.append(codec)
            }
        }
        return if (builder.length > 0) builder.toString() else null
    }

    fun splitCodecs(codecs: String?): Array<String> {
        if (TextUtils.isEmpty(codecs)) {
            return emptyArray()
        }
        return split(codecs!!.trim { it <= ' ' }, "(\\s*,\\s*)")
    }

    fun getPcmFormat(pcmEncoding: @PcmEncoding Int, channels: Int, sampleRate: Int): Format {
        return Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(channels)
            .setSampleRate(sampleRate)
            .setPcmEncoding(pcmEncoding)
            .build()
    }

    fun getPcmFormat(audioFormat: AudioProcessor.AudioFormat): Format {
        return getPcmFormat(audioFormat.encoding, audioFormat.channelCount, audioFormat.sampleRate)
    }

    fun getPcmEncoding(bitDepth: Int): @PcmEncoding Int {
        return getPcmEncoding(bitDepth, ByteOrder.LITTLE_ENDIAN)
    }

    fun getPcmEncoding(bitDepth: Int, byteOrder: ByteOrder): @PcmEncoding Int {
        when (bitDepth) {
            8 ->                 // Byte order has no effect for single-byte encodings.
                return C.ENCODING_PCM_8BIT

            16 -> return if (byteOrder == ByteOrder.LITTLE_ENDIAN)
                C.ENCODING_PCM_16BIT
            else
                C.ENCODING_PCM_16BIT_BIG_ENDIAN

            24 -> return if (byteOrder == ByteOrder.LITTLE_ENDIAN)
                C.ENCODING_PCM_24BIT
            else
                C.ENCODING_PCM_24BIT_BIG_ENDIAN

            32 -> return if (byteOrder == ByteOrder.LITTLE_ENDIAN)
                C.ENCODING_PCM_32BIT
            else
                C.ENCODING_PCM_32BIT_BIG_ENDIAN

            else -> return C.ENCODING_INVALID
        }
    }

    fun getFloatPcmEncoding(bitDepth: Int): @PcmEncoding Int {
        return getFloatPcmEncoding(bitDepth, ByteOrder.LITTLE_ENDIAN)
    }

    fun getFloatPcmEncoding(bitDepth: Int, byteOrder: ByteOrder): @PcmEncoding Int {
        when (bitDepth) {
            32 -> return if (byteOrder == ByteOrder.LITTLE_ENDIAN)
                C.ENCODING_PCM_FLOAT
            else
                C.ENCODING_PCM_FLOAT_BIG_ENDIAN

            64 -> return if (byteOrder == ByteOrder.LITTLE_ENDIAN)
                C.ENCODING_PCM_DOUBLE
            else
                C.ENCODING_PCM_DOUBLE_BIG_ENDIAN

            else -> return C.ENCODING_INVALID
        }
    }

    fun getEncodingString(encoding: @C.Encoding Int): String {
        when (encoding) {
            C.ENCODING_AAC_ELD -> return "aac-eld"
            C.ENCODING_AAC_ER_BSAC -> return "aac-er-bsac"
            C.ENCODING_AAC_HE_V1 -> return "aac-he-v1"
            C.ENCODING_AAC_HE_V2 -> return "aac-he-v2"
            C.ENCODING_AAC_LC -> return "aac-lc"
            C.ENCODING_AAC_XHE -> return "aac-xhe"
            C.ENCODING_AC3 -> return "ac3"
            C.ENCODING_AC4 -> return "ac4"
            C.ENCODING_DOLBY_TRUEHD -> return "truehd"
            C.ENCODING_DTS -> return "dts"
            C.ENCODING_DTS_HD -> return "dts-hd"
            C.ENCODING_DTS_UHD_P2 -> return "dts-uhd-p2"
            C.ENCODING_DSD -> return "dsd"
            C.ENCODING_E_AC3 -> return "eac3"
            C.ENCODING_E_AC3_JOC -> return "eac3-joc"
            C.ENCODING_MP3 -> return "mp3"
            C.ENCODING_OPUS -> return "opus"
            C.ENCODING_PCM_8BIT -> return "pcm-8"
            C.ENCODING_PCM_16BIT -> return "pcm-16"
            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> return "pcm-16be"
            C.ENCODING_PCM_24BIT -> return "pcm-24"
            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> return "pcm-24be"
            C.ENCODING_PCM_32BIT -> return "pcm-32"
            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> return "pcm-32be"
            C.ENCODING_PCM_DOUBLE -> return "pcm-double"
            C.ENCODING_PCM_DOUBLE_BIG_ENDIAN -> return "pcm-double-be"
            C.ENCODING_PCM_FLOAT -> return "pcm-float"
            C.ENCODING_PCM_FLOAT_BIG_ENDIAN -> return "pcm-float-be"
            C.ENCODING_INVALID -> return "invalid"
            Format.NO_VALUE -> return "unset"
            else -> return "unknown(" + encoding + ")"
        }
    }

    fun isEncodingLinearPcm(encoding: @C.Encoding Int): Boolean {
        return encoding == C.ENCODING_PCM_8BIT || encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_16BIT_BIG_ENDIAN || encoding == C.ENCODING_PCM_24BIT || encoding == C.ENCODING_PCM_24BIT_BIG_ENDIAN || encoding == C.ENCODING_PCM_32BIT || encoding == C.ENCODING_PCM_32BIT_BIG_ENDIAN || encoding == C.ENCODING_PCM_FLOAT || encoding == C.ENCODING_PCM_FLOAT_BIG_ENDIAN || encoding == C.ENCODING_PCM_DOUBLE || encoding == C.ENCODING_PCM_DOUBLE_BIG_ENDIAN
    }

    fun isEncodingHighResolutionPcm(encoding: @PcmEncoding Int): Boolean {
        return encoding == C.ENCODING_PCM_24BIT || encoding == C.ENCODING_PCM_24BIT_BIG_ENDIAN || encoding == C.ENCODING_PCM_32BIT || encoding == C.ENCODING_PCM_32BIT_BIG_ENDIAN || encoding == C.ENCODING_PCM_FLOAT || encoding == C.ENCODING_PCM_FLOAT_BIG_ENDIAN || encoding == C.ENCODING_PCM_DOUBLE || encoding == C.ENCODING_PCM_DOUBLE_BIG_ENDIAN
    }

    fun getAudioTrackChannelConfig(format: Format): Int {
        if (format.channelMask != Format.NO_VALUE) {
            return format.channelMask
        }
        return getAudioTrackChannelConfig(format.channelCount)
    }

    @SuppressLint("InlinedApi") // Inlined AudioFormat constants.
    fun getAudioTrackChannelConfig(channelCount: Int): Int {
        when (channelCount) {
            1 -> return AudioFormat.CHANNEL_OUT_MONO
            2 -> return AudioFormat.CHANNEL_OUT_STEREO
            3 -> return AudioFormat.CHANNEL_OUT_STEREO or AudioFormat.CHANNEL_OUT_FRONT_CENTER
            4 -> return AudioFormat.CHANNEL_OUT_QUAD
            5 -> return AudioFormat.CHANNEL_OUT_QUAD or AudioFormat.CHANNEL_OUT_FRONT_CENTER
            6 -> return AudioFormat.CHANNEL_OUT_5POINT1
            7 -> return AudioFormat.CHANNEL_OUT_5POINT1 or AudioFormat.CHANNEL_OUT_BACK_CENTER
            8 -> return AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            10 -> if (Build.VERSION.SDK_INT >= 32) {
                return AudioFormat.CHANNEL_OUT_5POINT1POINT4
            } else {
                // Before API 32, height channel masks are not available. For those 10-channel streams
                // supported on the audio output devices (e.g. DTS:X P2), we use 7.1-surround instead.
                return AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            }

            12 -> return AudioFormat.CHANNEL_OUT_7POINT1POINT4
            13 -> if (Build.VERSION.SDK_INT >= 32) {
                // TODO(b/238402306): Replace with the AudioFormat.CHANNEL_OUT_13POINT0 constant
                // once it is released.
                return (AudioFormat.CHANNEL_OUT_FRONT_LEFT
                        or AudioFormat.CHANNEL_OUT_FRONT_CENTER
                        or AudioFormat.CHANNEL_OUT_FRONT_RIGHT
                        or AudioFormat.CHANNEL_OUT_SIDE_LEFT
                        or AudioFormat.CHANNEL_OUT_SIDE_RIGHT
                        or AudioFormat.CHANNEL_OUT_TOP_FRONT_LEFT
                        or AudioFormat.CHANNEL_OUT_TOP_FRONT_CENTER
                        or AudioFormat.CHANNEL_OUT_TOP_FRONT_RIGHT
                        or AudioFormat.CHANNEL_OUT_TOP_BACK_LEFT
                        or AudioFormat.CHANNEL_OUT_TOP_BACK_RIGHT
                        or AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_LEFT
                        or AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_CENTER
                        or AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_RIGHT)
            } else {
                return AudioFormat.CHANNEL_INVALID
            }

            14 -> if (Build.VERSION.SDK_INT >= 32) {
                return AudioFormat.CHANNEL_OUT_9POINT1POINT4
            } else {
                return AudioFormat.CHANNEL_INVALID
            }

            16 -> if (Build.VERSION.SDK_INT >= 32) {
                return AudioFormat.CHANNEL_OUT_9POINT1POINT6
            } else {
                return AudioFormat.CHANNEL_INVALID
            }

            24 -> if (Build.VERSION.SDK_INT >= 32) {
                return (AudioFormat.CHANNEL_OUT_7POINT1POINT4
                        or AudioFormat.CHANNEL_OUT_FRONT_LEFT_OF_CENTER
                        or AudioFormat.CHANNEL_OUT_FRONT_RIGHT_OF_CENTER
                        or AudioFormat.CHANNEL_OUT_BACK_CENTER
                        or AudioFormat.CHANNEL_OUT_TOP_CENTER
                        or AudioFormat.CHANNEL_OUT_TOP_FRONT_CENTER
                        or AudioFormat.CHANNEL_OUT_TOP_BACK_CENTER
                        or AudioFormat.CHANNEL_OUT_TOP_SIDE_LEFT
                        or AudioFormat.CHANNEL_OUT_TOP_SIDE_RIGHT
                        or AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_LEFT
                        or AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_RIGHT
                        or AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_CENTER
                        or AudioFormat.CHANNEL_OUT_LOW_FREQUENCY_2)
            } else {
                return AudioFormat.CHANNEL_INVALID
            }

            else -> return AudioFormat.CHANNEL_INVALID
        }
    }

    fun getAudioFormat(sampleRate: Int, channelConfig: Int, encoding: Int): AudioFormat? {
        return AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .setEncoding(encoding)
            .build()
    }

    fun getApiLevelThatAudioFormatIntroducedAudioEncoding(encoding: Int): Int {
        when (encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_8BIT -> return 3
            C.ENCODING_PCM_FLOAT, C.ENCODING_AC3, C.ENCODING_E_AC3 -> return 21
            C.ENCODING_DTS, C.ENCODING_DTS_HD -> return 23
            C.ENCODING_DOLBY_TRUEHD -> return 25
            C.ENCODING_MP3, C.ENCODING_AAC_LC, C.ENCODING_AAC_HE_V1, C.ENCODING_AAC_HE_V2, C.ENCODING_AAC_ELD, C.ENCODING_AAC_XHE, C.ENCODING_AC4, C.ENCODING_E_AC3_JOC -> return 28
            C.ENCODING_OPUS -> return 30
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT -> return 31
            C.ENCODING_DTS_UHD_P2, C.ENCODING_DSD -> return 34
            else -> return Int.MAX_VALUE
        }
    }

    @Pure
    fun getPcmFrameSize(pcmEncoding: @PcmEncoding Int, channelCount: Int): Int {
        return getByteDepth(pcmEncoding) * channelCount
    }

    fun getByteDepth(pcmEncoding: @PcmEncoding Int): Int {
        when (pcmEncoding) {
            C.ENCODING_PCM_8BIT -> return 1
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> return 2
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> return 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT_BIG_ENDIAN -> return 4
            C.ENCODING_PCM_DOUBLE, C.ENCODING_PCM_DOUBLE_BIG_ENDIAN -> return 8
            C.ENCODING_INVALID, Format.NO_VALUE -> throw IllegalArgumentException()
            else -> throw IllegalArgumentException()
        }
    }

    @AudioUsage
    fun getAudioUsageForStreamType(@C.StreamType streamType: @C.StreamType Int): @AudioUsage Int {
        when (streamType) {
            C.STREAM_TYPE_ALARM -> return C.USAGE_ALARM
            C.STREAM_TYPE_DTMF -> return C.USAGE_VOICE_COMMUNICATION_SIGNALLING
            C.STREAM_TYPE_NOTIFICATION -> return C.USAGE_NOTIFICATION
            C.STREAM_TYPE_RING -> return C.USAGE_NOTIFICATION_RINGTONE
            C.STREAM_TYPE_SYSTEM -> return C.USAGE_ASSISTANCE_SONIFICATION
            C.STREAM_TYPE_VOICE_CALL -> return C.USAGE_VOICE_COMMUNICATION
            C.STREAM_TYPE_ACCESSIBILITY -> return C.USAGE_ASSISTANCE_ACCESSIBILITY
            C.STREAM_TYPE_MUSIC -> return C.USAGE_MEDIA
            else -> return C.USAGE_MEDIA
        }
    }

    fun generateAudioSessionId(context: Context): Int {
        val audioSessionId = AudioManagerCompat.getAudioManager(context).generateAudioSessionId()
        return if (audioSessionId != AudioManager.ERROR) audioSessionId else C.AUDIO_SESSION_ID_UNSET
    }

    fun getDrmUuid(drmScheme: String): UUID? {
        when (Ascii.toLowerCase(drmScheme)) {
            "widevine" -> return C.WIDEVINE_UUID
            "playready" -> return C.PLAYREADY_UUID
            "clearkey" -> return C.CLEARKEY_UUID
            else -> try {
                return UUID.fromString(drmScheme)
            } catch (e: RuntimeException) {
                return null
            }
        }
    }

    @PlaybackException.ErrorCode
    fun getErrorCodeForMediaDrmErrorCode(
        mediaDrmErrorCode: Int
    ): @PlaybackException.ErrorCode Int {
        when (mediaDrmErrorCode) {
            MediaDrm.ErrorCodes.ERROR_PROVISIONING_CONFIG, MediaDrm.ErrorCodes.ERROR_PROVISIONING_PARSE, MediaDrm.ErrorCodes.ERROR_PROVISIONING_REQUEST_REJECTED, MediaDrm.ErrorCodes.ERROR_PROVISIONING_CERTIFICATE, MediaDrm.ErrorCodes.ERROR_PROVISIONING_RETRY -> return PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED
            MediaDrm.ErrorCodes.ERROR_LICENSE_PARSE, MediaDrm.ErrorCodes.ERROR_LICENSE_RELEASE, MediaDrm.ErrorCodes.ERROR_LICENSE_REQUEST_REJECTED, MediaDrm.ErrorCodes.ERROR_LICENSE_RESTORE, MediaDrm.ErrorCodes.ERROR_LICENSE_STATE, MediaDrm.ErrorCodes.ERROR_CERTIFICATE_MALFORMED -> return PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED
            MediaDrm.ErrorCodes.ERROR_LICENSE_POLICY, MediaDrm.ErrorCodes.ERROR_INSUFFICIENT_OUTPUT_PROTECTION, MediaDrm.ErrorCodes.ERROR_INSUFFICIENT_SECURITY, MediaDrm.ErrorCodes.ERROR_KEY_EXPIRED, MediaDrm.ErrorCodes.ERROR_KEY_NOT_LOADED -> return PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION
            MediaDrm.ErrorCodes.ERROR_INIT_DATA, MediaDrm.ErrorCodes.ERROR_FRAME_TOO_LARGE -> return PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR
            else -> return PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR
        }
    }

    @C.ContentType
    fun inferContentType(uri: Uri): @C.ContentType Int {
        val scheme = uri.getScheme()
        if (scheme != null
            && (Ascii.equalsIgnoreCase("rtsp", scheme) || Ascii.equalsIgnoreCase("rtspt", scheme))
        ) {
            return C.CONTENT_TYPE_RTSP
        }

        val lastPathSegment = uri.getLastPathSegment()
        if (lastPathSegment == null) {
            return C.CONTENT_TYPE_OTHER
        }
        val lastDotIndex = lastPathSegment.lastIndexOf('.')
        if (lastDotIndex >= 0) {
            @C.ContentType val contentType: @C.ContentType Int =
                inferContentTypeForExtension(lastPathSegment.substring(lastDotIndex + 1))
            if (contentType != C.CONTENT_TYPE_OTHER) {
                // If contentType is TYPE_SS that indicates the extension is .ism or .isml and shows the ISM
                // URI is missing the "/manifest" suffix, which contains the information used to
                // disambiguate between Smooth Streaming, HLS and DASH below - so we can just return TYPE_SS
                // here without further checks.
                return contentType
            }
        }

        val ismMatcher =
            ISM_PATH_PATTERN.matcher(uri.path!!)
        if (ismMatcher.matches()) {
            val extensions = ismMatcher.group(2)
            if (extensions != null) {
                if (extensions.contains(ISM_DASH_FORMAT_EXTENSION)) {
                    return C.CONTENT_TYPE_DASH
                } else if (extensions.contains(ISM_HLS_FORMAT_EXTENSION)) {
                    return C.CONTENT_TYPE_HLS
                }
            }
            return C.CONTENT_TYPE_SS
        }

        return C.CONTENT_TYPE_OTHER
    }

    @C.ContentType
    fun inferContentTypeForExtension(fileExtension: String): @C.ContentType Int {
        var fileExtension = fileExtension
        fileExtension = Ascii.toLowerCase(fileExtension)
        when (fileExtension) {
            "mpd" -> return C.CONTENT_TYPE_DASH
            "m3u8" -> return C.CONTENT_TYPE_HLS
            "ism", "isml" -> return C.CONTENT_TYPE_SS
            else -> return C.CONTENT_TYPE_OTHER
        }
    }

    @C.ContentType
    fun inferContentTypeForUriAndMimeType(
        uri: Uri, mimeType: String?
    ): @C.ContentType Int {
        if (mimeType == null) {
            return inferContentType(uri)
        }
        when (mimeType) {
            MimeTypes.APPLICATION_MPD -> return C.CONTENT_TYPE_DASH
            MimeTypes.APPLICATION_M3U8 -> return C.CONTENT_TYPE_HLS
            MimeTypes.APPLICATION_SS -> return C.CONTENT_TYPE_SS
            MimeTypes.APPLICATION_RTSP -> return C.CONTENT_TYPE_RTSP
            else -> return C.CONTENT_TYPE_OTHER
        }
    }

    fun getAdaptiveMimeTypeForContentType(@C.ContentType contentType: @C.ContentType Int): String? {
        when (contentType) {
            C.CONTENT_TYPE_DASH -> return MimeTypes.APPLICATION_MPD
            C.CONTENT_TYPE_HLS -> return MimeTypes.APPLICATION_M3U8
            C.CONTENT_TYPE_SS -> return MimeTypes.APPLICATION_SS
            C.CONTENT_TYPE_RTSP, C.CONTENT_TYPE_OTHER -> return null
            else -> return null
        }
    }

    fun fixSmoothStreamingIsmManifestUri(uri: Uri): Uri? {
        val path = uri.getPath()
        if (path == null) {
            return uri
        }
        val ismMatcher = ISM_PATH_PATTERN.matcher(path)
        if (ismMatcher.matches() && ismMatcher.group(1) == null) {
            // Add missing "Manifest" suffix.
            return Uri.withAppendedPath(uri, "Manifest")
        }
        return uri
    }

    fun getStringForTime(builder: StringBuilder, formatter: Formatter, timeMs: Long): String {
        var timeMs = timeMs
        if (timeMs == C.TIME_UNSET) {
            timeMs = 0
        }
        val prefix = if (timeMs < 0) "-" else ""
        timeMs = abs(timeMs)
        val totalSeconds = (timeMs + 500) / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        builder.setLength(0)
        return if (hours > 0)
            formatter.format("%s%d:%02d:%02d", prefix, hours, minutes, seconds).toString()
        else
            formatter.format("%s%02d:%02d", prefix, minutes, seconds).toString()
    }

    fun getStringForTime(timeMs: Long): String {
        val builder = StringBuilder()
        val formatter = Formatter(builder, Locale.getDefault())
        return getStringForTime(builder, formatter, timeMs)
    }

    fun escapeFileName(fileName: String): String {
        val length = fileName.length
        var charactersToEscapeCount = 0
        for (i in 0..<length) {
            if (shouldEscapeCharacter(fileName[i])) {
                charactersToEscapeCount++
            }
        }
        if (charactersToEscapeCount == 0) {
            return fileName
        }

        var i = 0
        val builder = StringBuilder(length + charactersToEscapeCount * 2)
        while (charactersToEscapeCount > 0) {
            val c = fileName.get(i++)
            if (shouldEscapeCharacter(c)) {
                builder.append('%').append(Integer.toHexString(c.code))
                charactersToEscapeCount--
            } else {
                builder.append(c)
            }
        }
        if (i < length) {
            builder.append(fileName, i, length)
        }
        return builder.toString()
    }

    private fun shouldEscapeCharacter(c: Char): Boolean {
        when (c) {
            '<', '>', ':', '"', '/', '\\', '|', '?', '*', '%' -> return true
            else -> return false
        }
    }

    fun unescapeFileName(fileName: String): String? {
        val length = fileName.length
        var percentCharacterCount = 0
        for (i in 0..<length) {
            if (fileName[i] == '%') {
                percentCharacterCount++
            }
        }
        if (percentCharacterCount == 0) {
            return fileName
        }

        val expectedLength = length - percentCharacterCount * 2
        val builder = StringBuilder(expectedLength)
        val matcher = ESCAPED_CHARACTER_PATTERN.matcher(fileName)
        var startOfNotEscaped = 0
        while (percentCharacterCount > 0 && matcher.find()) {
            val unescapedCharacter = matcher.group(1)!!.toInt(16).toChar()
            builder.append(fileName, startOfNotEscaped, matcher.start()).append(unescapedCharacter)
            startOfNotEscaped = matcher.end()
            percentCharacterCount--
        }
        if (startOfNotEscaped < length) {
            builder.append(fileName, startOfNotEscaped, length)
        }
        if (builder.length != expectedLength) {
            return null
        }
        return builder.toString()
    }

    fun getDataUriForString(mimeType: String?, data: String): Uri? {
        return Uri.parse(
            "data:" + mimeType + ";base64," + Base64.encodeToString(
                data.toByteArray(),
                Base64.NO_WRAP
            )
        )
    }

    fun recursiveDelete(fileOrDirectory: File) {
        val directoryFiles = fileOrDirectory.listFiles()
        if (directoryFiles != null) {
            for (child in directoryFiles) {
                recursiveDelete(child)
            }
        }
        fileOrDirectory.delete()
    }

    @Throws(IOException::class)
    fun createTempDirectory(context: Context, prefix: String): File {
        val tempFile = createTempFile(context, prefix)
        tempFile.delete() // Delete the temp file.
        tempFile.mkdir() // Create a directory with the same name.
        return tempFile
    }

    @Throws(IOException::class)
    fun createTempFile(context: Context, prefix: String): File {
        return File.createTempFile(
            prefix,
            null,
            context.cacheDir
        )
    }

    fun crc32(bytes: ByteArray, start: Int, end: Int, initialValue: Int): Int {
        var initialValue = initialValue
        for (i in start..<end) {
            initialValue =
                ((initialValue shl 8)
                        xor CRC32_BYTES_MSBF[((initialValue ushr 24) xor (bytes[i].toInt() and 0xFF)) and 0xFF])
        }
        return initialValue
    }

    fun crc16(bytes: ByteArray, start: Int, end: Int, initialValue: Int): Int {
        var initialValue = initialValue
        for (i in start..<end) {
            val value = UnsignedBytes.toInt(bytes[i])
            // Process one message byte to update the current CRC-16 value.
            initialValue = crc16UpdateFourBits(value shr 4, initialValue) // High nibble first.
            initialValue = crc16UpdateFourBits(value and 0x0F, initialValue) // Low nibble.
        }
        return initialValue
    }

    private fun crc16UpdateFourBits(value: Int, crc16Register: Int): Int {
        // Step one, extract the most significant 4 bits of the CRC register.
        var crc16Register = crc16Register
        var mostSignificant4Bits = (crc16Register shr 12) and 0xFF
        // XOR in the Message Data into the extracted bits.
        mostSignificant4Bits = (mostSignificant4Bits xor value) and 0xFF
        // Shift the CRC register left 4 bits.
        crc16Register =
            (crc16Register shl 4) and 0xFFFF // Handle as 16 bit, discard any sign extension.
        // Do the table look-ups and XOR the result into the CRC tables.
        crc16Register = (crc16Register xor CRC16_BYTES_MSBF[mostSignificant4Bits]) and 0xFFFF

        return crc16Register
    }

    fun crc8(bytes: ByteArray, start: Int, end: Int, initialValue: Int): Int {
        var initialValue = initialValue
        for (i in start..<end) {
            initialValue = CRC8_BYTES_MSBF[initialValue xor (bytes[i].toInt() and 0xFF)]
        }
        return initialValue
    }

    fun gzip(input: ByteArray?): ByteArray {
        val output = ByteArrayOutputStream()
        try {
            GZIPOutputStream(output).use { os ->
                os.write(input)
            }
        } catch (e: IOException) {
            // A ByteArrayOutputStream wrapped in a GZipOutputStream should never throw IOException since
            // no I/O is happening.
            throw IllegalStateException(e)
        }
        return output.toByteArray()
    }

    fun getBigEndianInt(buffer: ByteBuffer, index: Int): Int {
        val value = buffer.getInt(index)
        return if (buffer.order() == ByteOrder.BIG_ENDIAN) value else Integer.reverseBytes(value)
    }

    fun createReadOnlyByteBuffer(byteBuffer: ByteBuffer): ByteBuffer {
        return byteBuffer.asReadOnlyBuffer().order(byteBuffer.order())
    }

    fun getCountryCode(context: Context?): String {
        if (context != null) {
            val telephonyManager =
                context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager?
            if (telephonyManager != null) {
                val countryCode = telephonyManager.getNetworkCountryIso()
                if (!TextUtils.isEmpty(countryCode)) {
                    return Ascii.toUpperCase(countryCode)
                }
            }
        }
        return Ascii.toUpperCase(Locale.getDefault().getCountry())
    }

    val systemLanguageCodes: Array<String>
        get() {
            val systemLocales: Array<String> =
                systemLocales
            for (i in systemLocales.indices) {
                systemLocales[i] = normalizeLanguageCode(systemLocales[i])
            }
            return systemLocales
        }

    val defaultDisplayLocale: Locale
        get() = if (Build.VERSION.SDK_INT >= 24)
            Locale.getDefault(Locale.Category.DISPLAY)
        else
            Locale.getDefault()

    fun inflate(
        input: ParsableByteArray, output: ParsableByteArray, inflater: Inflater?
    ): Boolean {
        var inflater = inflater
        if (input.bytesLeft() == 0) {
            return false
        }
        if (output.capacity() < input.bytesLeft()) {
            output.ensureCapacity(2 * input.bytesLeft())
        }
        if (inflater == null) {
            inflater = Inflater()
        }
        inflater.setInput(input.data, input.getPosition(), input.bytesLeft())
        try {
            var outputSize = 0
            while (true) {
                outputSize +=
                    inflater.inflate(output.data, outputSize, output.capacity() - outputSize)
                if (inflater.finished()) {
                    output.setLimit(outputSize)
                    return true
                }
                if (inflater.needsDictionary() || inflater.needsInput()) {
                    return false
                }
                if (outputSize == output.capacity()) {
                    output.ensureCapacity(output.capacity() * 2)
                }
            }
        } catch (e: DataFormatException) {
            return false
        } finally {
            inflater.reset()
        }
    }

    fun maybeInflate(
        input: ParsableByteArray, output: ParsableByteArray, inflater: Inflater?
    ): Boolean {
        return input.bytesLeft() > 0 && input.peekUnsignedByte() == ZLIB_INFLATE_HEADER && inflate(
            input,
            output,
            inflater
        )
    }

    fun isTv(context: Context): Boolean {
        // See https://developer.android.com/training/tv/start/hardware.html#runtime-check.
        val uiModeManager =
            context.getApplicationContext()
                .getSystemService(Context.UI_MODE_SERVICE) as UiModeManager?
        return uiModeManager != null
                && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION
    }

    fun isAutomotive(context: Context): Boolean {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    }

    fun isWear(context: Context): Boolean {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH)
    }

    fun getCurrentDisplayModeSize(context: Context): Point {
        var defaultDisplay: Display? = null
        val displayManager =
            context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager?
        // We don't expect displayManager to ever be null, so this check is just precautionary.
        if (displayManager != null) {
            defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        }
        if (defaultDisplay == null) {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            defaultDisplay = windowManager.defaultDisplay
        }
        return getCurrentDisplayModeSize(context, defaultDisplay)
    }

    fun getCurrentDisplayModeSize(context: Context, display: Display): Point {
        if (display.getDisplayId() == Display.DEFAULT_DISPLAY && isTv(context)) {
            // On Android TVs it's common for the UI to be driven at a lower resolution than the physical
            // resolution of the display (e.g., driving the UI at 1080p when the display is 4K).
            // SurfaceView outputs are still able to use the full physical resolution on such devices.
            //
            // Prior to API level 26, the Display object did not provide a way to obtain the true physical
            // resolution of the display. From API level 26, Display.getMode().getPhysical[Width|Height]
            // is expected to return the display's true physical resolution, but we still see devices
            // setting their hardware compositor output size incorrectly, which makes this unreliable.
            // Hence for TV devices, we try and read the display's true physical resolution from system
            // properties.
            //
            // From API level 28, Treble may prevent the system from writing sys.display-size, so we check
            // vendor.display-size instead.
            val displaySize =
                if (Build.VERSION.SDK_INT < 28)
                    getSystemProperty("sys.display-size")
                else
                    getSystemProperty("vendor.display-size")
            // If we managed to read the display size, attempt to parse it.
            if (!TextUtils.isEmpty(displaySize)) {
                try {
                    val displaySizeParts = split(displaySize!!.trim { it <= ' ' }, "x")
                    if (displaySizeParts.size == 2) {
                        val width = displaySizeParts[0]!!.toInt()
                        val height = displaySizeParts[1]!!.toInt()
                        if (width > 0 && height > 0) {
                            return Point(width, height)
                        }
                    }
                } catch (e: NumberFormatException) {
                    // Do nothing.
                }
                Log.e(TAG, "Invalid display size: " + displaySize)
            }

            // Sony Android TVs advertise support for 4k output via a system feature.
            if ("Sony" == Build.MANUFACTURER
                && Build.MODEL.startsWith("BRAVIA")
                && context.getPackageManager().hasSystemFeature("com.sony.dtv.hardware.panel.qfhd")
            ) {
                return Point(3840, 2160)
            }
        }

        val displaySize = Point()
        getDisplaySize(display, displaySize)
        return displaySize
    }

    fun getTrackTypeString(trackType: @C.TrackType Int): String {
        when (trackType) {
            C.TRACK_TYPE_DEFAULT -> return "default"
            C.TRACK_TYPE_AUDIO -> return "audio"
            C.TRACK_TYPE_VIDEO -> return "video"
            C.TRACK_TYPE_TEXT -> return "text"
            C.TRACK_TYPE_IMAGE -> return "image"
            C.TRACK_TYPE_METADATA -> return "metadata"
            C.TRACK_TYPE_CAMERA_MOTION -> return "camera motion"
            C.TRACK_TYPE_NONE -> return "none"
            C.TRACK_TYPE_UNKNOWN -> return "unknown"
            else -> return if (trackType >= C.TRACK_TYPE_CUSTOM_BASE) "custom (" + trackType + ")" else "?"
        }
    }

    fun isBitmapFactorySupportedMimeType(mimeType: String): Boolean {
        when (mimeType) {
            MimeTypes.IMAGE_PNG, MimeTypes.IMAGE_JPEG, MimeTypes.IMAGE_BMP, MimeTypes.IMAGE_WEBP -> return true
            MimeTypes.IMAGE_HEIF, MimeTypes.IMAGE_HEIC -> return Build.VERSION.SDK_INT >= 26
            MimeTypes.IMAGE_AVIF -> return Build.VERSION.SDK_INT >= 34
            else -> return false
        }
    }

    fun getSelectionFlagStrings(@SelectionFlags selectionFlags: @SelectionFlags Int): MutableList<String?> {
        val result: MutableList<String?> = ArrayList<String?>()
        // LINT.IfChange(selection_flags)
        if ((selectionFlags and C.SELECTION_FLAG_AUTOSELECT) != 0) {
            result.add("auto")
        }
        if ((selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0) {
            result.add("default")
        }
        if ((selectionFlags and C.SELECTION_FLAG_FORCED) != 0) {
            result.add("forced")
        }
        // LINT.ThenChange()
        return result
    }

    fun getRoleFlagStrings(@RoleFlags roleFlags: @RoleFlags Int): MutableList<String?> {
        val result: MutableList<String?> = ArrayList<String?>()
        // LINT.IfChange(role_flags)
        if ((roleFlags and C.ROLE_FLAG_MAIN) != 0) {
            result.add("main")
        }
        if ((roleFlags and C.ROLE_FLAG_ALTERNATE) != 0) {
            result.add("alt")
        }
        if ((roleFlags and C.ROLE_FLAG_SUPPLEMENTARY) != 0) {
            result.add("supplementary")
        }
        if ((roleFlags and C.ROLE_FLAG_COMMENTARY) != 0) {
            result.add("commentary")
        }
        if ((roleFlags and C.ROLE_FLAG_DUB) != 0) {
            result.add("dub")
        }
        if ((roleFlags and C.ROLE_FLAG_EMERGENCY) != 0) {
            result.add("emergency")
        }
        if ((roleFlags and C.ROLE_FLAG_CAPTION) != 0) {
            result.add("caption")
        }
        if ((roleFlags and C.ROLE_FLAG_SUBTITLE) != 0) {
            result.add("subtitle")
        }
        if ((roleFlags and C.ROLE_FLAG_SIGN) != 0) {
            result.add("sign")
        }
        if ((roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO) != 0) {
            result.add("describes-video")
        }
        if ((roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0) {
            result.add("describes-music")
        }
        if ((roleFlags and C.ROLE_FLAG_ENHANCED_DIALOG_INTELLIGIBILITY) != 0) {
            result.add("enhanced-intelligibility")
        }
        if ((roleFlags and C.ROLE_FLAG_TRANSCRIBES_DIALOG) != 0) {
            result.add("transcribes-dialog")
        }
        if ((roleFlags and C.ROLE_FLAG_EASY_TO_READ) != 0) {
            result.add("easy-read")
        }
        if ((roleFlags and C.ROLE_FLAG_TRICK_PLAY) != 0) {
            result.add("trick-play")
        }
        if ((roleFlags and C.ROLE_FLAG_AUXILIARY) != 0) {
            result.add("auxiliary")
        }
        // LINT.ThenChange()
        return result
    }

    fun getAuxiliaryTrackTypeString(@AuxiliaryTrackType auxiliaryTrackType: @AuxiliaryTrackType Int): String {
        // LINT.IfChange(auxiliary_track_type)
        when (auxiliaryTrackType) {
            C.AUXILIARY_TRACK_TYPE_UNDEFINED -> return "undefined"
            C.AUXILIARY_TRACK_TYPE_ORIGINAL -> return "original"
            C.AUXILIARY_TRACK_TYPE_DEPTH_LINEAR -> return "depth-linear"
            C.AUXILIARY_TRACK_TYPE_DEPTH_INVERSE -> return "depth-inverse"
            C.AUXILIARY_TRACK_TYPE_DEPTH_METADATA -> return "depth metadata"
            else -> throw IllegalStateException("Unsupported auxiliary track type")
        }
    }

    fun getNowUnixTimeMs(elapsedRealtimeEpochOffsetMs: Long): Long {
        return if (elapsedRealtimeEpochOffsetMs == C.TIME_UNSET)
            System.currentTimeMillis()
        else
            SystemClock.elapsedRealtime() + elapsedRealtimeEpochOffsetMs
    }

    fun getInt24(buffer: ByteBuffer, index: Int): Int {
        val component1 =
            buffer.get(if (buffer.order() == ByteOrder.BIG_ENDIAN) index else index + 2)
        val component2 = buffer.get(index + 1)
        val component3 =
            buffer.get(if (buffer.order() == ByteOrder.BIG_ENDIAN) index + 2 else index)
        return ((((component1.toInt() shl 24) and -0x1000000)
                or ((component2.toInt() shl 16) and 0xff0000)
                or ((component3.toInt() shl 8) and 0xff00))
                shr 8)
    }

    fun putInt24(buffer: ByteBuffer, value: Int) {
        Preconditions.checkArgument(
            (value and 0xffffff.inv()) == 0 || (value and 0x7fffff.inv()) == -0x800000,
            "Value out of range of 24-bit integer: %s",
            Integer.toHexString(value)
        )
        Preconditions.checkArgument(buffer.remaining() >= 3)
        val component1 =
            if (buffer.order() == ByteOrder.BIG_ENDIAN) ((value and 0xFF0000) shr 16).toByte() else (value and 0xFF).toByte()
        val component2 = ((value and 0xFF00) shr 8).toByte()
        val component3 =
            if (buffer.order() == ByteOrder.BIG_ENDIAN) (value and 0xFF).toByte() else ((value and 0xFF0000) shr 16).toByte()
        buffer.put(component1).put(component2).put(component3)
    }

    fun <T : Any> moveItems(
        items: MutableList<T>, fromIndex: Int, toIndex: Int, newFromIndex: Int
    ) {
        val removedItems = ArrayDeque<T>()
        val removedItemsLength = toIndex - fromIndex
        for (i in removedItemsLength - 1 downTo 0) {
            removedItems.addFirst(items.removeAt(fromIndex + i))
        }
        items.addAll(min(newFromIndex, items.size), removedItems)
    }

    fun tableExists(database: SQLiteDatabase?, tableName: String?): Boolean {
        val count =
            DatabaseUtils.queryNumEntries(
                database, "sqlite_master", "tbl_name = ?", arrayOf<String?>(tableName)
            )
        return count > 0
    }

    fun getErrorCodeFromPlatformDiagnosticsInfo(diagnosticsInfo: String?): Int {
        if (diagnosticsInfo == null) {
            return 0
        }
        val strings = split(diagnosticsInfo, "_")
        val length = strings.size
        if (length < 2) {
            return 0
        }
        val digitsSection = strings[length - 1]
        val isNegative = length >= 3 && "neg" == strings[length - 2]
        try {
            val errorCode = digitsSection.toInt()
            return if (isNegative) -errorCode else errorCode
        } catch (e: NumberFormatException) {
            return 0
        }
    }

    fun getBufferFlagsFromMediaCodecFlags(mediaCodecFlags: Int): @BufferFlags Int {
        var flags: @BufferFlags Int = 0
        if ((mediaCodecFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME) == MediaCodec.BUFFER_FLAG_KEY_FRAME) {
            flags = flags or C.BUFFER_FLAG_KEY_FRAME
        }
        if ((mediaCodecFlags and MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            == MediaCodec.BUFFER_FLAG_END_OF_STREAM
        ) {
            flags = flags or C.BUFFER_FLAG_END_OF_STREAM
        }
        return flags
    }

    fun isFrameDropAllowedOnSurfaceInput(context: Context): Boolean {
        // Prior to API 29, decoders may drop frames to keep their output surface from growing out of
        // bounds. From API 29, if the app targets API 29 or later, the {@link
        // MediaFormat#KEY_ALLOW_FRAME_DROP} key prevents frame dropping even when the surface is
        // full.
        // Some devices might drop frames despite setting {@link
        // MediaFormat#KEY_ALLOW_FRAME_DROP} to 0. See b/307518793, b/289983935 and b/353487886.
        return Build.VERSION.SDK_INT < 29 || context.getApplicationInfo().targetSdkVersion < 29 || ((Build.VERSION.SDK_INT == 30
                && (Ascii.equalsIgnoreCase(Build.MODEL, "moto g(20)")
                || Ascii.equalsIgnoreCase(Build.MODEL, "rmx3231")))
                || (Build.VERSION.SDK_INT == 34 && Ascii.equalsIgnoreCase(Build.MODEL, "sm-x200")))
    }

    fun getMaxPendingFramesCountForMediaCodecDecoders(context: Context): Int {
        if (isFrameDropAllowedOnSurfaceInput(context)) {
            // Frame dropping is never desired, so a workaround is needed for older API levels.
            // Allow a maximum of one frame to be pending at a time to prevent frame dropping.
            // TODO(b/226330223): Investigate increasing this limit.
            return 1
        }
        // Limit the maximum amount of frames for all decoders. This is a tentative value that should be
        // large enough to avoid significant performance degradation, but small enough to bypass decoder
        // issues.
        //
        // TODO: b/278234847 - Evaluate whether this reduces decoder timeouts, and consider restoring
        // prior higher limits as appropriate.
        //
        // Some OMX decoders don't correctly track their number of output buffers available, and get
        // stuck if too many frames are rendered without being processed. This value is experimentally
        // determined. See also
        // b/213455700, b/230097284, b/229978305, and b/245491744.
        //
        // OMX video codecs should no longer exist from android.os.Build.DEVICE_INITIAL_SDK_INT 31+.
        return 5
    }

    fun getFormatSupportString(@FormatSupport formatSupport: @FormatSupport Int): String {
        when (formatSupport) {
            C.FORMAT_HANDLED -> return "YES"
            C.FORMAT_EXCEEDS_CAPABILITIES -> return "NO_EXCEEDS_CAPABILITIES"
            C.FORMAT_UNSUPPORTED_DRM -> return "NO_UNSUPPORTED_DRM"
            C.FORMAT_UNSUPPORTED_SUBTYPE -> return "NO_UNSUPPORTED_SUBTYPE"
            C.FORMAT_UNSUPPORTED_TYPE -> return "NO"
            else -> throw IllegalStateException()
        }
    }

    fun getAvailableCommands(
        player: Player,
        permanentAvailableCommands: Player.Commands
    ): Player.Commands {
        val isPlayingAd = player.isPlayingAd
        val isCurrentMediaItemSeekable = player.isCurrentMediaItemSeekable
        val hasPreviousMediaItem = player.hasPreviousMediaItem()
        val hasNextMediaItem = player.hasNextMediaItem()
        val isCurrentMediaItemLive = player.isCurrentMediaItemLive
        val isCurrentMediaItemDynamic = player.isCurrentMediaItemDynamic
        val isTimelineEmpty = player.currentTimeline.isEmpty
        return Player.Commands.Builder()
            .addAll(permanentAvailableCommands)
            .addIf(Player.COMMAND_SEEK_TO_DEFAULT_POSITION, !isPlayingAd)
            .addIf(
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                isCurrentMediaItemSeekable && !isPlayingAd
            )
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, hasPreviousMediaItem && !isPlayingAd)
            .addIf(
                Player.COMMAND_SEEK_TO_PREVIOUS,
                !isTimelineEmpty && (hasPreviousMediaItem || !isCurrentMediaItemLive || isCurrentMediaItemSeekable)
                        && !isPlayingAd
            )
            .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, hasNextMediaItem && !isPlayingAd)
            .addIf(
                Player.COMMAND_SEEK_TO_NEXT,
                !isTimelineEmpty && (hasNextMediaItem || (isCurrentMediaItemLive && isCurrentMediaItemDynamic))
                        && !isPlayingAd
            )
            .addIf(Player.COMMAND_SEEK_TO_MEDIA_ITEM, !isPlayingAd)
            .addIf(Player.COMMAND_SEEK_BACK, isCurrentMediaItemSeekable && !isPlayingAd)
            .addIf(Player.COMMAND_SEEK_FORWARD, isCurrentMediaItemSeekable && !isPlayingAd)
            .build()
    }

    fun sum(vararg summands: Long): Long {
        var sum: Long = 0
        for (summand in summands) {
            sum += summand
        }
        return sum
    }

    fun getDrawable(
        context: Context, resources: Resources, @DrawableRes drawableRes: Int
    ): Drawable? {
        return resources.getDrawable(drawableRes, context.getTheme())
    }

    fun intToStringMaxRadix(i: Int): String {
        return i.toString(Character.MAX_RADIX.coerceIn(2, 36))
    }

    fun shouldEnablePlayPauseButton(player: Player?): Boolean {
        if (player == null) {
            return false
        }
        @Player.State val playbackState: @Player.State Int = player.playbackState
        val hasMediaItem =
            !(player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                    && player.currentMediaItem == null)
        val canPlayPause = player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)
        val canPrepare =
            playbackState == Player.STATE_IDLE && player.isCommandAvailable(Player.COMMAND_PREPARE)
        val canSeekToDefault =
            playbackState == Player.STATE_ENDED
                    && player.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)

        return hasMediaItem && (canPlayPause || canPrepare || canSeekToDefault)
    }

    fun shouldShowPlayButton(player: Player?): Boolean {
        return shouldShowPlayButton(player,  true)
    }

    fun shouldShowPlayButton(
        player: Player?, shouldShowPlayIfSuppressed: Boolean
    ): Boolean {
        return player == null || !player.playWhenReady || player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED || (shouldShowPlayIfSuppressed
                && player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE && (player.playbackSuppressionReason
                != Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING))
    }

    fun handlePlayButtonAction(player: Player?): Boolean {
        if (player == null) {
            return false
        }
        @Player.State val state: @Player.State Int = player.playbackState
        var methodTriggered = false
        if (state == Player.STATE_IDLE && player.isCommandAvailable(Player.COMMAND_PREPARE)) {
            player.prepare()
            methodTriggered = true
        } else if (state == Player.STATE_ENDED
            && player.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
        ) {
            player.seekToDefaultPosition()
            methodTriggered = true
        }
        if (player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) {
            player.play()
            methodTriggered = true
        }
        return methodTriggered
    }

    fun handlePauseButtonAction(player: Player?): Boolean {
        if (player != null && player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) {
            player.pause()
            return true
        }
        return false
    }

    fun handlePlayPauseButtonAction(
        player: Player?, playIfSuppressed: Boolean = true
    ): Boolean {
        if (shouldShowPlayButton(player, playIfSuppressed)) {
            return handlePlayButtonAction(player)
        } else {
            return handlePauseButtonAction(player)
        }
    }

    @CheckReturnValue
    fun convertToNullIfInvalid(bundle: Bundle?): Bundle? {
        if (bundle == null) {
            return null
        }
        // Handle cases where the Bundle doesn't have a valid class loader. See b/110768808.
        bundle.classLoader = Util::class.java.classLoader
        try {
            // Force validation.
            bundle.isEmpty
            return bundle
        } catch (e: RuntimeException) {
            Log.e(TAG, "Ignoring invalid bundle", e)
            return null
        }
    }

    fun getSystemProperty(name: String?): String? {
        try {
            @SuppressLint("PrivateApi") val systemProperties =
                Class.forName("android.os.SystemProperties")
            val getMethod = systemProperties.getMethod("get", String::class.java)
            return getMethod.invoke(systemProperties, name) as String?
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read system property " + name, e)
            return null
        }
    }

    private fun getDisplaySize(display: Display, outSize: Point) {
        val mode = display.getMode()
        outSize.x = mode.getPhysicalWidth()
        outSize.y = mode.getPhysicalHeight()
    }

    private val systemLocales: Array<String>
        get() {
            val config =
                Resources.getSystem().getConfiguration()
            return if (Build.VERSION.SDK_INT >= 24)
                getSystemLocalesV24(config)
            else
                arrayOf<String>(
                    getLocaleLanguageTag(config.locale)
                )
        }

    @RequiresApi(24)
    private fun getSystemLocalesV24(config: Configuration): Array<String> {
        return split(config.getLocales().toLanguageTags(), ",")
    }

    private fun createIsoLanguageReplacementMap(): HashMap<String?, String?> {
        val iso2Languages = Locale.getISOLanguages()
        val replacedLanguages =
            HashMap<String?, String?>( 
                iso2Languages.size + additionalIsoLanguageReplacements.size
            )
        for (iso2 in iso2Languages) {
            try {
                // This returns the ISO 639-2/T code for the language.
                val iso3 = Locale(iso2).getISO3Language()
                if (!TextUtils.isEmpty(iso3)) {
                    replacedLanguages.put(iso3, iso2)
                }
            } catch (e: MissingResourceException) {
                // Shouldn't happen for list of known languages, but we don't want to throw either.
            }
        }
        // Add additional replacement mappings.
        var i = 0
        while (i < additionalIsoLanguageReplacements.size) {
            replacedLanguages.put(
                additionalIsoLanguageReplacements[i], additionalIsoLanguageReplacements[i + 1]
            )
            i += 2
        }
        return replacedLanguages
    }

    private fun requestExternalStoragePermission(activity: Activity): Boolean {
        if (activity.checkSelfPermission(permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(
                arrayOf<String>(permission.READ_EXTERNAL_STORAGE),  0
            )
            return true
        }
        return false
    }

    @RequiresApi(api = 33)
    private fun requestReadMediaPermissions(activity: Activity): Boolean {
        if ((activity.checkSelfPermission(permission.READ_MEDIA_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) || (activity.checkSelfPermission(
                permission.READ_MEDIA_VIDEO
            )
                    != PackageManager.PERMISSION_GRANTED) || (activity.checkSelfPermission(
                permission.READ_MEDIA_IMAGES
            )
                    != PackageManager.PERMISSION_GRANTED)
        ) {
            activity.requestPermissions(
                arrayOf<String>(
                    permission.READ_MEDIA_AUDIO,
                    permission.READ_MEDIA_IMAGES,
                    permission.READ_MEDIA_VIDEO
                ),  
                0
            )
            return true
        }
        return false
    }

    @RequiresApi(api = 24)
    private fun isTrafficRestricted(uri: Uri): Boolean {
        return "http" == uri.getScheme()
                && !NetworkSecurityPolicy.getInstance()
            .isCleartextTrafficPermitted(uri.host)
    }

    private fun maybeReplaceLegacyLanguageTags(languageTag: String): String {
        var i = 0
        while (i < isoLegacyTagReplacements.size) {
            if (languageTag.startsWith(isoLegacyTagReplacements[i])) {
                return (isoLegacyTagReplacements[i + 1]
                        + languageTag.substring( isoLegacyTagReplacements[i].length))
            }
            i += 2
        }
        return languageTag
    }

    fun <T> ignoreFuture(unused: Future<T?>?) {
        // Ignore return value of the future.
    }

    // Additional mapping from ISO3 to ISO2 language codes.
    private val additionalIsoLanguageReplacements: Array<String> =
        arrayOf<String>( // Bibliographical codes defined in ISO 639-2/B, replaced by terminological code defined in
            // ISO 639-2/T. See https://en.wikipedia.org/wiki/List_of_ISO_639-2_codes.
            "alb",
            "sq",
            "arm",
            "hy",
            "baq",
            "eu",
            "bur",
            "my",
            "tib",
            "bo",
            "chi",
            "zh",
            "cze",
            "cs",
            "dut",
            "nl",
            "ger",
            "de",
            "gre",
            "el",
            "fre",
            "fr",
            "geo",
            "ka",
            "ice",
            "is",
            "mac",
            "mk",
            "mao",
            "mi",
            "may",
            "ms",
            "per",
            "fa",
            "rum",
            "ro",
            "scc",
            "hbs-srp",
            "slo",
            "sk",
            "wel",
            "cy",  // Deprecated 2-letter codes, replaced by modern equivalent (including macrolanguage)
            // See https://en.wikipedia.org/wiki/List_of_ISO_639-1_codes, "ISO 639:1988"
            "id",
            "ms-ind",
            "iw",
            "he",
            "heb",
            "he",
            "ji",
            "yi",  // Individual macrolanguage codes mapped back to full macrolanguage code.
            // See https://en.wikipedia.org/wiki/ISO_639_macrolanguage
            "arb",
            "ar-arb",
            "in",
            "ms-ind",
            "ind",
            "ms-ind",
            "nb",
            "no-nob",
            "nob",
            "no-nob",
            "nn",
            "no-nno",
            "nno",
            "no-nno",
            "tw",
            "ak-twi",
            "twi",
            "ak-twi",
            "bs",
            "hbs-bos",
            "bos",
            "hbs-bos",
            "hr",
            "hbs-hrv",
            "hrv",
            "hbs-hrv",
            "sr",
            "hbs-srp",
            "srp",
            "hbs-srp",
            "cmn",
            "zh-cmn",
            "hak",
            "zh-hak",
            "nan",
            "zh-nan",
            "hsn",
            "zh-hsn"
        )

    // Legacy tags that have been replaced by modern equivalents (including macrolanguage)
    // See https://www.iana.org/assignments/language-subtag-registry/language-subtag-registry.
    private val isoLegacyTagReplacements: Array<String> = arrayOf<String>(
        "i-lux", "lb",
        "i-hak", "zh-hak",
        "i-navajo", "nv",
        "no-bok", "no-nob",
        "no-nyn", "no-nno",
        "zh-guoyu", "zh-cmn",
        "zh-hakka", "zh-hak",
        "zh-min-nan", "zh-nan",
        "zh-xiang", "zh-hsn"
    )

    private val CRC32_BYTES_MSBF = intArrayOf(
        0X00000000, 0X04C11DB7, 0X09823B6E, 0X0D4326D9, 0X130476DC, 0X17C56B6B, 0X1A864DB2,
        0X1E475005, 0X2608EDB8, 0X22C9F00F, 0X2F8AD6D6, 0X2B4BCB61, 0X350C9B64, 0X31CD86D3,
        0X3C8EA00A, 0X384FBDBD, 0X4C11DB70, 0X48D0C6C7, 0X4593E01E, 0X4152FDA9, 0X5F15ADAC,
        0X5BD4B01B, 0X569796C2, 0X52568B75, 0X6A1936C8, 0X6ED82B7F, 0X639B0DA6, 0X675A1011,
        0X791D4014, 0X7DDC5DA3, 0X709F7B7A, 0X745E66CD, -0x67dc4920, -0x631d54a9, -0x6e5e7272,
        -0x6a9f6fc7, -0x74d83fc4, -0x70192275, -0x7d5a04ae, -0x799b191b, -0x41d4a4a8, -0x4515b911,
        -0x48569fca, -0x4c97827f, -0x52d0d27c, -0x5611cfcd, -0x5b52e916, -0x5f93f4a3, -0x2bcd9270,
        -0x2f0c8fd9, -0x224fa902, -0x268eb4b7, -0x38c9e4b4, -0x3c08f905, -0x314bdfde, -0x358ac26b,
        -0xdc57fd8, -0x9046261, -0x44744ba, -0x86590f, -0x1ec1090c, -0x1a0014bd, -0x17433266,
        -0x13822fd3, 0X34867077, 0X30476DC0, 0X3D044B19, 0X39C556AE, 0X278206AB, 0X23431B1C,
        0X2E003DC5, 0X2AC12072, 0X128E9DCF, 0X164F8078, 0X1B0CA6A1, 0X1FCDBB16, 0X018AEB13,
        0X054BF6A4, 0X0808D07D, 0X0CC9CDCA, 0X7897AB07, 0X7C56B6B0, 0X71159069, 0X75D48DDE,
        0X6B93DDDB, 0X6F52C06C, 0X6211E6B5, 0X66D0FB02, 0X5E9F46BF, 0X5A5E5B08, 0X571D7DD1,
        0X53DC6066, 0X4D9B3063, 0X495A2DD4, 0X44190B0D, 0X40D816BA, -0x535a3969, -0x579b24e0,
        -0x5ad80207, -0x5e191fb2, -0x405e4fb5, -0x449f5204, -0x49dc74db, -0x4d1d696e, -0x7552d4d1,
        -0x7193c968, -0x7cd0efbf, -0x7811f20a, -0x6656a20d, -0x6297bfbc, -0x6fd49963, -0x6b1584d6,
        -0x1f4be219, -0x1b8affb0, -0x16c9d977, -0x1208c4c2, -0xc4f94c5, -0x88e8974, -0x5cdafab,
        -0x10cb21e, -0x39430fa1, -0x3d821218, -0x30c134cf, -0x3400297a, -0x2a47797d, -0x2e8664cc,
        -0x23c54213, -0x27045fa6, 0X690CE0EE, 0X6DCDFD59, 0X608EDB80, 0X644FC637, 0X7A089632,
        0X7EC98B85, 0X738AAD5C, 0X774BB0EB, 0X4F040D56, 0X4BC510E1, 0X46863638, 0X42472B8F,
        0X5C007B8A, 0X58C1663D, 0X558240E4, 0X51435D53, 0X251D3B9E, 0X21DC2629, 0X2C9F00F0,
        0X285E1D47, 0X36194D42, 0X32D850F5, 0X3F9B762C, 0X3B5A6B9B, 0X0315D626, 0X07D4CB91,
        0X0A97ED48, 0X0E56F0FF, 0X1011A0FA, 0X14D0BD4D, 0X19939B94, 0X1D528623, -0xed0a9f2,
        -0xa11b447, -0x75292a0, -0x3938f29, -0x1dd4df2e, -0x1915c29b, -0x1456e444, -0x1097f9f5,
        -0x28d8444a, -0x2c1959ff, -0x215a7f28, -0x259b6291, -0x3bdc3296, -0x3f1d2f23, -0x325e09fc,
        -0x369f144d, -0x42c17282, -0x46006f37, -0x4b4349f0, -0x4f825459, -0x51c5045e, -0x550419eb,
        -0x58473f34, -0x5c862285, -0x64c99f3a, -0x6008828f, -0x6d4ba458, -0x698ab9e1, -0x77cde9e6,
        -0x730cf453, -0x7e4fd28c, -0x7a8ecf3d, 0X5D8A9099, 0X594B8D2E, 0X5408ABF7, 0X50C9B640,
        0X4E8EE645, 0X4A4FFBF2, 0X470CDD2B, 0X43CDC09C, 0X7B827D21, 0X7F436096, 0X7200464F,
        0X76C15BF8, 0X68860BFD, 0X6C47164A, 0X61043093, 0X65C52D24, 0X119B4BE9, 0X155A565E,
        0X18197087, 0X1CD86D30, 0X029F3D35, 0X065E2082, 0X0B1D065B, 0X0FDC1BEC, 0X3793A651,
        0X3352BBE6, 0X3E119D3F, 0X3AD08088, 0X2497D08D, 0X2056CD3A, 0X2D15EBE3, 0X29D4F654,
        -0x3a56d987, -0x3e97c432, -0x33d4e2e9, -0x3715ff60, -0x2952af5b, -0x2d93b2ee, -0x20d09435,
        -0x24118984, -0x1c5e343f, -0x189f298a, -0x15dc0f51, -0x111d12e8, -0xf5a42e3, -0xb9b5f56,
        -0x6d8798d, -0x219643c, -0x764702f7, -0x72861f42, -0x7fc53999, -0x7b042430, -0x6543742b,
        -0x6182699e, -0x6cc14f45, -0x680052f4, -0x504fef4f, -0x548ef2fa, -0x59cdd421, -0x5d0cc998,
        -0x434b9993, -0x478a8426, -0x4ac9a2fd, -0x4e08bf4c
    )

    private val CRC16_BYTES_MSBF = intArrayOf(
        0x0000, 0x01021, 0x2042, 0x3063, 0x4084, 0x50A5, 0x60C6, 0x70E7,
        0x8108, 0x9129, 0xA14A, 0xB16B, 0xC18C, 0xD1AD, 0xE1CE, 0xF1EF
    )

    private val CRC8_BYTES_MSBF = intArrayOf(
        0x00, 0x07, 0x0E, 0x09, 0x1C, 0x1B, 0x12, 0x15, 0x38, 0x3F, 0x36, 0x31, 0x24, 0x23, 0x2A,
        0x2D, 0x70, 0x77, 0x7E, 0x79, 0x6C, 0x6B, 0x62, 0x65, 0x48, 0x4F, 0x46, 0x41, 0x54, 0x53,
        0x5A, 0x5D, 0xE0, 0xE7, 0xEE, 0xE9, 0xFC, 0xFB, 0xF2, 0xF5, 0xD8, 0xDF, 0xD6, 0xD1, 0xC4,
        0xC3, 0xCA, 0xCD, 0x90, 0x97, 0x9E, 0x99, 0x8C, 0x8B, 0x82, 0x85, 0xA8, 0xAF, 0xA6, 0xA1,
        0xB4, 0xB3, 0xBA, 0xBD, 0xC7, 0xC0, 0xC9, 0xCE, 0xDB, 0xDC, 0xD5, 0xD2, 0xFF, 0xF8, 0xF1,
        0xF6, 0xE3, 0xE4, 0xED, 0xEA, 0xB7, 0xB0, 0xB9, 0xBE, 0xAB, 0xAC, 0xA5, 0xA2, 0x8F, 0x88,
        0x81, 0x86, 0x93, 0x94, 0x9D, 0x9A, 0x27, 0x20, 0x29, 0x2E, 0x3B, 0x3C, 0x35, 0x32, 0x1F,
        0x18, 0x11, 0x16, 0x03, 0x04, 0x0D, 0x0A, 0x57, 0x50, 0x59, 0x5E, 0x4B, 0x4C, 0x45, 0x42,
        0x6F, 0x68, 0x61, 0x66, 0x73, 0x74, 0x7D, 0x7A, 0x89, 0x8E, 0x87, 0x80, 0x95, 0x92, 0x9B,
        0x9C, 0xB1, 0xB6, 0xBF, 0xB8, 0xAD, 0xAA, 0xA3, 0xA4, 0xF9, 0xFE, 0xF7, 0xF0, 0xE5, 0xE2,
        0xEB, 0xEC, 0xC1, 0xC6, 0xCF, 0xC8, 0xDD, 0xDA, 0xD3, 0xD4, 0x69, 0x6E, 0x67, 0x60, 0x75,
        0x72, 0x7B, 0x7C, 0x51, 0x56, 0x5F, 0x58, 0x4D, 0x4A, 0x43, 0x44, 0x19, 0x1E, 0x17, 0x10,
        0x05, 0x02, 0x0B, 0x0C, 0x21, 0x26, 0x2F, 0x28, 0x3D, 0x3A, 0x33, 0x34, 0x4E, 0x49, 0x40,
        0x47, 0x52, 0x55, 0x5C, 0x5B, 0x76, 0x71, 0x78, 0x7F, 0x6A, 0x6D, 0x64, 0x63, 0x3E, 0x39,
        0x30, 0x37, 0x22, 0x25, 0x2C, 0x2B, 0x06, 0x01, 0x08, 0x0F, 0x1A, 0x1D, 0x14, 0x13, 0xAE,
        0xA9, 0xA0, 0xA7, 0xB2, 0xB5, 0xBC, 0xBB, 0x96, 0x91, 0x98, 0x9F, 0x8A, 0x8D, 0x84, 0x83,
        0xDE, 0xD9, 0xD0, 0xD7, 0xC2, 0xC5, 0xCC, 0xCB, 0xE6, 0xE1, 0xE8, 0xEF, 0xFA, 0xFD, 0xF4,
        0xF3
    )

    @RequiresApi(24)
    private object Api24 {
        fun stopForeground(service: Service, removeNotification: Boolean) {
            service.stopForeground(if (removeNotification) Service.STOP_FOREGROUND_REMOVE else Service.STOP_FOREGROUND_DETACH)
        }
    }

    @RequiresApi(26)
    private object Api26 {
        fun createNotificationChannel(
            notificationManager: NotificationManager, channelId: String?, channelName: String?
        ) {
            val channel =
                NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            if (Build.VERSION.SDK_INT <= 27) {
                // API 28+ will automatically hide the app icon 'badge' for notifications using
                // Notification.MediaStyle, but we have to manually hide it for APIs 26 (when badges were
                // added) and 27.
                channel.setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    @RequiresApi(29)
    private object Api29 {
        fun startForeground(
            mediaSessionService: Service,
            notificationId: Int,
            notification: Notification,
            foregroundServiceType: Int,
            foregroundServiceManifestType: String?
        ) {
            try {
                // startForeground() will throw if the service's foregroundServiceType is not defined.
                mediaSessionService.startForeground(
                    notificationId,
                    notification,
                    foregroundServiceType
                )
            } catch (e: RuntimeException) {
                Log.e(
                    TAG,
                    "The service must be declared with a foregroundServiceType that includes "
                            + foregroundServiceManifestType
                )
                throw e
            }
        }
    }
}

fun List<Any?>.contentHashCode(): Int {
    var hash = 17
    for (index in indices) {
        hash = 31 * hash + Objects.hashCode(get(index))
    }
    return hash
}