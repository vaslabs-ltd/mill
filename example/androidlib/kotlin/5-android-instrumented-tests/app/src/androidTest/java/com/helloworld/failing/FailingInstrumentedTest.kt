package com.helloworld.failing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helloworld.SampleLogic
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FailingInstrumentedTest {
    @Test
    fun wrongPackageAssertion_shouldFail() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.helloworld.WRONG", context.packageName)
    }

    @Test
    fun incorrectMath_shouldFail() {
        assertEquals(10, SampleLogic.add(3, 4))
    }
}
