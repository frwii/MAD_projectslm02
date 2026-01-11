package edu.utem.ftmk.slm02

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class DashboardActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        // Get data from Intent
        val f1 = intent.getDoubleExtra("f1", 0.0)
        val fnr = intent.getDoubleExtra("fnr", 0.0)
        val avgLatency = intent.getLongExtra("avgLatency", 0)

        findViewById<TextView>(R.id.tvF1Score).text = String.format("%.2f", f1)
        findViewById<TextView>(R.id.tvSafetyMetric).text = String.format("%.2f%%", fnr * 100)
        findViewById<TextView>(R.id.tvAvgLatency).text = "$avgLatency ms"
    }
}