package com.ruoyi.system.voice;

import com.ruoyi.common.security.handler.GlobalExceptionHandler;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VoiceHttpMethodBoundaryTest
{
    @RestController
    static class ReferenceRoute
    {
        @GetMapping("/api/v1/admin/public-voices/references")
        Map<String, Object> read() { return Map.of("code", 200); }
        @DeleteMapping("/api/v1/admin/public-voices/{resourceId}")
        Map<String, Object> deleteResource(@PathVariable("resourceId") long resourceId) { return Map.of("code", 200); }
    }

    @Test
    void unsupportedDeleteReturns405AndPreservesAllowedRead() throws Exception
    {
        var mvc = MockMvcBuilders.standaloneSetup(new ReferenceRoute())
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        String path = "/api/v1/admin/public-voices/references";
        mvc.perform(delete(path)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        mvc.perform(post(path)).andExpect(status().isMethodNotAllowed())
            .andExpect(header().string("Allow", containsString("GET")))
            .andExpect(jsonPath("$.code").value(405));
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
    }
}
