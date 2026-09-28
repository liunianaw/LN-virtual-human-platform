package com.ruoyi.session.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.session.business.BusinessSystemClient;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Temporary page data and element references belong to one active CHAT turn only. */
@Service
public class ContextRuntimeService
{
    private final ChatTurnStore store;
    private final RuntimeAuthorization access;
    private final RuntimeConnectionEpochs epochs;
    private final RuntimeEventPublisher events;
    private final BusinessSystemClient system;
    private final ObjectMapper json;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<Long, References> references = new ConcurrentHashMap<>();
    private final Map<String, Guidance> guidance = new ConcurrentHashMap<>();

    public ContextRuntimeService(ChatTurnStore store, RuntimeAuthorization access, RuntimeConnectionEpochs epochs,
        RuntimeEventPublisher events, BusinessSystemClient system, ObjectMapper json)
    {
        this.store = store; this.access = access; this.epochs = epochs; this.events = events;
        this.system = system; this.json = json;
    }

    public Capture request(RuntimeAuthorization.Grant grant, long epoch, long turnId, JsonNode fixedPolicy,
        String source, String coverage, String resultMode, boolean ai, int ordinal)
    {
        RuntimePrincipal principal = grant.principal();
        JsonNode current = currentPolicy(principal);
        requireActive(grant.id(), principal, epoch, turnId);
        checkPolicy(fixedPolicy, current, source, coverage, resultMode, ai);
        if (ordinal < 0 || ordinal >= Math.min(fixedPolicy.path("maxCapturesPerTurn").asInt(0),
            current.path("maxCapturesPerTurn").asInt(0)))
            throw problem(HttpStatus.TOO_MANY_REQUESTS, "CONTEXT_LIMIT");
        String id = UUID.randomUUID().toString();
        Instant deadline = Instant.now().plusSeconds(12);
        long operationId = store.beginOperation(principal, turnId, "CONTEXT", ordinal,
            principal.configVersionId(), source + ":" + coverage);
        Pending request = new Pending(id, grant.id(), grant.source(), principal, epoch, turnId, source, coverage, resultMode, ai,
            fixedPolicy, current, deadline, operationId);
        pending.put(id, request);
        try
        {
            events.chat(principal, epoch, turnId, "runtime", "context.request",
                Map.of("captureRequestId", id, "turnId", Long.toString(turnId), "source", source,
                    "coverage", coverage, "resultMode", resultMode, "deadlineAt", deadline.toString(),
                    "fixedPolicy", fixedPolicy, "currentPolicy", current));
            Capture captured = request.result.get(12, TimeUnit.SECONDS);
            requireActive(grant.id(), principal, epoch, turnId);
            store.endOperation(principal, turnId, operationId, "CONTEXT", "SUCCEEDED", null, null, null, null);
            if ("HYBRID".equals(source)) references.put(turnId,
                new References(id, epoch, current, Set.copyOf(captured.elementRefs())));
            events.chat(principal, epoch, turnId, "runtime", "context.received",
                Map.of("captureRequestId", id, "screenshotStatus", captured.screenshotStatus(),
                    "screenshotReason", captured.screenshotReason(), "domStatus", captured.domStatus(),
                    "domReason", captured.domReason(), "coverage", coverage));
            return captured;
        }
        catch (Exception error)
        {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            Throwable cause = error instanceof java.util.concurrent.ExecutionException ? error.getCause() : error;
            String code = cause instanceof InterruptedException
                || cause instanceof IllegalStateException && "TURN_STOPPED".equals(cause.getMessage()) ? "TURN_STOPPED"
                : cause instanceof java.util.concurrent.TimeoutException ? "CONTEXT_TIMEOUT"
                : cause instanceof RuntimeProblem problem ? problem.code() : "CONTEXT_CAPTURE_FAILED";
            store.endOperation(principal, turnId, operationId, "CONTEXT",
                "TURN_STOPPED".equals(code) ? "CANCELLED" : "FAILED", code, null, null, null);
            throw problem(HttpStatus.CONFLICT, code);
        }
        finally { pending.remove(id, request); }
    }

