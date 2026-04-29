package com.example.babyguard

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class AlertRecord(val id: Int, val timestamp: String, val status: String, val imageBase64: String)

class AlertDatabaseHelper(context: Context) : SQLiteOpenHelper(context, "BabyGuardDB", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE alerts (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp TEXT, status TEXT, image TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS alerts")
        onCreate(db)
    }
    fun saveAlert(timestamp: String, status: String, base64Image: String) {
        val values = ContentValues().apply {
            put("timestamp", timestamp)
            put("status", status)
            put("image", base64Image)
        }
        writableDatabase.insert("alerts", null, values)
    }
    fun getAllAlerts(): List<AlertRecord> {
        val list = mutableListOf<AlertRecord>()
        val cursor = readableDatabase.rawQuery("SELECT * FROM alerts ORDER BY id DESC LIMIT 50", null)
        if (cursor.moveToFirst()) {
            do { list.add(AlertRecord(cursor.getInt(0), cursor.getString(1), cursor.getString(2), cursor.getString(3))) } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }
    fun clearAllAlerts() {
        writableDatabase.execSQL("DELETE FROM alerts")
    }
}