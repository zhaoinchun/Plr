package androidx.media3.common.util

import androidx.media3.common.util.Log.w
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class AtomicFile(private val baseName: File) {
    private val backupName: File

    init {
        backupName = File(baseName.getPath() + ".bak")
    }

    fun exists(): Boolean {
        return baseName.exists() || backupName.exists()
    }

    fun delete() {
        baseName.delete()
        backupName.delete()
    }

    @Throws(IOException::class)
    fun startWrite(): OutputStream {
        // Rename the current file so it may be used as a backup during the next read
        if (baseName.exists()) {
            if (!backupName.exists()) {
                if (!baseName.renameTo(backupName)) {
                    w(TAG, "Couldn't rename file " + baseName + " to backup file " + backupName)
                }
            } else {
                baseName.delete()
            }
        }
        var str: OutputStream
        try {
            str = AtomicFileOutputStream(baseName)
        } catch (e: FileNotFoundException) {
            val parent = baseName.getParentFile()
            if (parent == null || !parent.mkdirs()) {
                throw IOException("Couldn't create " + baseName, e)
            }
            // Try again now that we've created the parent directory.
            try {
                str = AtomicFileOutputStream(baseName)
            } catch (e2: FileNotFoundException) {
                throw IOException("Couldn't create " + baseName, e2)
            }
        }
        return str
    }

    @Throws(IOException::class)
    fun endWrite(str: OutputStream) {
        str.close()
        // If close() throws exception, the next line is skipped.
        backupName.delete()
    }

    @Throws(FileNotFoundException::class)
    fun openRead(): InputStream {
        restoreBackup()
        return FileInputStream(baseName)
    }

    private fun restoreBackup() {
        if (backupName.exists()) {
            baseName.delete()
            backupName.renameTo(baseName)
        }
    }

    private class AtomicFileOutputStream(file: File?) : OutputStream() {
        private val fileOutputStream: FileOutputStream
        private var closed = false

        init {
            fileOutputStream = FileOutputStream(file)
        }

        @Throws(IOException::class)
        override fun close() {
            if (closed) {
                return
            }
            closed = true
            flush()
            try {
                fileOutputStream.getFD().sync()
            } catch (e: IOException) {
                w(TAG, "Failed to sync file descriptor:", e)
            }
            fileOutputStream.close()
        }

        @Throws(IOException::class)
        override fun flush() {
            fileOutputStream.flush()
        }

        @Throws(IOException::class)
        override fun write(b: Int) {
            fileOutputStream.write(b)
        }

        @Throws(IOException::class)
        override fun write(b: ByteArray?) {
            fileOutputStream.write(b)
        }

        @Throws(IOException::class)
        override fun write(b: ByteArray?, off: Int, len: Int) {
            fileOutputStream.write(b, off, len)
        }
    }

    companion object {
        private const val TAG = "AtomicFile"
    }
}
