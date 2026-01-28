package edu.utem.ftmk.slm02

object MetricsCalculator {

    data class QualityResult(
        val precision: Double,
        val recall: Double,

        // ✅ Split F1
        val microF1: Double,
        val macroF1: Double,

        // ✅ kept for backward compatibility (equals microF1)
        val f1Score: Double,

        val fnr: Double,
        val hallucinationRate: Double,
        val overPredictionRate: Double,
        val abstentionRate: Double,
        val exactMatchRate: Double,
        val hammingLoss: Double
    )

    // ✅ Official allergen label set (Table 2)
    private val ALL_LABELS = setOf(
        "milk", "egg", "peanut", "tree nut",
        "wheat", "soy", "fish", "shellfish", "sesame"
    )

    fun calculate(results: List<Pair<List<String>, List<String>>>): QualityResult {

        var tp = 0
        var fp = 0
        var fn = 0
        var exactMatch = 0
        var hammingSum = 0
        var abstain = 0
        val total = results.size

        // ✅ Per-label counters for Macro F1
        val labelTP = mutableMapOf<String, Int>()
        val labelFP = mutableMapOf<String, Int>()
        val labelFN = mutableMapOf<String, Int>()

        ALL_LABELS.forEach {
            labelTP[it] = 0
            labelFP[it] = 0
            labelFN[it] = 0
        }

        results.forEach { (gt, pred) ->

            val gtSet = gt.toSet()
            val prSet = pred.toSet()

            val correct = gtSet.intersect(prSet).size
            tp += correct
            fp += (prSet.size - correct)
            fn += (gtSet.size - correct)

            if (pred.isEmpty()) abstain++
            if (gtSet == prSet) exactMatch++

            val union = (gtSet + prSet).size
            hammingSum += (union - correct)

            // ===== Per-label accounting =====
            ALL_LABELS.forEach { label ->
                val inGT = label in gtSet
                val inPR = label in prSet

                when {
                    inGT && inPR -> labelTP[label] = labelTP[label]!! + 1
                    !inGT && inPR -> labelFP[label] = labelFP[label]!! + 1
                    inGT && !inPR -> labelFN[label] = labelFN[label]!! + 1
                }
            }
        }

        // ===== Precision & Recall =====
        val precision =
            if (tp + fp > 0) tp.toDouble() / (tp + fp) else 0.0

        val recall =
            if (tp + fn > 0) tp.toDouble() / (tp + fn) else 0.0

        // ===== Micro F1 (Table 2) =====
        val microF1 =
            if (2 * tp + fp + fn > 0)
                (2.0 * tp) / (2.0 * tp + fp + fn)
            else 0.0

        // ===== Macro F1 (average per-label F1) =====
        val macroF1 = ALL_LABELS.map { label ->
            val tpL = labelTP[label]!!
            val fpL = labelFP[label]!!
            val fnL = labelFN[label]!!

            if (2 * tpL + fpL + fnL > 0)
                (2.0 * tpL) / (2.0 * tpL + fpL + fnL)
            else 0.0
        }.average()

        return QualityResult(
            precision = precision,
            recall = recall,

            microF1 = microF1,
            macroF1 = macroF1,

            // ✅ keep legacy field aligned with Micro F1
            f1Score = microF1,

            fnr = if (tp + fn > 0) fn.toDouble() / (tp + fn) else 0.0,
            hallucinationRate = if (fp + tp > 0) fp.toDouble() / (fp + tp) else 0.0,
            overPredictionRate = fp.toDouble() / total,
            abstentionRate = abstain.toDouble() / total,
            exactMatchRate = exactMatch.toDouble() / total,
            hammingLoss = hammingSum.toDouble() / total
        )
    }
}
