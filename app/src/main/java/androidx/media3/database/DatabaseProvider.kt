package androidx.media3.database

import android.database.sqlite.SQLiteDatabase

interface DatabaseProvider {
    val writableDatabase: SQLiteDatabase?

    val readableDatabase: SQLiteDatabase?

    companion object {
        const val TABLE_PREFIX: String = "ExoPlayer"
    }
}
