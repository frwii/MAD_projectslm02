package edu.utem.ftmk.slm02

import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import java.io.FileWriter

class MetricsFragment : Fragment(R.layout.fragment_metrics) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val emptyText = view.findViewById<TextView>(R.id.tvEmptyMetrics)
        val btnExport = view.findViewById<Button>(R.id.btnExportMetrics)

        val safetyRows = view.findViewById<LinearLayout>(R.id.safetyRowsContainer)
        val qualityRows = view.findViewById<LinearLayout>(R.id.qualityRowsContainer)
        val efficiencyRows = view.findViewById<LinearLayout>(R.id.efficiencyRowsContainer)

        val csvLines = mutableListOf<String>()

        fun padRow(values: List<String>, totalCols: Int = 10): String =
            (values + List(totalCols - values.size) { "" }).joinToString(",")

        fun avg(docs: List<com.google.firebase.firestore.DocumentSnapshot>, field: String): Double =
            docs.mapNotNull { it.getLong(field) }
                .filter { it > 0 }
                .average()
                .takeIf { !it.isNaN() } ?: 0.0

        FirebaseFirestore.getInstance()
            .collection("benchmarks")
            .get()
            .addOnSuccessListener { snap ->

                if (snap.isEmpty) {
                    emptyText.visibility = View.VISIBLE
                    btnExport.isEnabled = false
                    return@addOnSuccessListener
                }

                emptyText.visibility = View.GONE

                val safetyCsv = mutableListOf<String>()
                val qualityCsv = mutableListOf<String>()
                val efficiencyCsv = mutableListOf<String>()

                val grouped = snap.documents.groupBy {
                    it.getString("modelName") ?: "Unknown"
                }

                grouped.forEach { (model, docs) ->

                    /* =========================
                       QUALITY METRICS
                    ========================= */

                    val pairs = docs.map { doc ->
                        val gt = doc.getString("mappedAllergens")
                            ?.split(",")?.map { it.trim().lowercase() }
                            ?.filter { it.isNotEmpty() } ?: emptyList()

                        val pr = doc.getString("predictedAllergens")
                            ?.split(",")?.map { it.trim().lowercase() }
                            ?.filter { it.isNotEmpty() && it != "empty" } ?: emptyList()

                        gt to pr
                    }

                    val q = MetricsCalculator.calculate(pairs)

                    val qRow = layoutInflater.inflate(
                        R.layout.item_quality_row,
                        qualityRows,
                        false
                    )

                    qRow.findViewById<TextView>(R.id.modelName).text = model
                    qRow.findViewById<TextView>(R.id.precision).text = "%.1f%%".format(q.precision * 100)
                    qRow.findViewById<TextView>(R.id.recall).text = "%.1f%%".format(q.recall * 100)
                    qRow.findViewById<TextView>(R.id.f1Micro).text = "%.3f".format(q.microF1)
                    qRow.findViewById<TextView>(R.id.f1Macro).text = "%.3f".format(q.macroF1)
                    qRow.findViewById<TextView>(R.id.emr).text = "%.1f%%".format(q.exactMatchRate * 100)
                    qRow.findViewById<TextView>(R.id.hammingLoss).text = "%.3f".format(q.hammingLoss)
                    qRow.findViewById<TextView>(R.id.fnr).text = "%.1f%%".format(q.fnr * 100)

                    qualityRows.addView(qRow)

                    qualityCsv.add(
                        padRow(
                            listOf(
                                model,
                                "%.1f".format(q.precision * 100),
                                "%.1f".format(q.recall * 100),
                                "%.3f".format(q.microF1),
                                "%.3f".format(q.macroF1),
                                "%.1f".format(q.exactMatchRate * 100),
                                "%.3f".format(q.hammingLoss),
                                "%.1f".format(q.fnr * 100)
                            )
                        )
                    )

                    /* =========================
                       SAFETY METRICS
                    ========================= */

                    val hallucinationRate =
                        docs.count {
                            val gt = it.getString("mappedAllergens") ?: ""
                            val pr = it.getString("predictedAllergens") ?: ""
                            pr != "EMPTY" && gt.isNotEmpty() && !gt.contains(pr)
                        }.toDouble() / docs.size

                    val sRow = layoutInflater.inflate(
                        R.layout.item_safety_row,
                        safetyRows,
                        false
                    )

                    sRow.findViewById<TextView>(R.id.model).text = model
                    sRow.findViewById<TextView>(R.id.hallucination).text =
                        "%.1f%%".format(hallucinationRate * 100)
                    sRow.findViewById<TextView>(R.id.overPredict).text =
                        "%.1f%%".format(q.overPredictionRate * 100)
                    sRow.findViewById<TextView>(R.id.abstention).text =
                        "%.1f%%".format(q.abstentionRate * 100)

                    safetyRows.addView(sRow)

                    safetyCsv.add(
                        padRow(
                            listOf(
                                model,
                                "%.1f".format(hallucinationRate * 100),
                                "%.1f".format(q.overPredictionRate * 100),
                                "%.1f".format(q.abstentionRate * 100)
                            )
                        )
                    )

                    /* =========================
                       EFFICIENCY METRICS
                    ========================= */

                    val latency = avg(docs, "latencyMs")
                    val ttft = avg(docs, "ttftMs")
                    val itps = avg(docs, "itps")
                    val otps = avg(docs, "otps")
                    val oet = avg(docs, "oetMs")
                    val totalTime = latency + oet

                    val eRow = layoutInflater.inflate(
                        R.layout.item_efficiency_row,
                        efficiencyRows,
                        false
                    )

                    eRow.findViewById<TextView>(R.id.model).text = model
                    eRow.findViewById<TextView>(R.id.latency).text = "${latency.toInt()} ms"
                    eRow.findViewById<TextView>(R.id.ttft).text = "${ttft.toInt()} ms"
                    eRow.findViewById<TextView>(R.id.itps).text = itps.toInt().toString()
                    eRow.findViewById<TextView>(R.id.otps).text = otps.toInt().toString()
                    eRow.findViewById<TextView>(R.id.oet).text = "${oet.toInt()} ms"
                    eRow.findViewById<TextView>(R.id.totalTime).text = "${totalTime.toInt()} ms"
                    eRow.findViewById<TextView>(R.id.javaHeap).text = avg(docs, "javaHeapKb").toInt().toString()
                    eRow.findViewById<TextView>(R.id.nativeHeap).text = avg(docs, "nativeHeapKb").toInt().toString()
                    eRow.findViewById<TextView>(R.id.pss).text = avg(docs, "pssKb").toInt().toString()

                    efficiencyRows.addView(eRow)

                    efficiencyCsv.add(
                        padRow(
                            listOf(
                                model,
                                latency.toInt().toString(),
                                ttft.toInt().toString(),
                                itps.toInt().toString(),
                                otps.toInt().toString(),
                                oet.toInt().toString(),
                                totalTime.toInt().toString(),
                                avg(docs, "javaHeapKb").toInt().toString(),
                                avg(docs, "nativeHeapKb").toInt().toString(),
                                avg(docs, "pssKb").toInt().toString()
                            )
                        )
                    )
                }

                /* =========================
                   WRITE CSV (EXCEL SAFE)
                ========================= */

                csvLines.add(padRow(listOf("SAFETY METRICS")))
                csvLines.add(
                    padRow(
                        listOf(
                            "Model",
                            "HallucinationRate(%)",
                            "OverPredictionRate(%)",
                            "AbstentionRate(%)"
                        )
                    )
                )
                csvLines.addAll(safetyCsv)
                csvLines.add("")

                csvLines.add(padRow(listOf("QUALITY METRICS")))
                csvLines.add(
                    padRow(
                        listOf(
                            "Model",
                            "Precision(%)",
                            "Recall(%)",
                            "MicroF1",
                            "MacroF1",
                            "ExactMatch(%)",
                            "HammingLoss",
                            "FNR(%)"
                        )
                    )
                )
                csvLines.addAll(qualityCsv)
                csvLines.add("")

                csvLines.add(padRow(listOf("EFFICIENCY METRICS")))
                csvLines.add(
                    padRow(
                        listOf(
                            "Model",
                            "LatencyMs",
                            "TTFTms",
                            "ITPS",
                            "OTPS",
                            "OETms",
                            "TotalTimeMs",
                            "JavaHeapKB",
                            "NativeHeapKB",
                            "PSSKB"
                        )
                    )
                )
                csvLines.addAll(efficiencyCsv)

                btnExport.setOnClickListener {
                    exportCsv(csvLines)
                }
            }
    }

    private fun exportCsv(lines: List<String>) {
        try {
            val dir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(dir, "metrics_export_${System.currentTimeMillis()}.csv")

            FileWriter(file).use { writer ->
                lines.forEach { writer.appendLine(it) }
            }

            Toast.makeText(
                requireContext(),
                "Metrics exported to Downloads",
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
