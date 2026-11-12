package com.splayer.splay

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class MediaCatalogDatabase(context: Context) :
    SQLiteOpenHelper(context, "splay-library.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE media_files (
                path TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                kind TEXT NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX media_files_kind_name ON media_files(kind, name COLLATE NOCASE)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun replace(kind: MediaKind, items: List<MediaItem>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("media_files", "kind = ?", arrayOf(kind.name))
            val values = ContentValues()
            items.forEach { item ->
                values.clear()
                values.put("path", item.path)
                values.put("name", item.name)
                values.put("kind", item.kind.name)
                db.insertWithOnConflict("media_files", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun read(kind: MediaKind): List<MediaItem> {
        val result = ArrayList<MediaItem>()
        readableDatabase.query(
            "media_files",
            arrayOf("path", "name"),
            "kind = ?",
            arrayOf(kind.name),
            null,
            null,
            "name COLLATE NOCASE ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += MediaItem(cursor.getString(0), cursor.getString(1), kind)
            }
        }
        return result
    }
}
