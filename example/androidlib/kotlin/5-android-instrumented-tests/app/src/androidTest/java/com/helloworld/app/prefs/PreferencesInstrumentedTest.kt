package com.helloworld.app.prefs

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helloworld.app.PreferencesHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreferencesInstrumentedTest {
    @Test
    fun writeAndReadBooleanFlag() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "feature_enabled"
        PreferencesHelper.writeFlag(context, key, true)
        assertTrue(PreferencesHelper.readFlag(context, key))
        assertFalse(PreferencesHelper.readFlag(context, "missing_key", default = false))
    }
}
