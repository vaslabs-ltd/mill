package com.helloworld

object SampleLogic {
    fun textSize(): Float = 32f

    fun add(a: Int, b: Int): Int = a + b

    fun isPalindrome(input: String): Boolean {
        val cleaned = input.lowercase().filter { it.isLetterOrDigit() }
        return cleaned == cleaned.reversed()
    }

    fun safeDivide(a: Int, b: Int): Double? = if (b == 0) null else a.toDouble() / b

    fun average(nums: List<Int>): Double {
        require(nums.isNotEmpty()) { "List must not be empty" }
        return nums.sum().toDouble() / nums.size
    }
}
