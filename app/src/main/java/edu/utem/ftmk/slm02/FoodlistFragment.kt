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
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import java.io.FileWriter

class FoodListFragment : Fragment(R.layout.fragment_food_list) {

    private lateinit var tvLogs: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnExport: Button

    // 🔹 Stored for export
    private val exportRows = mutableListOf<List<String>>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvLogs = view.findViewById(R.id.tvFoodLogs)
        progressBar = view.findViewById(R.id.progressBarFood)
        btnExport = view.findViewById(R.id.button)

        tvLogs.movementMethod = LinkMovementMethod.getInstance()

        btnExport.setOnClickListener {
            exportPredictions()
        }

        loadAllFoods()
    }

    // ================= LOAD ALL FOODS =================
    private fun loadAllFoods() {
        progressBar.visibility = View.VISIBLE
        tvLogs.text = "Loading food records...\n"
        exportRows.clear()

        FirebaseFirestore.getInstance()
            .collection("benchmarks")
            .get()
            .addOnSuccessListener { snap ->

                if (snap.isEmpty) {
                    tvLogs.text = "No food records found."
                    progressBar.visibility = View.GONE
                    btnExport.isEnabled = false
                    return@addOnSuccessListener
                }

                val logs = SpannableStringBuilder()

                // ================= MODEL COUNTER =================
                val modelCounts = snap.documents
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
                // =================================================

                var index = 1

                snap.documents.forEach { doc ->

                    val id = doc.getString("dataId") ?: "UNKNOWN"
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

                    // ===== OUTCOME CHECK =====
                    val outcome =
                        mapped.split(",").map { it.trim().lowercase() }.sorted() ==
                                predicted.split(",").map { it.trim().lowercase() }.sorted()

                    // ===== STORE FOR EXPORT =====
                    exportRows.add(
                        listOf(
                            id,
                            foodName,
                            ingredients,
                            raw,
                            mapped,
                            predicted,
                            if (outcome) "CORRECT" else "WRONG"
                        )
                    )

                    val start = logs.length
                    logs.append("$index. $foodName\n")

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
                            startActivity(intent)
                        }

                        override fun updateDrawState(ds: TextPaint) {
                            super.updateDrawState(ds)
                            ds.isUnderlineText = false
                            ds.color = 0xFF512DA8.toInt()
                        }

                    }, start, logs.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                    logs.append("Predicted Allergens : $predicted\n")
                    logs.append("Outcome : ${if (outcome) "✅ CORRECT" else "❌ WRONG"}\n\n")

                    index++
                }

                tvLogs.text = logs
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

    // ================= EXPORT CSV =================
    private fun exportPredictions() {
        try {
            val dir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

            val file = File(dir, "prediction_export_${System.currentTimeMillis()}.csv")

            FileWriter(file).use { writer ->
                writer.appendLine(
                    "ID,Food Name,Ingredients,Raw Allergens,Mapped Allergens,Predicted Allergens,Outcome"
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
