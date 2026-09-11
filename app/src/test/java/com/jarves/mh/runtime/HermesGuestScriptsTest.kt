package com.jarves.mh.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesGuestScriptsTest {
    @Test
    fun pickPrefersNewestPythonAndRequires311() {
        val s = RuntimeInstaller.HermesGuestScripts.hermesPythonPickSnippet()
        assertTrue(s.contains("python3.13 python3.12 python3.11 python3"))
        assertTrue(s.contains("-ge 311"))
    }

    @Test
    fun bootstrapUsesUvNotDeadsnakes() {
        val s = RuntimeInstaller.HermesGuestScripts.hermesPythonBootstrapSnippet()
        assertTrue(s.contains("uv python install 3.11"))
        assertTrue(s.contains("ensurepip"))
        assertTrue(s.contains("python3-pip"))
        assertFalse(s.contains("deadsnakes"))
        assertFalse(s.contains("add-apt-repository"))
    }
}
