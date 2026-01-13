package com.helloworld.app.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helloworld.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResourceInstrumentedTest {
    @Test
    fun verifyAppContextAndResources() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.helloworld.app", context.packageName)
        val hello = context.getString(R.string.hello_world)
        assertTrue(hello.contains("Hello"))
        val white = context.getColor(R.color.white)
        assertNotEquals(0, white)
    }
}
