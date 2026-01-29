package edu.utem.ftmk.slm02

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Environment
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.*
import androidx.fragment.app.Fragment
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import java.io.FileWriter

class FoodListFragment : Fragment(R.layout.fragment_food_list) {

    private lateinit var tvLogs: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnExport: Button
    private lateinit var spinner: Spinner

    // 🔹 Firestore cache
    private val allDocs = mutableListOf<DocumentSnapshot>()

    // 🔹 Filtered export rows
    private val exportRows = mutableListOf<List<String>>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvLogs = view.findViewById(R.id.tvFoodLogs)
        progressBar = view.findViewById(R.id.progressBarFood)
        btnExport = view.findViewById(R.id.button)
        spinner = view.findViewById(R.id.spinner)

        tvLogs.movementMethod = LinkMovementMethod.getInstance()

        btnExport.setOnClickListener { exportPredictions() }

        loadAllFoods()
    }

    // ================= LOAD FROM FIRESTORE =================
    private fun loadAllFoods() {
        progressBar.visibility = View.VISIBLE
        tvLogs.text = "Loading food records...\n"
        btnExport.isEnabled = false

        FirebaseFirestore.getInstance()
            .collection("benchmarks")
            .get()
            .addOnSuccessListener { snap ->

                if (snap.isEmpty) {
                    tvLogs.text = "No food records found."
                    progressBar.visibility = View.GONE
                    return@addOnSuccessListener
                }

                allDocs.clear()
                allDocs.addAll(snap.documents)

                setupSpinner(allDocs)
                renderList("All Models")

                progressBar.visibility = View.GONE
                btnExport.isEnabled = true
            }
            .addOnFailureListener {
                progressBar.visibility = View.GONE
                Toast.makeText(
                    requireContext(),
                    "Failed to load food records",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    // ================= SPINNER SETUP =================
    private fun setupSpinner(docs: List<DocumentSnapshot>) {

        val models = docs
            .map { it.getString("modelName") ?: "Unknown" }
            .distinct()
            .sorted()

        val spinnerItems = mutableListOf("All Models")
        spinnerItems.addAll(models)

        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            spinnerItems
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                position: Int,
                id: Long
            ) {
                renderList(spinnerItems[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    // ================= RENDER LIST =================
    private fun renderList(selectedModel: String) {

        exportRows.clear()
        val logs = SpannableStringBuilder()

        val filteredDocs = if (selectedModel == "All Models") {
            allDocs
        } else {
            allDocs.filter {
                (it.getString("modelName") ?: "Unknown") == selectedModel
            }
        }

        // ===== SUMMARY =====
        val modelCounts = filteredDocs
            .groupBy { it.getString("modelName") ?: "Unknown" }
            .mapValues { it.value.size }

        val summaryStart = logs.length
        logs.append("Data Loaded Per Model\n")
        logs.setSpan(
            StyleSpan(Typeface.BOLD),
            summaryStart,
            logs.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        modelCounts.forEach { (model, count) ->
            logs.append("• $model : $count items\n")
        }

        logs.append("\n")

        // ===== LIST ITEMS =====
        var index = 1

        filteredDocs.forEach { doc ->

            val id = doc.getString("dataId") ?: "UNKNOWN"
            val modelName = doc.getString("modelName") ?: "Unknown"
            val foodName = doc.getString("foodName") ?: "Unknown"
            val ingredients = doc.getString("ingredients") ?: ""
            val raw = doc.getString("rawAllergens") ?: ""
            val mapped = doc.getString("mappedAllergens") ?: ""
            val predicted = doc.getString("predictedAllergens") ?: "EMPTY"

            val latencyMs = doc.getLong("latencyMs") ?: 0L
            val ttft = doc.getLong("ttftMs") ?: -1L
            val itps = doc.getLong("itps") ?: -1L
            val otps = doc.getLong("otps") ?: -1L
            val oet = doc.getLong("oetMs") ?: -1L

            val javaHeapKb = doc.getLong("javaHeapKb") ?: -1L
            val nativeHeapKb = doc.getLong("nativeHeapKb") ?: -1L
            val pssKb = doc.getLong("pssKb") ?: -1L

            val outcome =
                mapped.split(",").map { it.trim().lowercase() }.sorted() ==
                        predicted.split(",").map { it.trim().lowercase() }.sorted()

            // ===== EXPORT =====
            exportRows.add(
                listOf(
                    id,
                    modelName,
                    foodName,
                    ingredients,
                    raw,
                    mapped,
                    predicted,
                    if (outcome) "MATCH" else "MISMATCH"
                )
            )

            val start = logs.length
            logs.append("$index. $foodName ($modelName)\n")

            logs.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                logs.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            logs.setSpan(object : ClickableSpan() {

                override fun onClick(widget: View) {
                    val intent = Intent(requireContext(), FoodDetailActivity::class.java)
                    intent.putExtra("name", foodName)
                    intent.putExtra("ingredients", ingredients)
                    intent.putExtra("raw", raw)
                    intent.putExtra("mapped", mapped)
                    intent.putExtra("predicted", predicted)
                    intent.putExtra("latencyMs", latencyMs)
                    intent.putExtra("ttft", ttft)
                    intent.putExtra("itps", itps)
                    intent.putExtra("otps", otps)
                    intent.putExtra("oet", oet)
                    intent.putExtra("javaHeapKb", javaHeapKb)
                    intent.putExtra("nativeHeapKb", nativeHeapKb)
                    intent.putExtra("pssKb", pssKb)

                    startActivity(intent)
                }

                override fun updateDrawState(ds: TextPaint) {
                    ds.isUnderlineText = false
                    ds.color = 0xFF512DA8.toInt()
                }

            }, start, logs.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            logs.append("Predicted Allergens : $predicted\n")
            logs.append("Outcome : ${if (outcome) "✅ MATCH" else "❌ MISMATCH"}\n\n")

            index++
        }

        tvLogs.text = logs
    }

    // ================= EXPORT CSV =================
    private fun exportPredictions() {
        try {
            val dir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

            val file = File(dir, "prediction_export_${System.currentTimeMillis()}.csv")

            FileWriter(file).use { writer ->
                writer.appendLine(
                    "ID,Model Name,Food Name,Ingredients,Raw Allergens,Mapped Allergens,Predicted Allergens,Outcome"
                )

                exportRows.forEach { row ->
                    writer.appendLine(row.joinToString(",") { "\"$it\"" })
                }
            }

            Toast.makeText(
                requireContext(),
                "Exported to Downloads:\n${file.name}",
                Toast.LENGTH_LONG
            ).show()

        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "Export failed: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
