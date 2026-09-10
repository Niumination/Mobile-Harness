package com.jarves.mh.network

import com.jarves.mh.model.ProviderProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderApiClientTest {
    @Test
    fun openAiResponsesProbeUsesProviderCompatibleOutputMinimum() {
        val body = JSONObject(
            ProviderApiClient().validationBody(
                model = "muse-spark-1.3-contributor-free",
                protocol = ProviderProtocol.OPENAI_RESPONSES,
            ),
        )

        assertEquals("muse-spark-1.3-contributor-free", body.getString("model"))
        assertEquals(16, body.getInt("max_output_tokens"))
        assertEquals("Reply OK", body.getString("input"))
    }
}
