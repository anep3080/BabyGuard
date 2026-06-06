package com.example.babyguard

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class AlertRecord(
    val id: Int, 
    val timestamp: String, 
    val status: String, 
    val imageBase64: String,
    val priority: String // "LOW", "MEDIUM", "HIGH"
)

class AlertDatabaseHelper(context: Context) : SQLiteOpenHelper(context, "BabyGuardDB", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE alerts (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp TEXT, status TEXT, image TEXT, priority TEXT DEFAULT 'LOW')")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            db.execSQL("DROP TABLE IF EXISTS alerts")
            onCreate(db)
        }
    }
    fun saveAlert(timestamp: String, status: String, base64Image: String, priority: String = "LOW") {
        val values = ContentValues().apply {
            put("timestamp", timestamp)
            put("status", status)
            put("image", base64Image)
            put("priority", priority)
        }
        writableDatabase.insert("alerts", null, values)
    }
    fun getAllAlerts(): List<AlertRecord> {
        val list = mutableListOf<AlertRecord>()
        try {
            val cursor = readableDatabase.rawQuery("SELECT * FROM alerts ORDER BY id DESC LIMIT 50", null)
            if (cursor.moveToFirst()) {
                do { 
                    list.add(AlertRecord(
                        cursor.getInt(0), 
                        cursor.getString(1), 
                        cursor.getString(2), 
                        cursor.getString(3),
                        cursor.getString(4) ?: "LOW"
                    ))
                } while (cursor.moveToNext())
            }
            cursor.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }
    fun clearAllAlerts() {
        writableDatabase.execSQL("DELETE FROM alerts")
    }
}