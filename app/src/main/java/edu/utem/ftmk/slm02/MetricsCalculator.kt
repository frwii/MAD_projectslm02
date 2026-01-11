package edu.utem.ftmk.slm02

object MetricsCalculator {

    data class QualityResult(
        val precision: Double,
        val recall: Double,
        val f1Score: Double,
        val fnr: Double, // False Negative Rate (Safety)
        val overPredictionRate: Double // Safety
    )

    fun calculate(results: List<Pair<List<String>, List<String>>>): QualityResult {
        var tp = 0
        var fp = 0
        var fn = 0
        var totalItems = results.size

        results.forEach { (groundTruth, predicted) ->
            val correct = predicted.intersect(groundTruth).size
            tp += correct
            fp += (predicted.size - correct)
            fn += (groundTruth.size - correct)
        }

        val precision = if (tp + fp > 0) tp.toDouble() / (tp + fp) else 0.0
        val recall = if (tp + fn > 0) tp.toDouble() / (tp + fn) else 0.0
        val f1 = if (precision + recall > 0) 2 * (precision * recall) / (precision + recall) else 0.0
        val fnr = if (tp + fn > 0) fn.toDouble() / (tp + fn) else 0.0

        return QualityResult(precision, recall, f1, fnr, (fp.toDouble() / totalItems))
    }
}