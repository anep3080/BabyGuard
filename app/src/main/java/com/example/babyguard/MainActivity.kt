package com.example.babyguard

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.widget.TextView
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

        findViewById<TextView>(R.id.btnQuitApp).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Quit BabyGuard?")
                .setMessage("This stops background monitoring and alerts on this device until you reopen the app.")
                .setPositiveButton("Quit") { _, _ -> quitApp() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /** Stops the foreground monitoring service and kills the process so nothing lingers. */
    private fun quitApp() {
        stopService(Intent(this, BabyGuardService::class.java))
        finishAffinity()
        Process.killProcess(Process.myPid())
    }
}