package com.ruoyi.session.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.session.business.BusinessCredentialStore;
import com.ruoyi.session.business.BusinessSystemClient;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** One bounded in-memory CHAT turn. Relay owns cross-turn memory; only redacted facts survive here. */
@Service
public class ChatRuntimeService implements IChatRuntimeService
{
    private final ChatTurnStore store;
    private final BusinessSystemClient system;
    private final BusinessCredentialStore credentials;
    private final RuntimeAuthorization access;
    private final RuntimeConnectionEpochs epochs;
    private final RuntimeEventPublisher events;
    private final PinnedHttps https;
    private final ChatToolPolicy policy;
    private final ObjectMapper json;
    private final SpeakOnlyRuntimeService speech;
    private final TtsSubmissionService submissions;
    private final ContextRuntimeService context;
    private final Map<Long, Running> running = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 8, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.AbortPolicy());

    public ChatRuntimeService(ChatTurnStore store, BusinessSystemClient system, BusinessCredentialStore credentials,
        RuntimeAuthorization access, RuntimeConnectionEpochs epochs, RuntimeEventPublisher events,
        PinnedHttps https, ChatToolPolicy policy, ObjectMapper json,
        SpeakOnlyRuntimeService speech, TtsSubmissionService submissions, ContextRuntimeService context)
    {
        this.store = store; this.system = system; this.credentials = credentials; this.access = access;
        this.epochs = epochs; this.events = events; this.https = https; this.policy = policy; this.json = json;
        this.speech = speech; this.submissions = submissions; this.context = context;
    }

    @Override
    public Started start(RuntimeAuthorization.Grant grant, long epoch, String requestId, String text, JsonNode data)
    {
        RuntimePrincipal principal = grant.principal();
        if (!java.util.Set.of("BUSINESS_KEY", "CONSOLE_DEBUG").contains(grant.source())
            || !principal.scopes().contains("chat:write"))
            throw problem("CAPABILITY_NOT_ALLOWED");
        if (text == null || text.isBlank() || text.length() > 4096)
            throw problem("INVALID_ARGUMENT");
        ObjectNode config = (ObjectNode) system.chatConfig(principal.accountId(), principal.applicationId(),
            principal.configVersionId(), principal.sessionId());
        for (String field : List.of("parameters", "capabilities", "runtimeLimits", "contextPolicy", "currentPolicy"))
        {
            JsonNode value = config.path(field);
            if (value.isTextual()) try { config.set(field, json.readTree(value.asText())); }
            catch (IOException error) { throw problem("CHAT_CONFIG_INVALID"); }
        }
        if (config.path("llmRelayVersionId").asLong() <= 0 || config.path("model").asText().isBlank())
            throw problem("CHAT_CONFIG_INVALID");
        List<JsonNode> skills = new ArrayList<>();
        StringBuilder prompt = new StringBuilder(config.path("systemPrompt").asText(""));
        for (JsonNode versionId : config.path("skillVersionIds"))
        {
            ObjectNode skill = (ObjectNode) system.resolveSkill(principal.accountId(), principal.applicationId(),
                principal.sessionId(), versionId.asLong());
            skill.put("skillVersionId", versionId.asLong());
            if ("PROMPT".equals(skill.path("skillType").asText()))
            {
                prompt.append("\n").append(skill.path("instructions").asText());
                if (prompt.length() > 65536) throw problem("FIXED_PROMPT_TOO_LARGE");
            }
            else if ("HTTP_TOOL".equals(skill.path("skillType").asText())) skills.add(skill);
        }
        int maxCalls = Math.min(20, Math.max(0, config.path("runtimeLimits").path("toolCallsPerTurn").asInt(10)));
        if (!config.path("capabilities").path("tool").asBoolean(false)) maxCalls = 0;
        JsonNode businessContext = data == null ? null : data.path("businessContext");
        if (businessContext != null && !businessContext.isMissingNode() && !businessContext.isNull()
            && (!businessContext.isObject() || businessContext.toString().getBytes(StandardCharsets.UTF_8).length > 32768))
            throw problem("CONTEXT_TOO_LARGE");
        JsonNode explicit = data == null ? null : data.path("contextRequest");
        String contextMode = data == null ? "" : data.path("contextMode").asText("");
        if (!contextMode.isEmpty() && !"AI_ON_DEMAND".equals(contextMode)) throw problem("CONTEXT_REQUEST_INVALID");
        boolean aiOnDemand = "AI_ON_DEMAND".equals(contextMode);
        if (explicit != null && !explicit.isMissingNode() && !explicit.isNull()
            && (!explicit.isObject() || explicit.size() > 3 || aiOnDemand || !principal.scopes().contains("context:capture")))
            throw problem("CONTEXT_NOT_ALLOWED");
        int maxCaptures = Math.min(5, Math.max(0, Math.min(config.path("contextPolicy").path("maxCapturesPerTurn").asInt(0),
            config.path("runtimeLimits").path("capturesPerTurn").asInt(0))));
        if (explicit != null && !explicit.isMissingNode() && !explicit.isNull() && maxCaptures == 0)
            throw problem("CONTEXT_NOT_ALLOWED");
        if (aiOnDemand && (maxCaptures == 0 || maxCalls == 0 || !principal.scopes().contains("context:capture")))
            throw problem("CONTEXT_NOT_ALLOWED");
        ChatTurnStore.Started started = store.start(principal, epoch, requestId, text);
        if (started.priorTurnId() != null)
        {
            cancel(started.priorTurnId());
            speech.stop(principal, Long.toString(started.priorTurnId()));
        }
        String externalUserId = "CONSOLE_DEBUG".equals(grant.source())
            ? "__ln_debug__:" + principal.sessionId() : started.externalUserId();
        Running current = new Running(grant.id(), grant.source(), principal, epoch, requestId, started.turnId(),
            externalUserId, text, prompt.toString(), config, skills, maxCalls, maxCaptures,
            businessContext, explicit, aiOnDemand);
        running.put(started.turnId(), current);
        return new Started(started.turnId(), started.priorTurnId());
    }

    @Override
    public void launch(long turnId)
    {
        Running turn = running.get(turnId);
        if (turn == null) return;
        FutureTask<Void> task = new FutureTask<>(() -> { execute(turn); return null; });
        if (!turn.task.compareAndSet(null, task)) return;
        try { workers.execute(task); }
        catch (java.util.concurrent.RejectedExecutionException error)
        {
            running.remove(turnId, turn);
            store.finish(turn.principal, turnId, false, "CHAT_BUSY");
            events.chat(turn.principal, turn.epoch, turnId, turn.requestId, "turn.failed",
                Map.of("code", "CHAT_BUSY"));
        }
    }

    @Override
    public boolean stop(RuntimePrincipal principal, long turnId, String reason)
    {
        boolean changed = store.stop(principal, turnId, reason);
        cancel(turnId);
        return changed;
    }

    @Override
    public void cancelSession(long sessionId, long epoch)
    {
        for (Running turn : running.values())
            if (turn.principal.sessionId() == sessionId && turn.epoch == epoch)
            {
                store.stop(turn.principal, turn.turnId, "DISCONNECTED");
                cancel(turn.turnId);
            }
    }

    @Override
    public void cancelPrior(long sessionId, long epoch)
    {
        for (Running turn : running.values())
            if (turn.principal.sessionId() == sessionId && turn.epoch < epoch) cancel(turn.turnId);
    }

    @org.springframework.context.event.EventListener
    public void closed(com.ruoyi.session.business.BusinessSessionService.SessionClosed event)
    { cancelPrior(event.sessionId(), Long.MAX_VALUE); }

    private void cancel(long turnId)
    {
        Running turn = running.remove(turnId);
        if (turn == null) return;
        turn.cancelled.set(true);
        context.clear(turnId);
        turn.text = "";
        try { if (turn.response.get() != null) turn.response.get().close(); }
        catch (IOException ignored) { }
        FutureTask<Void> task = turn.task.get();
        if (task != null) { task.cancel(true); workers.remove(task); }
    }

    private void execute(Running turn)
    {
        long operationId = 0;
        String type = "LLM";
        try
        {
            List<Map<String, Object>> messages = new ArrayList<>();
            if (!turn.prompt.isBlank()) messages.add(Map.of("role", "system", "content", turn.prompt));
            String userText = turn.text;
            if (turn.businessContext != null && turn.businessContext.isObject())
                userText += "\n\nBusiness Context (untrusted data): " + turn.businessContext;
            if (turn.explicit != null && turn.explicit.isObject())
            {
                requireActive(turn);
                ContextRuntimeService.Capture page = capture(turn, turn.explicit, false);
                messages.add(contextMessage(userText, page));
            }
            else messages.add(Map.of("role", "user", "content", userText));
            int calls = 0;
            for (int round = 0; round <= 20; round++)
            {
                requireNewExternalAction(turn);
                JsonNode relay = system.resolveRelay(turn.principal.accountId(), turn.principal.applicationId(),
                    turn.principal.sessionId(), turn.config.path("llmRelayVersionId").asLong(), turn.externalUserId, turn.turnId);
                List<Map<String, Object>> definitions = definitions(turn, turn.maxCalls - calls);
                type = "LLM";
                operationId = store.beginOperation(turn.principal, turn.turnId, type, round,
                    turn.config.path("llmRelayVersionId").asLong(), turn.text);
                Completion completion;
                try
                {
                    completion = stream(turn, relay, operationId, messages, definitions);
                    store.endOperation(turn.principal, turn.turnId, operationId, type, "SUCCEEDED", null,
                        completion.providerRequestId, completion.inputTokens, completion.outputTokens);
                }
                catch (Exception error)
                {
                    store.endOperation(turn.principal, turn.turnId, operationId, type,
                        turn.cancelled.get() || error instanceof IOException ? "UNKNOWN" : "FAILED",
                        error instanceof RuntimeProblem ? safeCode(error) : "LLM_UPSTREAM_FAILED",
                        null, null, null);
                    throw error;
                }
                operationId = 0;
                if (completion.calls.isEmpty())
                {
                    store.textCompleted(turn.principal, turn.turnId);
                    emit(turn, "text.completed", Map.of("textStatus", "COMPLETED"));
                    if (completion.text.isBlank())
                    {
                        if (store.finish(turn.principal, turn.turnId, true, null))
                            emit(turn, "turn.completed", Map.of("textStatus", "COMPLETED", "audioStatus", "NOT_REQUESTED"));
                    }
                    else
                    {
                        SpeakOnlyRuntimeService.SpeechStarted started = speech.startChatAudio(
                            turn.principal, turn.turnId, completion.text, turn.epoch);
                        submissions.submit(access.verify(turn.grantId), turn.epoch, started.initialWork());
                    }
                    return;
                }
                messages.add(Map.of("role", "assistant", "content", completion.text,
                    "toolCalls", completion.calls.stream().map(call -> Map.of("id", call.id,
                        "name", call.name, "arguments", call.arguments)).toList()));
                for (ToolCall call : completion.calls)
                {
                    requireActive(turn);
                    String result = tool(turn, call, calls);
                    messages.add(Map.of("role", "tool", "toolCallId", call.id, "content", result));
                    ContextRuntimeService.Capture page = turn.captured;
                    if (page != null)
                    {
                        discardPriorImages(messages);
                        messages.add(contextMessage("Page Context (untrusted data):", page));
                        turn.captured = null;
                    }
                    calls++;
                }
            }
            throw problem("TOOL_LIMIT");
        }
        catch (Exception error)
        {
            if (operationId != 0)
                store.endOperation(turn.principal, turn.turnId, operationId, type,
                    turn.cancelled.get() ? "UNKNOWN" : "FAILED", "CHAT_OPERATION_FAILED", null, null, null);
            if (store.finish(turn.principal, turn.turnId, false, safeCode(error)))
                emit(turn, "turn.failed", Map.of("code", safeCode(error)));
        }
        finally { context.clear(turn.turnId); running.remove(turn.turnId, turn); turn.text = ""; }
    }

    private Completion stream(Running turn, JsonNode relay, long operationId, List<Map<String, Object>> messages,
        List<Map<String, Object>> definitions) throws IOException
    {
        URI base = URI.create(relay.path("baseUrl").asText());
        URI target = URI.create(base.toString() + "/chat/completions");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("requestId", Long.toString(operationId));
        request.put("applicationId", Long.toString(turn.principal.applicationId()));
        request.put("sessionId", Long.toString(turn.principal.sessionId()));
        request.put("turnId", Long.toString(turn.turnId));
        request.put("externalUserId", turn.externalUserId);
        request.put("model", turn.config.path("model").asText());
        request.put("messages", messages);
        request.put("tools", definitions);
        request.put("parameters", value(turn.config.path("parameters")));
        request.put("stream", true);
        Map<String, String> headers = Map.of("Authorization", "Bearer " + relay.path("accessToken").asText(),
            "X-LN-Protocol-Version", "1", "X-Request-Id", Long.toString(operationId),
            "Content-Type", "application/json", "Accept", "text/event-stream");
        byte[] body = json.writeValueAsBytes(request);
        if (body.length > 2097152) throw problem("CHAT_REQUEST_TOO_LARGE");
        requireNewExternalAction(turn);
        StringBuilder text = new StringBuilder();
        Map<String, ToolBuffer> tools = new LinkedHashMap<>();
        JsonNode terminal = null;
        try (PinnedHttps.Response response = https.request(target, relay.path("pinnedAddress").asText(),
            "POST", headers, body, relay.path("timeoutMs").asInt(30000),
            relay.path("maxResponseBytes").asLong(1048576), () -> requireNewExternalAction(turn)))
        {
            turn.response.set(response);
            if (turn.cancelled.get()) response.close();
            if (!response.contentType().startsWith("text/event-stream")) throw new IOException("Invalid SSE response");
            BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
            String event = "", line;
            while ((line = reader.readLine()) != null)
            {
                requireActive(turn);
                if (line.startsWith("event:")) event = line.substring(6).trim();
                if (!line.startsWith("data:")) continue;
                JsonNode data = json.readTree(line.substring(5).trim());
                String kind = event.isBlank() ? data.path("type").asText() : event;
                event = "";
                switch (kind)
                {
                    case "text.delta" -> {
                        String delta = data.path("text").asText(data.path("delta").asText());
                        if (text.length() + delta.length() > 65536) throw new IOException("Text too large");
                        text.append(delta);
                        emit(turn, "text.delta", Map.of("text", delta));
                    }
                    case "tool_call.delta" -> {
                        String id = data.path("id").asText();
                        if (!id.matches("[A-Za-z0-9_-]{1,64}") || tools.size() >= 20 && !tools.containsKey(id))
                            throw new IOException("Invalid Tool call");
                        ToolBuffer buffer = tools.computeIfAbsent(id, ignored -> new ToolBuffer(id));
                        String name = data.path("name").asText();
                        if (!name.isEmpty()) buffer.name.append(name);
                        if (buffer.name.length() > 128) throw new IOException("Tool name too large");
                        buffer.arguments.append(data.path("arguments").asText(""));
                        if (buffer.arguments.length() > 32768) throw new IOException("Tool arguments too large");
                    }
                    case "response.completed" -> { terminal = data; break; }
                    case "error" -> throw new IOException("LLM error");
                    default -> throw new IOException("Unknown SSE event");
                }
                if (terminal != null) break;
            }
        }
        finally { turn.response.set(null); }
        if (terminal == null) throw new IOException("SSE ended before completion");
        List<ToolCall> calls = new ArrayList<>();
        for (ToolBuffer tool : tools.values())
            calls.add(new ToolCall(tool.id, tool.name.toString(), tool.arguments.toString()));
        JsonNode usage = terminal.path("usage");
        Long inputTokens = nullableLong(usage, "inputTokens");
        Long outputTokens = nullableLong(usage, "outputTokens");
        if (inputTokens != null && inputTokens < 0 || outputTokens != null && outputTokens < 0)
            throw new IOException("Invalid usage");
        String providerRequestId = terminal.path("providerRequestId").asText(null);
        if (providerRequestId != null && !providerRequestId.matches("[A-Za-z0-9._:-]{1,128}"))
            throw new IOException("Invalid provider request ID");
        return new Completion(text.toString(), calls, inputTokens, outputTokens, providerRequestId);
    }

    private String tool(Running turn, ToolCall call, int ordinal)
    {
        long operationId = 0;
        try
        {
            requireNewExternalAction(turn);
            if (ordinal >= turn.maxCalls) throw problem("TOOL_LIMIT");
            if ("ln_capture_context".equals(call.name))
            {
                if (!turn.aiOnDemand) throw problem("CONTEXT_NOT_ALLOWED");
                JsonNode args = json.readTree(call.arguments);
                if (!args.isObject() || args.size() != 1) throw problem("CONTEXT_REQUEST_INVALID");
                ContextRuntimeService.Capture page = capture(turn, args, true);
                turn.captured = page;
                return json.createObjectNode().put("status", "SUCCESS")
                    .put("captureRequestId", page.captureRequestId())
                    .put("screenshotStatus", page.screenshotStatus()).put("screenshotReason", page.screenshotReason())
                    .put("domStatus", page.domStatus()).put("domReason", page.domReason()).toString();
            }
            if ("ln_highlight_element".equals(call.name))
            {
                JsonNode args = json.readTree(call.arguments);
                if (!args.isObject() || args.size() < 2 || args.size() > 3) throw problem("CONTEXT_REQUEST_INVALID");
                String status = context.highlight(access.verify(turn.grantId), turn.epoch, turn.turnId,
                    args.path("captureRequestId").asText(), args.path("elementRef").asText(),
                    args.path("scrollIntoView").asBoolean(false));
                return json.createObjectNode().put("status", status).toString();
            }
            long versionId = toolVersion(turn, call.name);
            JsonNode skill = system.resolveSkill(turn.principal.accountId(), turn.principal.applicationId(),
                turn.principal.sessionId(), versionId);
            if (!"HTTP_TOOL".equals(skill.path("skillType").asText()) || !call.name.equals(skill.path("toolName").asText()))
                throw problem("TOOL_NOT_ALLOWED");
            JsonNode args;
            try { args = json.readTree(call.arguments); }
            catch (IOException error) { throw problem("TOOL_ARGUMENT_INVALID"); }
            try { policy.validate(skill.path("inputSchema"), args); }
            catch (IllegalArgumentException error) { throw problem("TOOL_ARGUMENT_INVALID"); }
            int perTool = skill.path("maxCallsPerTurn").asInt(1);
            long already = turn.toolCalls.merge(call.name, 1L, Long::sum);
            if (already > perTool) throw problem("TOOL_LIMIT");
            String credential = skill.path("requiresUserCredential").asBoolean(false)
                ? credentials.readForTool(turn.principal.sessionId()) : null;
            if (skill.path("requiresUserCredential").asBoolean(false) && credential == null)
                throw problem("TOOL_CREDENTIAL_REQUIRED");
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Accept", "application/json");
            if (skill.hasNonNull("accessToken"))
                headers.put("Authorization", "Bearer " + skill.path("accessToken").asText());
            JsonNode binding = skill.path("identityBinding");
            if (binding.has("externalUserId")) headers.put("X-LN-External-User-Id-B64",
                Base64.getUrlEncoder().withoutPadding().encodeToString(turn.externalUserId.getBytes(StandardCharsets.UTF_8)));
            if (binding.has("applicationId")) headers.put("X-LN-Application-Id", Long.toString(turn.principal.applicationId()));
            if (binding.has("sessionId")) headers.put("X-LN-Session-Id", Long.toString(turn.principal.sessionId()));
            if (credential != null) headers.put("X-LN-Query-Credential-B64",
                Base64.getUrlEncoder().withoutPadding().encodeToString(credential.getBytes(StandardCharsets.UTF_8)));
            String method = skill.path("httpMethod").asText();
            URI uri = URI.create(skill.path("toolUrl").asText());
            byte[] body = new byte[0];
            if ("GET".equals(method))
            {
                List<String> pairs = new ArrayList<>();
                args.fields().forEachRemaining(entry -> pairs.add(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                    + "=" + URLEncoder.encode(entry.getValue().asText(), StandardCharsets.UTF_8)));
                uri = URI.create(uri + "?" + String.join("&", pairs));
            }
            else
            {
                headers.put("Content-Type", "application/json");
                body = json.writeValueAsBytes(args);
            }
            requireNewExternalAction(turn);
            operationId = store.beginOperation(turn.principal, turn.turnId, "TOOL", ordinal, versionId, call.arguments);
            headers.put("X-Request-Id", Long.toString(operationId));
            requireNewExternalAction(turn);
            byte[] raw;
            try (PinnedHttps.Response response = https.request(uri, skill.path("pinnedAddress").asText(),
                method, headers, body, skill.path("timeoutMs").asInt(30000),
                skill.path("maxResultBytes").asLong(1048576), () -> requireNewExternalAction(turn)))
            {
                turn.response.set(response);
                if (turn.cancelled.get()) response.close();
                if (!response.contentType().startsWith("application/json")) throw new IOException("Invalid Tool response");
                raw = response.body().readAllBytes();
            }
            finally { turn.response.set(null); }
            ObjectNode result = policy.result(skill.path("outputSchema"), raw, skill.path("maxResultBytes").asLong());
            store.endOperation(turn.principal, turn.turnId, operationId, "TOOL", "SUCCEEDED", null, null, null, null);
            JsonNode fields = skill.path("frontendFields");
            List<String> allowed = new ArrayList<>();
            for (JsonNode field : fields) allowed.add(field.asText());
            ObjectNode publicResult = policy.frontend(result, allowed);
            if (!publicResult.isEmpty()) emit(turn, "tool.result", Map.of("toolName", call.name, "fields", publicResult));
            return result.toString();
        }
        catch (Exception error)
        {
            String code = safeCode(error);
            if (operationId != 0)
                store.endOperation(turn.principal, turn.turnId, operationId, "TOOL",
                    turn.cancelled.get() || error instanceof IOException ? "UNKNOWN" : "FAILED",
                    code, null, null, null);
            return json.createObjectNode().put("error", code).toString();
        }
    }

    private void requireActive(Running turn)
    {
        if (turn.cancelled.get() || Thread.currentThread().isInterrupted()
            || turn.startedAt.plusSeconds(300).isBefore(Instant.now())
            || !store.active(turn.principal, turn.turnId, turn.epoch)
            || !epochs.current(turn.principal, turn.epoch))
            throw problem("TURN_STOPPED");
        RuntimeAuthorization.Grant fresh = access.verify(turn.grantId);
        if (!fresh.principal().scopes().contains("chat:write")) throw problem("SCOPE_DENIED");
    }

    private void requireNewExternalAction(Running turn)
    {
        requireActive(turn);
        if ("CONSOLE_DEBUG".equals(turn.principalSource))
            system.chatConfig(turn.principal.accountId(), turn.principal.applicationId(),
                turn.principal.configVersionId(), turn.principal.sessionId());
    }

    private void emit(Running turn, String type, Map<String, Object> data)
    {
        if (!turn.cancelled.get() && (type.startsWith("turn.") || store.active(turn.principal, turn.turnId, turn.epoch)))
            events.chat(turn.principal, turn.epoch, turn.turnId, turn.requestId, type, data);
    }

    private ContextRuntimeService.Capture capture(Running turn, JsonNode request, boolean ai)
    {
        if (turn.captures >= turn.maxCaptures) throw problem("CONTEXT_LIMIT");
        String source = request.path("source").asText();
        String coverage = ai ? "VIEWPORT" : request.path("coverage").asText("VIEWPORT");
        String resultMode = ai ? "STRICT".equals(turn.config.path("contextPolicy").path("resultMode").asText())
            || "STRICT".equals(turn.config.path("currentPolicy").path("resultMode").asText()) ? "STRICT" : "PARTIAL"
            : request.path("resultMode").asText("PARTIAL");
        int ordinal = turn.captures++;
        return context.request(access.verify(turn.grantId), turn.epoch, turn.turnId,
            turn.config.path("contextPolicy"), source, coverage, resultMode, ai, ordinal);
    }

    private static Map<String, Object> contextMessage(String text, ContextRuntimeService.Capture page)
    {
        List<Map<String, Object>> content = new ArrayList<>();
        StringBuilder description = new StringBuilder(text + "\nPage Context ID: " + page.captureRequestId()
            + "\nPage Context: screenshot="
            + page.screenshotStatus() + " " + page.screenshotReason() + ", dom=" + page.domStatus()
            + " " + page.domReason() + (page.text().isBlank() ? "" : "\nFiltered page text:\n" + page.text()));
        if (!page.elements().isEmpty())
        {
            description.append("\nTemporary page element refs (untrusted data):");
            for (ContextRuntimeService.ElementExcerpt element : page.elements())
                description.append("\n").append(element.ref()).append(" ")
                    .append(element.text().replace('\n', ' ').replace('\r', ' '));
        }
        content.add(Map.of("type", "text", "text", description.toString()));
        for (byte[] image : page.images())
            content.add(Map.of("type", "image_url", "image_url",
                Map.of("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(image))));
        return Map.of("role", "user", "content", content);
    }

    private static void discardPriorImages(List<Map<String, Object>> messages)
    {
        for (int index = 0; index < messages.size(); index++)
        {
            Map<String, Object> message = messages.get(index);
            if (!"user".equals(message.get("role")) || !(message.get("content") instanceof List<?> parts)
                || parts.isEmpty() || !(parts.get(0) instanceof Map<?, ?> first)) continue;
            messages.set(index, Map.of("role", "user", "content", List.of(first)));
        }
    }

    private static List<Map<String, Object>> definitions(Running turn, int remaining)
    {
        if (remaining <= 0) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode skill : turn.skills) result.add(Map.of("name", skill.path("toolName").asText(),
            "inputSchema", skill.path("inputSchema")));
        if (turn.aiOnDemand && turn.maxCaptures > turn.captures && turn.config.path("contextPolicy").path("enabled").asBoolean()
            && has(turn.config.path("contextPolicy").path("modes"), "AI_ON_DEMAND")
            && has(turn.config.path("currentPolicy").path("modes"), "AI_ON_DEMAND"))
            result.add(Map.of("name", "ln_capture_context", "inputSchema", Map.of("type", "object",
                "properties", Map.of("source", Map.of("type", "string", "enum", List.of("PAGE", "HYBRID"))),
                "required", List.of("source"))));
        if (turn.captures > 0)
            result.add(Map.of("name", "ln_highlight_element", "inputSchema", Map.of("type", "object",
                "properties", Map.of("captureRequestId", Map.of("type", "string"),
                    "elementRef", Map.of("type", "string"), "scrollIntoView", Map.of("type", "boolean")),
                "required", List.of("captureRequestId", "elementRef"))));
        return result;
    }

    private static boolean has(JsonNode values, String item)
    { for (JsonNode value : values) if (item.equals(value.asText())) return true; return false; }

    private static long toolVersion(Running turn, String name)
    {
        for (JsonNode skill : turn.skills) if (name.equals(skill.path("toolName").asText()))
            return skill.path("skillVersionId").asLong();
        throw problem("TOOL_NOT_ALLOWED");
    }
    private static Object value(JsonNode node)
    {
        if (node == null || node.isMissingNode() || node.isNull()) return Map.of();
        return node;
    }
    private static Long nullableLong(JsonNode node, String field)
    { return node.path(field).canConvertToLong() ? node.path(field).asLong() : null; }
    private static RuntimeProblem problem(String code)
    { return new RuntimeProblem(HttpStatus.CONFLICT, code, code); }
    private static String safeCode(Exception error)
    { return error instanceof RuntimeProblem known ? known.code() : "UPSTREAM_FAILED"; }

    @jakarta.annotation.PreDestroy
    public void close() { workers.shutdownNow(); }

    private static final class Running
    {
        final long grantId, epoch, turnId;
        final String principalSource;
        final Instant startedAt = Instant.now();
        final RuntimePrincipal principal;
        final String requestId, externalUserId, prompt;
        volatile String text;
        final JsonNode config;
        final List<JsonNode> skills;
        final int maxCalls;
        final int maxCaptures;
        final JsonNode businessContext, explicit;
        final boolean aiOnDemand;
        int captures;
        volatile ContextRuntimeService.Capture captured;
        final Map<String, Long> toolCalls = new ConcurrentHashMap<>();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicReference<PinnedHttps.Response> response = new AtomicReference<>();
        final AtomicReference<FutureTask<Void>> task = new AtomicReference<>();
        Running(long grantId, String principalSource, RuntimePrincipal principal, long epoch, String requestId, long turnId,
            String externalUserId, String text, String prompt, JsonNode config, List<JsonNode> skills, int maxCalls,
            int maxCaptures, JsonNode businessContext, JsonNode explicit, boolean aiOnDemand)
        {
            this.grantId = grantId; this.principalSource = principalSource; this.principal = principal;
            this.epoch = epoch; this.requestId = requestId;
            this.turnId = turnId; this.externalUserId = externalUserId; this.text = text; this.prompt = prompt;
            this.config = config; this.skills = skills; this.maxCalls = maxCalls;
            this.maxCaptures = maxCaptures; this.businessContext = businessContext; this.explicit = explicit;
            this.aiOnDemand = aiOnDemand;
        }
    }
    private static final class ToolBuffer
    {
        final String id;
        final StringBuilder name = new StringBuilder();
        final StringBuilder arguments = new StringBuilder();
        ToolBuffer(String id) { this.id = id; }
    }
    private record ToolCall(String id, String name, String arguments) { }
    private record Completion(String text, List<ToolCall> calls, Long inputTokens,
        Long outputTokens, String providerRequestId) { }
}
