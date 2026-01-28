package edu.utem.ftmk.slm02

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.math.max

class DetailsFragment : Fragment(R.layout.fragment_details) {

    data class ModelSummary(
        val model: String,
        val total: Int,
        val correct: Int,
        val accuracy: Double,
        val avgLatency: Double,
        val avgTTFT: Double,
        val hallucinationRate: Double,
        val overPredictionRate: Double,
        var score: Double = 0.0
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val container = view.findViewById<LinearLayout>(R.id.detailsContainer)
        val emptyText = view.findViewById<TextView>(R.id.tvEmptyDetails)

        container.removeAllViews()

        FirebaseFirestore.getInstance()
            .collection("benchmarks")
            .get()
            .addOnSuccessListener { snap ->

                if (snap.isEmpty) {
                    emptyText.visibility = View.VISIBLE
                    return@addOnSuccessListener
                }

                emptyText.visibility = View.GONE

                val grouped = snap.documents.groupBy {
                    it.getString("modelName") ?: "Unknown"
                }

                val summaries = mutableListOf<ModelSummary>()

                grouped.forEach { (model, docs) ->
                    val total = docs.size
                    if (total == 0) return@forEach

                    val correct = docs.count { doc ->
                        val gt = doc.getString("mappedAllergens") ?: ""
                        val pr = doc.getString("predictedAllergens") ?: ""
                        gt.isNotBlank() && gt == pr
                    }

                    val avgLatency =
                        docs.map { it.getLong("latencyMs") ?: 0L }.average()

                    val avgTTFT =
                        docs.map { it.getLong("ttftMs") ?: 0L }.average()

                    val hallucinationRate =
                        docs.count { doc ->
                            val gt = doc.getString("mappedAllergens")
                                ?.split(",")?.map { it.trim() } ?: emptyList()
                            val pr = doc.getString("predictedAllergens")
                                ?.split(",")?.map { it.trim() } ?: emptyList()
                            pr.isNotEmpty() && pr.any { it !in gt }
                        }.toDouble() / total

                    val overPredictionRate =
                        docs.count { doc ->
                            val gtSize =
                                doc.getString("mappedAllergens")
                                    ?.split(",")?.filter { it.isNotBlank() }?.size ?: 0
                            val prSize =
                                doc.getString("predictedAllergens")
                                    ?.split(",")
                                    ?.filter { it.isNotBlank() && it != "EMPTY" }
                                    ?.size ?: 0
                            prSize > gtSize
                        }.toDouble() / total

                    summaries.add(
                        ModelSummary(
                            model,
                            total,
                            correct,
                            correct.toDouble() / total,
                            avgLatency,
                            avgTTFT,
                            hallucinationRate,
                            overPredictionRate
                        )
                    )
                }

                // ================= OVERALL SCORE =================
                val maxLatency = summaries.maxOf { it.avgLatency }

                summaries.forEach { s ->
                    val latencyNorm = s.avgLatency / maxLatency
                    s.score =
                        (s.accuracy * 0.5) -
                                (latencyNorm * 0.2) -
                                (s.hallucinationRate * 0.2) -
                                (s.overPredictionRate * 0.1)
                }

                val rankedOverall = summaries.sortedByDescending { it.score }

                // ================= RANKING CARD (FIRST) =================
                val rankingCard = layoutInflater.inflate(
                    R.layout.item_model_ranking,
                    container,
                    false
                )

                val rankingContainer =
                    rankingCard.findViewById<LinearLayout>(R.id.rankingContainer)

                rankedOverall.forEachIndexed { index, s ->
                    val tv = TextView(requireContext())
                    tv.text =
                        "${index + 1}. ${s.model} — ${(s.accuracy * 100).toInt()}% accuracy"
                    tv.textSize = 14f
                    tv.setPadding(8, 6, 8, 6)
                    rankingContainer.addView(tv)
                }

                container.addView(rankingCard)

                // ================= EXISTING DETAIL CARDS =================
                val latencyRank = summaries.sortedBy { it.avgLatency }
                val ttftRank = summaries.sortedBy { it.avgTTFT }
                val accuracyRank = summaries.sortedByDescending { it.accuracy }
                val hallucinationRank = summaries.sortedBy { it.hallucinationRate }
                val overPredictRank = summaries.sortedBy { it.overPredictionRate }

                summaries.forEach { s ->

                    val card = layoutInflater.inflate(
                        R.layout.item_detail,
                        container,
                        false
                    )

                    card.findViewById<TextView>(R.id.modelName).text = s.model
                    card.findViewById<TextView>(R.id.totalAccuracy).text =
                        "%.1f%%".format(s.accuracy * 100)
                    card.findViewById<TextView>(R.id.totalCorrect).text =
                        "Correct: ${s.correct} / ${s.total}"

                    val badgeContainer =
                        card.findViewById<LinearLayout>(R.id.badgeContainer)
                    badgeContainer.removeAllViews()

                    fun addBadge(text: String) {
                        val tv = TextView(requireContext())
                        tv.text = "🏅 $text"
                        tv.textSize = 12f
                        badgeContainer.addView(tv)
                    }

                    val accPos = accuracyRank.indexOf(s) + 1
                    val latPos = latencyRank.indexOf(s) + 1

                    if (accPos <= 3) addBadge("$accPos Best Accuracy")
                    if (latPos <= 3) addBadge("$latPos Fastest Latency")

                    card.findViewById<TextView>(R.id.tvStrengths).text =
                        "#$accPos Accuracy\n#$latPos Latency"

                    card.findViewById<TextView>(R.id.tvWeaknesses).text =
                        "#${hallucinationRank.indexOf(s) + 1} Hallucination\n" +
                                "#${overPredictRank.indexOf(s) + 1} Over-Prediction"

                    container.addView(card)
                }
            }
    }
}