    public Map<String, Object> upload(RuntimeAuthorization.Grant grant, long epoch, byte[] raw,
        Map<String, MultipartFile> files) throws IOException
    {
        if (raw.length == 0 || raw.length > 65536) throw problem(HttpStatus.BAD_REQUEST, "CONTEXT_METADATA_INVALID");
        JsonNode metadata;
        try { metadata = json.readTree(raw); }
        catch (IOException error) { throw problem(HttpStatus.BAD_REQUEST, "CONTEXT_METADATA_INVALID"); }
        String id = metadata.path("captureRequestId").asText();
        Pending request = pending.get(id);
        if (request == null || request.deadline().isBefore(Instant.now()))
            throw problem(HttpStatus.CONFLICT, "CONTEXT_REQUEST_EXPIRED");
        RuntimePrincipal principal = grant.principal();
        if (!grant.source().equals(request.authSource())
            || principal.sessionId() != request.principal().sessionId()
            || principal.accountId() != request.principal().accountId() || epoch != request.epoch()
            || !Long.toString(request.turnId()).equals(metadata.path("turnId").asText())
            || !Long.toString(epoch).equals(metadata.path("connectionEpoch").asText())
            || !request.source().equals(metadata.path("source").asText())
            || !request.coverage().equals(metadata.path("coverage").path("mode").asText()))
            throw problem(HttpStatus.FORBIDDEN, "CONTEXT_REQUEST_MISMATCH");
        requireActive(grant.id(), principal, epoch, request.turnId());
        JsonNode current = currentPolicy(principal);
        if (!request.currentPolicy().equals(current)) throw problem(HttpStatus.CONFLICT, "CONTEXT_POLICY_CHANGED");
        checkPolicy(request.fixedPolicy(), current, request.source(), request.coverage(), request.resultMode(), request.ai());
        JsonNode screenshot = metadata.path("screenshot"), dom = metadata.path("dom");
        String screenshotStatus = screenshot.path("status").asText(), domStatus = dom.path("status").asText();
        String screenshotReason = screenshot.path("reason").asText(""), domReason = dom.path("reason").asText("");
        if (!Set.of("SUCCESS", "FAILED").contains(screenshotStatus)
            || !Set.of("SUCCESS", "FAILED", "NOT_REQUESTED").contains(domStatus)
            || "PAGE".equals(request.source()) && !"NOT_REQUESTED".equals(domStatus)
            || "HYBRID".equals(request.source()) && "NOT_REQUESTED".equals(domStatus)
            || "FAILED".equals(screenshotStatus) != Set.of("CONTEXT_SCOPE_DENIED", "CONTEXT_CANCELLED",
                "CONTEXT_TIMEOUT", "CROSS_ORIGIN", "PIXEL_LIMIT", "SCREENSHOT_FAILED").contains(screenshotReason)
            || "FAILED".equals(domStatus) != "FILTER_FAILED".equals(domReason)
            || "NOT_REQUESTED".equals(domStatus) && !domReason.isEmpty())
            throw problem(HttpStatus.BAD_REQUEST, "CONTEXT_METADATA_INVALID");
        int width = metadata.path("coverage").path("width").asInt(), height = metadata.path("coverage").path("height").asInt();
        int x = metadata.path("coverage").path("x").asInt(-1), y = metadata.path("coverage").path("y").asInt(-1);
        if (width < 1 || width > 4096 || height < 1 || height > 4096
            || x < 0 || y < 0 || x > 1_000_000 || y > 1_000_000
            || files.size() > 4 || files.containsKey("metadata"))
            throw problem(HttpStatus.BAD_REQUEST, "PIXEL_LIMIT");
        List<byte[]> images = new ArrayList<>();
        long bytes = 0;
        JsonNode parts = screenshot.path("parts");
        if (!parts.isArray() || parts.size() != ("SUCCESS".equals(screenshotStatus) ? files.size() : 0))
            throw problem(HttpStatus.BAD_REQUEST, "CONTEXT_METADATA_INVALID");
        if ("SUCCESS".equals(screenshotStatus) && parts.isEmpty())
            throw problem(HttpStatus.BAD_REQUEST, "CONTEXT_IMAGE_INVALID");
        int nextY = 0;
        for (int index = 0; index < parts.size(); index++)
        {
            String name = "screenshot" + index;
            MultipartFile file = files.get(name);
            if (file == null || file.getSize() > 1_200_000)
                throw problem(HttpStatus.BAD_REQUEST, "CONTEXT_IMAGE_INVALID");
            byte[] image = file.getBytes();
            JsonNode part = parts.get(index);
            if (!name.equals(parts.get(index).path("field").asText()) || image.length < 4
                || !"image/jpeg".equals(file.getContentType()) || (image[0] & 255) != 255
                || (image[1] & 255) != 216 || (image[image.length - 2] & 255) != 255
                || (image[image.length - 1] & 255) != 217 || part.path("x").asInt(-1) != 0
                || part.path("y").asInt(-1) != nextY || part.path("width").asInt(-1) != width
                || part.path("height").asInt(-1) < 1 || part.path("height").asInt() > 1024)
                throw problem(HttpStatus.BAD_REQUEST, "CONTEXT_IMAGE_INVALID");
            nextY += part.path("height").asInt();
            try (javax.imageio.stream.ImageInputStream input = javax.imageio.ImageIO.createImageInputStream(new ByteArrayInputStream(image)))
            {
                if (input == null) throw problem(HttpStatus.BAD_REQUEST, "CONTEXT_IMAGE_INVALID");
                java.util.Iterator<javax.imageio.ImageReader> readers = javax.imageio.ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw problem(HttpStatus.BAD_REQUEST, "CONTEXT_IMAGE_INVALID");
                javax.imageio.ImageReader reader = readers.next();
                try
                {
                    reader.setInput(input, true, true);
                    if (reader.getWidth(0) != width || reader.getHeight(0) != part.path("height").asInt())
                        throw problem(HttpStatus.BAD_REQUEST, "CONTEXT_IMAGE_INVALID");
                }
                finally { reader.dispose(); }
            }
            bytes += image.length;
            if (bytes > 1_200_000) throw problem(HttpStatus.BAD_REQUEST, "CONTEXT_TOO_LARGE");
            images.add(image);
        }
        if ("SUCCESS".equals(screenshotStatus) && nextY != height)
            throw problem(HttpStatus.BAD_REQUEST, "CONTEXT_IMAGE_INVALID");
        String text = "";
        Set<String> refs = new HashSet<>();
        List<ElementExcerpt> elements = new ArrayList<>();
        if ("SUCCESS".equals(domStatus))
        {
            if (!"HYBRID".equals(request.source()) || !dom.path("text").isTextual()
                || dom.path("text").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32768
                || !dom.path("elements").isArray() || dom.path("elements").size() > 100)
                throw problem(HttpStatus.BAD_REQUEST, "CONTEXT_DOM_INVALID");
            text = dom.path("text").asText();
            for (JsonNode element : dom.path("elements"))
            {
                String ref = element.path("ref").asText();
                String excerpt = element.path("text").asText();
                if (!ref.matches("[a-fA-F0-9-]{36}") || !refs.add(ref)
                    || !element.path("text").isTextual()
                    || excerpt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 512)
                    throw problem(HttpStatus.BAD_REQUEST, "CONTEXT_DOM_INVALID");
                elements.add(new ElementExcerpt(ref, excerpt));
            }
        }
        else if (dom.has("text") || dom.has("elements")) throw problem(HttpStatus.BAD_REQUEST, "CONTEXT_DOM_INVALID");
        boolean screenshotOk = "SUCCESS".equals(screenshotStatus), domOk = "SUCCESS".equals(domStatus);
        if ("STRICT".equals(request.resultMode()) ? !screenshotOk || "HYBRID".equals(request.source()) && !domOk
            : !screenshotOk && !domOk)
        {
            RuntimeProblem error = problem(HttpStatus.UNPROCESSABLE_ENTITY, "CONTEXT_CAPTURE_FAILED");
            request.result().completeExceptionally(error);
            throw error;
        }
        Capture captured = new Capture(id, screenshotStatus, screenshotReason,
            domStatus, domReason, text, List.copyOf(images), List.copyOf(elements), Set.copyOf(refs));
        if (!request.result().complete(captured)) throw problem(HttpStatus.CONFLICT, "CONTEXT_REQUEST_EXPIRED");
        return Map.of("captureRequestId", id, "screenshotStatus", screenshotStatus,
            "domStatus", domStatus, "coverage", metadata.path("coverage"));
    }

