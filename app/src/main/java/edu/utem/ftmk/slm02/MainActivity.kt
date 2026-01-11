package edu.utem.ftmk.slm02

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    // JNI Declaration
    external fun inferAllergens(input: String, modelPath: String): String

    companion object {
        init {
            System.loadLibrary("native-lib")
            System.loadLibrary("ggml-base")
            System.loadLibrary("ggml-cpu")
            System.loadLibrary("llama")
        }
    }

    private lateinit var spinnerDataset: Spinner
    private lateinit var spinnerModel: Spinner
    private lateinit var btnPredict: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvLogs: TextView

    // Standardized Prompt for all 7 models
    private fun buildPrompt(ingredients: String): String {
        return """
        Task: Detect food allergens.
        Ingredients: $ingredients
        Allowed allergens: milk, egg, peanut, tree nut, wheat, soy, fish, shellfish, sesame
        Rules: Output ONLY a comma-separated list of allergens. If none are present, output EMPTY.
        """.trimIndent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerDataset = findViewById(R.id.spinnerDataset)
        spinnerModel = findViewById(R.id.spinnerModel)
        btnPredict = findViewById(R.id.btnPredict)
        progressBar = findViewById(R.id.progressBar2)
        tvLogs = findViewById(R.id.tvLogs)

        tvLogs.movementMethod = LinkMovementMethod.getInstance()

        btnPredict.setOnClickListener {
            startBenchmarking()
        }
    }

    private fun startBenchmarking() {
        val selectedSetIdx = spinnerDataset.selectedItemPosition
        val selectedModelRawName = spinnerModel.selectedItem.toString()
        val modelFileName = "$selectedModelRawName.gguf"

        btnPredict.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvLogs.text = "Initializing model: $selectedModelRawName...\n"

        thread {
            // 1. Prepare Model File
            val modelPath = copyModelFromAssets(this, modelFileName)

            // 2. Load Dataset
            val allDatasets = CsvDatasetLoader.loadDatasets(this)
            val selectedDataset = allDatasets[selectedSetIdx]

            val styledLogs = SpannableStringBuilder()
            val batchResults = mutableListOf<Pair<List<String>, List<String>>>()
            var totalLatency: Long = 0

            selectedDataset.forEachIndexed { index, item ->
                val prompt = buildPrompt(item.ingredients)

                // Metrics: Memory Before
                val javaBefore = MemoryReader.javaHeapKb()
                val nativeBefore = MemoryReader.nativeHeapKb()
                val pssBefore = MemoryReader.totalPssKb()
                val startTime = System.currentTimeMillis()

                // 3. Native Inference Call
                val rawResult = inferAllergens(prompt, modelPath)

                val latency = System.currentTimeMillis() - startTime
                totalLatency += latency

                // Parse Metrics|Output
                val parts = rawResult.split("|")
                val meta = if (parts.size > 1) parts[0] else ""
                val prediction = if (parts.size > 1) parts[1] else parts[0]

                val metricsMap = parseMetrics(meta)
                val metrics = InferenceMetrics(
                    latencyMs = latency,
                    javaHeapKb = MemoryReader.javaHeapKb() - javaBefore,
                    nativeHeapKb = MemoryReader.nativeHeapKb() - nativeBefore,
                    totalPssKb = MemoryReader.totalPssKb() - pssBefore,
                    ttft = metricsMap["TTFT_MS"] ?: -1L,
                    itps = metricsMap["ITPS"] ?: -1L,
                    otps = metricsMap["OTPS"] ?: -1L,
                    oet = metricsMap["OET_MS"] ?: -1L
                )

                // 4. Traceability & Comparison
                val groundTruth = item.allergensMapped.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                val predicted = prediction.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                batchResults.add(groundTruth to predicted)

                // 5. SAVE TO FIREBASE
                saveToFirebase(item, prediction, metrics, selectedModelRawName)

                // 6. UI Update (Clickable List)
                val startPos = styledLogs.length
                styledLogs.append("🍽 ${item.name}\n")
                styledLogs.setSpan(StyleSpan(Typeface.BOLD), startPos, styledLogs.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                styledLogs.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        val intent = Intent(this@MainActivity, FoodDetailActivity::class.java)
                        intent.putExtra("name", item.name)
                        intent.putExtra("ingredients", item.ingredients)
                        intent.putExtra("raw", item.allergensRaw)
                        intent.putExtra("mapped", item.allergensMapped)
                        intent.putExtra("predicted", prediction)
                        // Efficiency Metrics
                        intent.putExtra("latencyMs", metrics.latencyMs)
                        intent.putExtra("javaHeapKb", metrics.javaHeapKb)
                        intent.putExtra("nativeHeapKb", metrics.nativeHeapKb)
                        intent.putExtra("totalPssKb", metrics.totalPssKb)
                        intent.putExtra("ttft", metrics.ttft)
                        intent.putExtra("itps", metrics.itps)
                        intent.putExtra("otps", metrics.otps)
                        intent.putExtra("oet", metrics.oet)
                        startActivity(intent)
                    }
                }, startPos, styledLogs.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                styledLogs.append("Result: $prediction\n\n")

                runOnUiThread {
                    progressBar.progress = ((index + 1) * 100) / selectedDataset.size
                    tvLogs.text = styledLogs
                }
            }

            // 7. Performance Dashboard Navigation
            val finalStats = MetricsCalculator.calculate(batchResults)
            runOnUiThread {
                progressBar.visibility = View.GONE
                btnPredict.isEnabled = true

                val intent = Intent(this@MainActivity, DashboardActivity::class.java)
                intent.putExtra("f1", finalStats.f1Score)
                intent.putExtra("fnr", finalStats.fnr)
                intent.putExtra("avgLatency", totalLatency / selectedDataset.size)
                startActivity(intent)
            }
        }
    }

    private fun saveToFirebase(item: FoodItem, predicted: String, metrics: InferenceMetrics, modelName: String) {
        val db = FirebaseFirestore.getInstance()
        val record = hashMapOf(
            "modelName" to modelName,
            "dataId" to item.id,
            "foodName" to item.name,
            "ingredients" to item.ingredients,
            "groundTruth" to item.allergensMapped,
            "predictedAllergens" to predicted,
            "latencyMs" to metrics.latencyMs,
            "ttft" to metrics.ttft,
            "timestamp" to System.currentTimeMillis()
        )
        db.collection("benchmarks").add(record)
    }

    private fun copyModelFromAssets(context: Context, modelName: String): String {
        val outFile = File(context.filesDir, modelName)
        if (!outFile.exists()) {
            context.assets.open(modelName).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return outFile.absolutePath
    }

    private fun parseMetrics(meta: String): Map<String, Long> {
        val map = mutableMapOf<String, Long>()
        meta.split(";").forEach {
            val kv = it.split("=")
            if (kv.size == 2) map[kv[0]] = kv[1].toLongOrNull() ?: -1L
        }
        return map
    }
}