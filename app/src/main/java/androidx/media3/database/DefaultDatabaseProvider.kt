package androidx.media3.database

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DefaultDatabaseProvider(private val sqliteOpenHelper: SQLiteOpenHelper) : DatabaseProvider {
    override val writableDatabase: SQLiteDatabase?
        get() = sqliteOpenHelper.writableDatabase

    override val readableDatabase: SQLiteDatabase?
        get() = sqliteOpenHelper.readableDatabase
}
