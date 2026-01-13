package com.helloworld.logic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helloworld.SampleLogic
import com.helloworld.SampleLogicInKotlinDir
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LogicInstrumentedTest {
    @Test
    fun testAddAndAverage() {
        assertEquals(7, SampleLogic.add(3, 4))
        assertEquals(3.0, SampleLogic.average(listOf(1, 2, 3, 6)), 0.0001)
    }

    @Test
    fun testPalindromeAndDivide() {
        assertTrue(SampleLogic.isPalindrome("A man, a plan, a canal: Panama"))
        assertNull(SampleLogic.safeDivide(10, 0))
        assertEquals(2.5, SampleLogic.safeDivide(5, 2)!!, 0.0001)
    }

    @Test
    fun testKotlinDirHelpers() {
        assertEquals(128f, SampleLogicInKotlinDir.scaledTextSize(2f))
        assertEquals(4, SampleLogicInKotlinDir.wordCount("One  two\nthree    four"))
    }
}