    public String highlight(RuntimeAuthorization.Grant grant, long epoch, long turnId, String captureId,
        String elementRef, boolean scrollIntoView)
    {
        RuntimePrincipal principal = grant.principal();
        requireActive(grant.id(), principal, epoch, turnId);
        References refs = references.get(turnId);
        if (refs == null || refs.epoch() != epoch || !refs.captureId().equals(captureId)
            || !refs.refs().contains(elementRef) || !refs.currentPolicy().equals(currentPolicy(principal)))
            throw problem(HttpStatus.CONFLICT, "TARGET_STALE");
        JsonNode fixed = fixedPolicy(principal), current = currentPolicy(principal);
        if (scrollIntoView && (!fixed.path("allowScroll").asBoolean() || !current.path("allowScroll").asBoolean()))
            throw problem(HttpStatus.FORBIDDEN, "GUIDANCE_SCROLL_DENIED");
        String id = UUID.randomUUID().toString();
        Guidance proposed = new Guidance(principal.sessionId(), epoch, turnId);
        guidance.put(id, proposed);
        events.chat(principal, epoch, turnId, "runtime", "guidance.proposed",
            Map.of("guidanceId", id, "captureRequestId", captureId, "elementRef", elementRef,
                "scrollIntoView", scrollIntoView));
        try { return proposed.result().get(5, TimeUnit.SECONDS); }
        catch (Exception error) { return "TIMEOUT"; }
        finally { guidance.remove(id, proposed); }
    }

