package com.helloworld

object SampleLogicInKotlinDir {
    fun textSize(): Float = 64f

    fun scaledTextSize(scale: Float): Float = textSize() * scale

    fun wordCount(text: String): Int = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
}
