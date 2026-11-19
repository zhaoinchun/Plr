package androidx.media3.database

import android.database.sqlite.SQLiteDatabase

interface DatabaseProvider {
    fun getWritableDatabase(): SQLiteDatabase
    fun getReadableDatabase(): SQLiteDatabase

    companion object {
        const val TABLE_PREFIX: String = "ExoPlayer"
    }
}
