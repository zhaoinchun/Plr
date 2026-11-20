package androidx.media3.database

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DefaultDatabaseProvider(private val sqliteOpenHelper: SQLiteOpenHelper) : DatabaseProvider {
    override fun getWritableDatabase(): SQLiteDatabase {
        return sqliteOpenHelper.readableDatabase
    }

    override fun getReadableDatabase(): SQLiteDatabase {
        return sqliteOpenHelper.readableDatabase
    }
}
