package com.example.babyguard

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import soup.neumorphism.NeumorphCardView

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Find the new clickable cards and route them!
        findViewById<NeumorphCardView>(R.id.cardParentMode).setOnClickListener {
            startActivity(Intent(this, ParentActivity::class.java))
        }

        findViewById<NeumorphCardView>(R.id.cardCameraMode).setOnClickListener {
            startActivity(Intent(this, CameraActivity::class.java))
        }
    }
}