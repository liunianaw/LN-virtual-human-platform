package com.ruoyi.session.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ContextRuntimeServiceTest
{
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void onlyCurrentTurnPageAndHybridScopesCanCapture() throws Exception
    {
        JsonNode fixed = json.readTree("""
            {"enabled":true,"sources":["PAGE","HYBRID"],"modes":["EXPLICIT","AI_ON_DEMAND"],
             "captureScope":{"allowViewport":true},"fullPageEnabled":true,"resultMode":"PARTIAL"}
            """);
        JsonNode current = json.readTree("""
            {"enabled":true,"sources":["HYBRID"],"modes":["EXPLICIT","AI_ON_DEMAND"],
             "captureScope":{"allowViewport":true},"fullPageEnabled":false,"resultMode":"STRICT"}
            """);
        assertDoesNotThrow(() -> ContextRuntimeService.checkPolicy(fixed, current,
            "HYBRID", "VIEWPORT", "STRICT", false));
        assertThrows(RuntimeProblem.class, () -> ContextRuntimeService.checkPolicy(fixed, current,
            "ELEMENT", "VIEWPORT", "STRICT", false));
        assertThrows(RuntimeProblem.class, () -> ContextRuntimeService.checkPolicy(fixed, current,
            "PAGE", "VIEWPORT", "STRICT", false));
        assertThrows(RuntimeProblem.class, () -> ContextRuntimeService.checkPolicy(fixed, current,
            "HYBRID", "FULL_PAGE", "STRICT", true));
        assertThrows(RuntimeProblem.class, () -> ContextRuntimeService.checkPolicy(fixed, current,
            "HYBRID", "VIEWPORT", "PARTIAL", false));
    }
}
