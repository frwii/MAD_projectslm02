package edu.utem.ftmk.slm02

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class FoodDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_food_detail)

        // ===============================
        // BASIC FOOD INFORMATION
        // ===============================
        val name = intent.getStringExtra("name") ?: "-"
        val ingredients = intent.getStringExtra("ingredients") ?: "-"
        val raw = intent.getStringExtra("raw") ?: "-"
        val mapped = intent.getStringExtra("mapped") ?: "-"
        val predicted = intent.getStringExtra("predicted") ?: "-"

        // ===============================
        // INFERENCE METRICS
        // ===============================
        val latencyMs = intent.getLongExtra("latencyMs", -1L)
        val javaHeapKb = intent.getLongExtra("javaHeapKb", -1L)
        val nativeHeapKb = intent.getLongExtra("nativeHeapKb", -1L)
        val totalPssKb = intent.getLongExtra("totalPssKb", -1L)
        val ttft = intent.getLongExtra("ttft", -1L)
        val itps = intent.getLongExtra("itps", -1L)
        val otps = intent.getLongExtra("otps", -1L)
        val oet = intent.getLongExtra("oet", -1L)

        // ===============================
        // BIND BASIC DATA TO UI
        // ===============================
        findViewById<TextView>(R.id.tvFoodName).text = name
        findViewById<TextView>(R.id.tvIngredients).text = ingredients
        findViewById<TextView>(R.id.tvRawAllergens).text = raw
        findViewById<TextView>(R.id.tvMappedAllergens).text = mapped
        findViewById<TextView>(R.id.tvPredictedAllergens).text = predicted

        // ===============================
        // FORMAT & DISPLAY METRICS
        // ===============================
        val metricsText = """
            ⏱ Inference Latency
            ${latencyMs} ms

            Memory Usage
            Java Heap   : ${javaHeapKb} KB
            Native Heap : ${nativeHeapKb} KB
            Total PSS   : ${totalPssKb} KB

            ⚙ LLM Inference Metrics
            TTFT : ${ttft} ms
            ITPS : ${itps} tokens/sec
            OTPS : ${otps} tokens/sec
            OET  : ${oet} ms
        """.trimIndent()

        findViewById<TextView>(R.id.tvInferenceMetrics).text = metricsText
    }
}
