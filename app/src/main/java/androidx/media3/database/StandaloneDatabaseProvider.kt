package androidx.media3.database

import android.content.Context
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.media3.common.util.Log.e

class StandaloneDatabaseProvider(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME,  null, VERSION){
    override fun onCreate(db: SQLiteDatabase?) {
        // Features create their own tables.
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        // Features handle their own upgrades.
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        wipeDatabase(db)
    }

    companion object {
        const val DATABASE_NAME: String = "exoplayer_internal.db"

        private const val VERSION = 1
        private const val TAG = "SADatabaseProvider"

        private fun wipeDatabase(db: SQLiteDatabase) {
            val columns = arrayOf<String?>("type", "name")
            db.query(
                "sqlite_master",
                columns,  
                null,  
                null,  
                null,  
                null,  
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val type = cursor.getString(0)
                    val name = cursor.getString(1)
                    if ("sqlite_sequence" != name) {
                        // If it's not an SQL-controlled entity, drop it
                        val sql = "DROP " + type + " IF EXISTS " + name
                        try {
                            db.execSQL(sql)
                        } catch (e: SQLException) {
                            e(TAG, "Error executing " + sql, e)
                        }
                    }
                }
            }
        }
    }
}