    public void guidanceResult(RuntimeAuthorization.Grant grant, long epoch, long turnId, String id, String status)
    {
        Guidance proposed = guidance.get(id);
        if (proposed == null || proposed.sessionId() != grant.principal().sessionId()
            || proposed.epoch() != epoch || proposed.turnId() != turnId
            || !Set.of("HIGHLIGHTED", "EVENT_ONLY", "TARGET_STALE").contains(status))
            throw problem(HttpStatus.CONFLICT, "TARGET_STALE");
        requireActive(grant.id(), grant.principal(), epoch, turnId);
        proposed.result().complete(status);
    }

    public void clear(long turnId)
    {
        references.remove(turnId);
        pending.values().stream().filter(item -> item.turnId() == turnId).forEach(item -> {
            pending.remove(item.id(), item);
            item.result().completeExceptionally(new IllegalStateException("TURN_STOPPED"));
        });
        guidance.values().stream().filter(item -> item.turnId() == turnId).forEach(item -> item.result().complete("TARGET_STALE"));
    }

    private JsonNode currentPolicy(RuntimePrincipal principal)
    {
        JsonNode config = system.chatConfig(principal.accountId(), principal.applicationId(),
            principal.configVersionId(), principal.sessionId());
        return parsePolicy(config.path("currentPolicy"));
    }

    private JsonNode fixedPolicy(RuntimePrincipal principal)
    {
        JsonNode config = system.chatConfig(principal.accountId(), principal.applicationId(),
            principal.configVersionId(), principal.sessionId());
        return parsePolicy(config.path("contextPolicy"));
    }

    private JsonNode parsePolicy(JsonNode policy)
    {
        if (policy.isTextual()) try { policy = json.readTree(policy.asText()); }
        catch (IOException error) { throw problem(HttpStatus.CONFLICT, "CONTEXT_POLICY_INVALID"); }
        return policy;
    }

    private void requireActive(long grantId, RuntimePrincipal principal, long epoch, long turnId)
    {
        if (!access.verify(grantId).principal().scopes().contains("context:capture")
            || !epochs.current(principal, epoch) || !store.active(principal, turnId, epoch))
            throw problem(HttpStatus.FORBIDDEN, "CONTEXT_NOT_ALLOWED");
    }

    static void checkPolicy(JsonNode fixed, JsonNode current, String source, String coverage,
        String resultMode, boolean ai)
    {
        if (!Set.of("PAGE", "HYBRID").contains(source) || !Set.of("VIEWPORT", "FULL_PAGE").contains(coverage)
            || !Set.of("PARTIAL", "STRICT").contains(resultMode))
            throw problem(HttpStatus.BAD_REQUEST, "CONTEXT_REQUEST_INVALID");
        for (JsonNode policy : List.of(fixed, current))
            if (!policy.path("enabled").asBoolean() || !contains(policy.path("sources"), source)
                || !contains(policy.path("modes"), ai ? "AI_ON_DEMAND" : "EXPLICIT")
                || !policy.path("captureScope").path("allowViewport").asBoolean()
                || "STRICT".equals(policy.path("resultMode").asText()) && "PARTIAL".equals(resultMode)
                || "FULL_PAGE".equals(coverage) && (ai || !policy.path("fullPageEnabled").asBoolean()))
                throw problem(HttpStatus.FORBIDDEN, "CONTEXT_NOT_ALLOWED");
    }

    private static boolean contains(JsonNode values, String value)
    { for (JsonNode item : values) if (value.equals(item.asText())) return true; return false; }
    private static RuntimeProblem problem(HttpStatus status, String code)
    { return new RuntimeProblem(status, code, code); }

    public record Capture(String captureRequestId, String screenshotStatus, String screenshotReason,
        String domStatus, String domReason,
        String text, List<byte[]> images, List<ElementExcerpt> elements, Set<String> elementRefs) { }
    public record ElementExcerpt(String ref, String text) { }
    private record Pending(String id, long grantId, String authSource, RuntimePrincipal principal, long epoch, long turnId,
        String source, String coverage, String resultMode, boolean ai, JsonNode fixedPolicy, JsonNode currentPolicy,
        Instant deadline, long operationId, CompletableFuture<Capture> result)
    {
        private Pending(String id, long grantId, String authSource, RuntimePrincipal principal, long epoch, long turnId,
            String source, String coverage, String resultMode, boolean ai, JsonNode fixedPolicy, JsonNode currentPolicy,
            Instant deadline, long operationId)
        { this(id, grantId, authSource, principal, epoch, turnId, source, coverage, resultMode, ai, fixedPolicy, currentPolicy,
            deadline, operationId, new CompletableFuture<>()); }
    }
    private record References(String captureId, long epoch, JsonNode currentPolicy, Set<String> refs) { }
    private record Guidance(long sessionId, long epoch, long turnId, CompletableFuture<String> result)
    {
        private Guidance(long sessionId, long epoch, long turnId)
        { this(sessionId, epoch, turnId, new CompletableFuture<>()); }
    }
}
