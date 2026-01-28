package edu.utem.ftmk.slm02

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.concurrent.thread
import kotlin.math.max
import java.io.File
import android.os.Debug
import android.app.ActivityManager
import android.content.Context



class HomeFragment : Fragment(R.layout.fragment_home) {

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
    private lateinit var btnPredict: Button
    private lateinit var btnSelectModel: Button
    private lateinit var tvSelectedModel: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvLogs: TextView
    private lateinit var tvDataLoaded: TextView

    private var selectedModelUri: Uri? = null

    private val pickModelLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                selectedModelUri = uri
                tvSelectedModel.text = getDisplayNameFromUri(uri)
            }
        }

    private fun buildPrompt(ingredients: String) = """
        Task: Detect food allergens.
        Ingredients:
        $ingredients
        Allowed allergens:
        milk, egg, peanut, tree nut, wheat, soy, fish, shellfish, sesame
        Rules:
        - Output ONLY a comma-separated list of allergens.
        - If none are present, output EMPTY.
    """.trimIndent()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        spinnerDataset = view.findViewById(R.id.spinnerDataset)
        btnPredict = view.findViewById(R.id.btnPredict)
        btnSelectModel = view.findViewById(R.id.btnSelectModel)
        tvSelectedModel = view.findViewById(R.id.tvSelectedModel)
        progressBar = view.findViewById(R.id.progressBar2)
        tvLogs = view.findViewById(R.id.tvLogs)
        tvDataLoaded = view.findViewById(R.id.textView)

        tvLogs.movementMethod = LinkMovementMethod.getInstance()

        btnSelectModel.setOnClickListener {
            pickModelLauncher.launch(arrayOf("*/*"))
        }

        btnPredict.setOnClickListener { startBenchmarking() }
    }

    private fun startBenchmarking() {
        val datasetIdx = spinnerDataset.selectedItemPosition

        if (selectedModelUri == null) {
            Toast.makeText(requireContext(), "Please select a model first", Toast.LENGTH_SHORT).show()
            return
        }

        val modelPath = copyUriToInternalStorage(requireContext(), selectedModelUri!!)
        val modelName = getDisplayNameFromUri(selectedModelUri!!)

        btnPredict.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0

        tvLogs.text = "Initializing model: $modelName...\n"
        tvDataLoaded.text = "Data loaded (0 / 0) — Model: $modelName"

        thread {
            val ctx = requireContext()
            val act = requireActivity()

            val dataset = CsvDatasetLoader.loadDatasets(ctx)[datasetIdx]
            val total = dataset.size
            val logs = SpannableStringBuilder()

            dataset.forEachIndexed { index, item ->

                val current = index + 1
                val prompt = buildPrompt(item.ingredients)

                val startNs = System.nanoTime()
                val nativeOutput = inferAllergens(prompt, modelPath)
                val latencyMs = max((System.nanoTime() - startNs) / 1_000_000, 1)

                val hasMetrics = nativeOutput.contains("|")
                val text = nativeOutput.substringBefore("|").lowercase()

                fun parse(key: String): Long =
                    if (!hasMetrics) -1L
                    else nativeOutput.substringAfter("$key=", "-1")
                        .substringBefore(";")
                        .toLongOrNull() ?: -1L

                val ttft = parse("TTFT_MS")
                val itps = parse("ITPS")
                val otps = parse("OTPS")
                val oet = parse("OET_MS")

                val allowed = setOf(
                    "milk","egg","peanut","tree nut",
                    "wheat","soy","fish","shellfish","sesame"
                )

                val predicted =
                    if (text.contains("empty")) emptyList()
                    else allowed.filter { text.contains(it) }

                val predictedText =
                    if (predicted.isEmpty()) "EMPTY"
                    else predicted.joinToString(", ")

                // ✅ MEMORY METRICS (PATCH)
                val runtime = Runtime.getRuntime()
                val javaHeapKb =
                    (runtime.totalMemory() - runtime.freeMemory()) / 1024
                val nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024

                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val memInfo = am.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))
                val totalPssKb = memInfo[0].totalPss.toLong()

                FirebaseFirestore.getInstance()
                    .collection("benchmarks")
                    .add(
                        mapOf(
                            "modelName" to modelName,
                            "dataId" to item.id,
                            "foodName" to item.name,
                            "ingredients" to item.ingredients,
                            "rawAllergens" to item.allergensRaw,
                            "mappedAllergens" to item.allergensMapped,
                            "predictedAllergens" to predictedText,

                            // ===== Efficiency =====
                            "latencyMs" to latencyMs,
                            "ttftMs" to ttft,
                            "itps" to itps,
                            "otps" to otps,
                            "oetMs" to oet,

                            // ===== Memory =====
                            "javaHeapKb" to (Runtime.getRuntime().totalMemory() / 1024),
                            "nativeHeapKb" to (android.os.Debug.getNativeHeapAllocatedSize() / 1024),
                            "pssKb" to totalPssKb,
                            "timestamp" to System.currentTimeMillis()
                        )
                    )


                val start = logs.length
                logs.append("🍽 ${item.name}\n")

                logs.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start,
                    logs.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                logs.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        val i = Intent(ctx, FoodDetailActivity::class.java)
                        i.putExtra("name", item.name)
                        i.putExtra("ingredients", item.ingredients)
                        i.putExtra("raw", item.allergensRaw)
                        i.putExtra("mapped", item.allergensMapped)
                        i.putExtra("predicted", predictedText)
                        i.putExtra("latencyMs", latencyMs)
                        i.putExtra("ttft", ttft)
                        i.putExtra("itps", itps)
                        i.putExtra("otps", otps)
                        i.putExtra("oet", oet)
                        startActivity(i)
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        ds.isUnderlineText = false
                        ds.color = 0xFF512DA8.toInt()
                    }
                }, start, logs.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                logs.append("Predicted Allergens : $predictedText\n\n")

                act.runOnUiThread {
                    progressBar.progress = (current * 100) / total
                    tvLogs.text = logs
                    tvDataLoaded.text =
                        "Data loaded ($current / $total) — Model: $modelName"
                }
            }

            act.runOnUiThread {
                progressBar.visibility = View.GONE
                btnPredict.isEnabled = true
                Toast.makeText(ctx, "Benchmark complete", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copyUriToInternalStorage(context: android.content.Context, uri: Uri): String {
        val name = getDisplayNameFromUri(uri)
        val outFile = File(context.filesDir, name)
        if (!outFile.exists()) {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return outFile.absolutePath
    }

    private fun getDisplayNameFromUri(uri: Uri): String {
        val cursor = requireContext().contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return it.getString(idx)
            }
        }
        return "External Model"
    }
}
