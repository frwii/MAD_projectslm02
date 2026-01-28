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

        fun fmtMs(v: Double) = if (v == 0.0) "N/A" else "${v.toInt()} ms"
        fun fmtKb(v: Double) = if (v == 0.0) "N/A" else "${v.toInt()} KB"
        fun fmtNum(v: Double) = if (v == 0.0) "N/A" else v.toInt().toString()

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

                // ================= CSV HEADER =================
                csvLines.add(
                    "Model,Precision(%),Recall(%),MicroF1,MacroF1,ExactMatch(%),HammingLoss,FNR(%)," +
                            "HallucinationRate(%),OverPredictionRate(%),AbstentionRate(%)," +
                            "LatencyMs,TTFTms,ITPS,OTPS,OETms,TotalTimeMs,JavaHeapKB,NativeHeapKB,PSSKB"
                )

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
                    qRow.findViewById<TextView>(R.id.precision).text =
                        "%.1f%%".format(q.precision * 100)
                    qRow.findViewById<TextView>(R.id.recall).text =
                        "%.1f%%".format(q.recall * 100)
                    qRow.findViewById<TextView>(R.id.f1Micro).text =
                        "%.3f".format(q.microF1)
                    qRow.findViewById<TextView>(R.id.f1Macro).text =
                        "%.3f".format(q.macroF1)
                    qRow.findViewById<TextView>(R.id.emr).text =
                        "%.1f%%".format(q.exactMatchRate * 100)
                    qRow.findViewById<TextView>(R.id.hammingLoss).text =
                        "%.3f".format(q.hammingLoss)
                    qRow.findViewById<TextView>(R.id.fnr).text =
                        "%.1f%%".format(q.fnr * 100)

                    qualityRows.addView(qRow)

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

                    /* =========================
                       EFFICIENCY METRICS (CORRECT)
                    ========================= */

                    fun avg(field: String): Double =
                        docs.mapNotNull { it.getLong(field) }
                            .filter { it > 0 }
                            .average()
                            .takeIf { !it.isNaN() } ?: 0.0

                    val avgLatency = avg("latencyMs")
                    val avgOet = avg("oetMs")
                    val totalTime =
                        if (avgLatency == 0.0 && avgOet == 0.0) 0.0 else avgLatency + avgOet

                    val eRow = layoutInflater.inflate(
                        R.layout.item_efficiency_row,
                        efficiencyRows,
                        false
                    )

                    eRow.findViewById<TextView>(R.id.model).text = model
                    eRow.findViewById<TextView>(R.id.latency).text = fmtMs(avgLatency)
                    eRow.findViewById<TextView>(R.id.ttft).text = fmtMs(avg("ttftMs"))
                    eRow.findViewById<TextView>(R.id.itps).text = fmtNum(avg("itps"))
                    eRow.findViewById<TextView>(R.id.otps).text = fmtNum(avg("otps"))
                    eRow.findViewById<TextView>(R.id.oet).text = fmtMs(avgOet)
                    eRow.findViewById<TextView>(R.id.totalTime).text = fmtMs(totalTime)
                    eRow.findViewById<TextView>(R.id.javaHeap).text = fmtKb(avg("javaHeapKb"))
                    eRow.findViewById<TextView>(R.id.nativeHeap).text = fmtKb(avg("nativeHeapKb"))
                    eRow.findViewById<TextView>(R.id.pss).text = fmtKb(avg("pssKb"))

                    efficiencyRows.addView(eRow)

                    /* =========================
                       CSV EXPORT ROW
                    ========================= */

                    csvLines.add(
                        listOf(
                            model,
                            q.precision * 100,
                            q.recall * 100,
                            q.microF1,
                            q.macroF1,
                            q.exactMatchRate * 100,
                            q.hammingLoss,
                            q.fnr * 100,
                            hallucinationRate * 100,
                            q.overPredictionRate * 100,
                            q.abstentionRate * 100,
                            avgLatency,
                            avg("ttftMs"),
                            avg("itps"),
                            avg("otps"),
                            avgOet,
                            totalTime,
                            avg("javaHeapKb"),
                            avg("nativeHeapKb"),
                            avg("pssKb")
                        ).joinToString(",")
                    )
                }

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
