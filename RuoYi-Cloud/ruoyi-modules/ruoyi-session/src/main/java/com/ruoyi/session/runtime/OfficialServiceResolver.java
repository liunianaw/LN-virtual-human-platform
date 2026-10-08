package com.ruoyi.session.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Resolves a fixed Voice service at send time so a later disable wins over frozen configuration. */
@Component
public class OfficialServiceResolver
{
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public OfficialServiceResolver(ObjectMapper json) { this.json = json; }
    public Resolved resolve(VoiceRuntimeBinding voice)
    {
        if (voice.officialServiceId() == null || voice.officialServiceRevision() == null) throw unavailable("OFFICIAL_SERVICE_INVALID");
        String base = System.getenv("LN_SESSION_TO_SYSTEM_URL"), bearer = System.getenv("LN_SESSION_TO_SYSTEM_INTERNAL_BEARER");
        if (blank(base) || blank(bearer)) throw unavailable("OFFICIAL_SERVICE_NOT_CONFIGURED");
        try
        {
            String body = json.writeValueAsString(new ResolveRequest(voice.officialServiceRevision(), "TTS", null, voice.voiceVersionId()));
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/internal/v1/official-services/" + voice.officialServiceId() + "/resolve"))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + bearer).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 409) throw unavailable("OFFICIAL_SERVICE_DISABLED");
            if (response.statusCode() != 200) throw unavailable("OFFICIAL_SERVICE_UNAVAILABLE");
            JsonNode value = json.readTree(response.body());
            String endpoint = value.path("endpoint").asText(), model = value.path("modelId").asText(), credential = value.path("credential").asText();
            if (!"DASHSCOPE_QWEN_TTS".equals(com.ruoyi.common.voice.VoiceCatalog.canonical(value.path("providerCode").asText())) || blank(endpoint) || blank(model) || blank(credential)) throw unavailable("OFFICIAL_SERVICE_INVALID");
            return new Resolved(endpoint, model, credential);
        }
        catch (RuntimeProblem e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw unavailable("OFFICIAL_SERVICE_UNAVAILABLE"); }
        catch (IOException | IllegalArgumentException e) { throw unavailable("OFFICIAL_SERVICE_UNAVAILABLE"); }
    }
    public DefaultResolved resolveDefaultAsr()
    {
        String base = System.getenv("LN_SESSION_TO_SYSTEM_URL"), bearer = System.getenv("LN_SESSION_TO_SYSTEM_INTERNAL_BEARER");
        if (blank(base) || blank(bearer)) throw unavailable("OFFICIAL_ASR_NOT_CONFIGURED");
        try
        {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/internal/v1/official-services/default/resolve"))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"purpose\":\"ASR\"}")).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw unavailable("OFFICIAL_ASR_UNAVAILABLE");
            JsonNode value = json.readTree(response.body());
            long serviceId = Long.parseLong(value.path("serviceId").asText());
            String endpoint = value.path("endpoint").asText(), model = value.path("modelId").asText();
            String credential = value.path("credential").asText();
            if (serviceId <= 0 || !"DASHSCOPE_BEIJING".equals(value.path("providerCode").asText())
                || blank(endpoint) || blank(model) || blank(credential)) throw unavailable("OFFICIAL_ASR_INVALID");
            return new DefaultResolved(serviceId, Long.parseLong(value.path("revision").asText()),
                endpoint, model, credential);
        }
        catch (RuntimeProblem error) { throw error; }
        catch (InterruptedException error)
        { Thread.currentThread().interrupt(); throw unavailable("OFFICIAL_ASR_UNAVAILABLE"); }
        catch (IOException | IllegalArgumentException error) { throw unavailable("OFFICIAL_ASR_UNAVAILABLE"); }
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static RuntimeProblem unavailable(String code) { return new RuntimeProblem(HttpStatus.SERVICE_UNAVAILABLE, code, "The configured official TTS service is unavailable."); }
    private record ResolveRequest(long expectedServiceRevision, String purpose, Long taskId, Long voiceVersionId) { }
    public record Resolved(String endpoint, String model, String credential) { }
    public record DefaultResolved(long serviceId, long revision, String endpoint, String model, String credential) { }
}
