package edu.utem.ftmk.slm02

data class FoodItem(
    val id: String,
    val name: String,
    val ingredients: String,
    val allergensRaw: String,
    val allergensMapped: String

)
