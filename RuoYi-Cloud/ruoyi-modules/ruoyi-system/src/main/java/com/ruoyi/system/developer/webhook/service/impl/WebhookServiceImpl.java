package com.ruoyi.system.developer.webhook.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.developer.webhook.mapper.WebhookMapper;
import com.ruoyi.system.developer.webhook.service.IWebhookService;
import com.ruoyi.system.developer.webhook.service.WebhookSender;
import com.ruoyi.system.officialservice.service.OfficialSecretCrypto;

@Service
public class WebhookServiceImpl implements IWebhookService
{
    private static final Set<String> EVENTS = Set.of("avatar.generation.succeeded", "avatar.generation.failed");
    private final WebhookMapper mapper;
    private final OfficialSecretCrypto crypto;
    private final WebhookSender sender;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();

    public WebhookServiceImpl(WebhookMapper mapper, OfficialSecretCrypto crypto, WebhookSender sender,
        ObjectMapper json, TransactionTemplate transactions)
    { this.mapper = mapper; this.crypto = crypto; this.sender = sender; this.json = json; this.transactions = transactions; }

    @Override public Map<String,Object> list(long accountId, int pageNum, int pageSize)
    {
        checkPage(accountId, pageNum, pageSize);
        return page(mapper.page(accountId, pageSize, (pageNum - 1) * pageSize).stream().map(this::view).toList(),
            mapper.count(accountId), pageNum, pageSize);
    }

    @Override @Transactional public Map<String,Object> create(long accountId, CreateInput input, String key)
    {
        requireKey(key);
        if (accountId <= 0 || input == null || input.name() == null || input.name().isBlank()
            || input.name().trim().length() > 100 || input.events() == null || input.events().isEmpty()
            || input.events().size() > 2 || new java.util.HashSet<>(input.events()).size() != input.events().size()
            || !EVENTS.containsAll(input.events())
            || input.timeoutMs() != null && (input.timeoutMs() < 1000 || input.timeoutMs() > 10000)
            || input.maxAttempts() != null && (input.maxAttempts() < 1 || input.maxAttempts() > 6)) throw bad("Webhook 参数无效");
        String url = sender.validate(input.url());
        CreateInput value = new CreateInput(input.name().trim(), url, List.copyOf(input.events()),
            input.timeoutMs() == null ? 5000 : input.timeoutMs(), input.maxAttempts() == null ? 6 : input.maxAttempts());
        byte[] hash = digest(value.toString());
        String scope = scope("create");
        lockAccount(accountId);
        Long duplicate = duplicate(accountId, scope, key, hash);
        if (duplicate != null) return withSecret(mapper.endpoint(accountId, duplicate), null);
        long id = id(), secretId = id();
        String secret = newSecret();
        mapper.insertSecret(crypto.encrypt(secretId, secret), accountId, value.name() + " Webhook");
        mapper.insertEndpoint(id, accountId, value, secretId, stringify(value.events()));
        mapper.remember(id(), accountId, scope, key, hash, id);
        return withSecret(mapper.endpoint(accountId, id), secret);
    }

    @Override @Transactional public Map<String,Object> rotate(long accountId, long endpointId, String ifMatch, String key)
    {
        requireKey(key);
        long revision = revision(ifMatch);
        String scope = scope("rotate:" + endpointId), material = endpointId + "|" + revision;
        byte[] hash = digest(material);
        lockAccount(accountId);
        Long duplicate = duplicate(accountId, scope, key, hash);
        if (duplicate != null) return withSecret(mapper.endpoint(accountId, duplicate), null);
        Map<String,Object> current = requireEndpoint(accountId, endpointId, true);
        if (number(current.get("revision")) != revision) throw conflict("Webhook 配置已变化", 412);
        long old = number(current.get("secretId")), next = id();
        String secret = newSecret();
        mapper.insertSecret(crypto.encrypt(next, secret), accountId, current.get("name") + " Webhook");
        if (mapper.rotate(accountId, endpointId, old, next, revision) != 1 || mapper.disableSecret(accountId, old) != 1)
            throw conflict("Webhook 签名密钥已变化", 409);
        mapper.remember(id(), accountId, scope, key, hash, endpointId);
        return withSecret(mapper.endpoint(accountId, endpointId), secret);
    }

    @Override @Transactional public Map<String,Object> status(long accountId, long endpointId, StatusInput input,
        String ifMatch, String key)
    {
        requireKey(key);
        if (input == null || !Set.of("ACTIVE", "DISABLED").contains(input.status())) throw bad("Webhook 状态无效");
        long revision = revision(ifMatch);
        String scope = scope("status:" + endpointId);
        byte[] hash = digest(endpointId + "|" + revision + "|" + input.status());
        lockAccount(accountId);
        Long duplicate = duplicate(accountId, scope, key, hash);
        if (duplicate != null) return view(mapper.endpoint(accountId, duplicate));
        Map<String,Object> current = requireEndpoint(accountId, endpointId, true);
        if (number(current.get("revision")) != revision) throw conflict("Webhook 配置已变化", 412);
        if (mapper.status(accountId, endpointId, input.status(), revision) != 1) throw conflict("Webhook 状态已变化", 409);
        mapper.remember(id(), accountId, scope, key, hash, endpointId);
        return view(mapper.endpoint(accountId, endpointId));
    }

    @Override public Map<String,Object> deliveries(long accountId, long endpointId, String from, String to,
        int pageNum, int pageSize)
    {
        checkPage(accountId, pageNum, pageSize);
        requireEndpoint(accountId, endpointId, false);
        DateRange dates = dates(from, to);
        return page(mapper.deliveries(accountId, endpointId, dates.from, dates.to, pageSize, (pageNum - 1) * pageSize)
            .stream().map(WebhookServiceImpl::stringIds).toList(),
            mapper.deliveryCount(accountId, endpointId, dates.from, dates.to), pageNum, pageSize);
    }

    @Override public Map<String,Object> attempts(long accountId, long deliveryId, String from, String to,
        int pageNum, int pageSize)
    {
        checkPage(accountId, pageNum, pageSize);
        if (deliveryId <= 0 || mapper.deliveryOwner(accountId, deliveryId) == null) throw conflict("投递记录不存在", 404);
        DateRange dates = dates(from, to);
        return page(mapper.attempts(accountId, deliveryId, dates.from, dates.to, pageSize, (pageNum - 1) * pageSize)
            .stream().map(WebhookServiceImpl::stringIds).toList(),
            mapper.attemptCount(accountId, deliveryId, dates.from, dates.to), pageNum, pageSize);
    }

    @Override public void requireActive(long accountId, long endpointId)
    {
        if (endpointId <= 0 || !"ACTIVE".equals(requireEndpoint(accountId, endpointId, true).get("status")))
            throw conflict("Webhook 不可用", 409);
    }

    @Override public void recordTerminal(long accountId, long taskId)
    { mapper.insertTerminalEvent(accountId, taskId); }

    @Override public void recordMissingTerminalEvents()
    { mapper.insertMissingTerminalEvents(); }

    /** One committed Outbox event becomes one durable delivery; redelivery is harmless. */
    public boolean materializeNext()
    {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            Map<String,Object> event = mapper.claimOutbox();
            if (event == null) return false;
            try { event.putAll(json.readValue(String.valueOf(event.remove("payload")),
                json.getTypeFactory().constructMapType(Map.class, String.class, Object.class))); }
            catch (Exception error) { throw new IllegalStateException("Webhook Outbox 载荷无效", error); }
            String taskStatus = String.valueOf(event.get("taskStatus"));
            String type = "SUCCEEDED".equals(taskStatus) ? "avatar.generation.succeeded" : "avatar.generation.failed";
            event.put("eventType", "SUCCEEDED".equals(taskStatus) ? "AVATAR_SUCCEEDED" : "AVATAR_FAILED");
            Map<String,Object> endpoint = mapper.currentEndpoint(number(event.get("endpointId")));
            if (endpoint == null || !events(endpoint).contains(type)) { mapper.finishOutbox(number(event.get("id"))); return true; }
            Map<String,Object> data = new LinkedHashMap<>();
            data.put("taskId", String.valueOf(event.get("taskId")));
            data.put("executionNo", event.get("executionNo"));
            data.put("avatarId", String.valueOf(event.get("avatarId")));
            data.put("candidateVersionId", String.valueOf(event.get("versionId")));
            data.put("status", taskStatus);
            data.put("publicationStatus", "SUCCEEDED".equals(taskStatus) ? "REVIEW" : null);
            data.put("error", "FAILED".equals(taskStatus) ? event.get("errorCode") : null);
            String at = String.valueOf(event.get("finishedAt"));
            Map<String,Object> payload = Map.of("id",event.get("eventId"),"type",type,"timestamp",at,
                "schemaVersion",1,"data",data);
            String snapshot = stringify(Map.of("url",endpoint.get("url"),"timeoutMs",endpoint.get("timeoutMs"),
                "maxAttempts",endpoint.get("maxAttempts"),"signatureVersion","v1"));
            mapper.insertDelivery(id(), event, stringify(payload), snapshot);
            mapper.finishOutbox(number(event.get("id")));
            return true;
        }));
    }

    public boolean deliverNext()
    {
        Dispatch dispatch = transactions.execute(status -> {
            Map<String,Object> delivery = mapper.claimDelivery();
            if (delivery == null) return null;
            long deliveryId = number(delivery.get("id"));
            int oldCount = (int)number(delivery.get("attemptCount"));
            if ("SENDING".equals(delivery.get("status"))) mapper.finishStaleAttempt(deliveryId, oldCount);
            Map<String,Object> endpoint = mapper.currentEndpoint(number(delivery.get("endpointId")));
            if (endpoint == null || !"ACTIVE".equals(endpoint.get("status")))
            { mapper.closeUnleased(deliveryId, "CANCELLED", "ENDPOINT_DISABLED"); return new Dispatch(null,null,null,null); }
            if (oldCount >= number(endpoint.get("maxAttempts")))
            { mapper.closeUnleased(deliveryId, "EXHAUSTED", "ATTEMPTS_EXHAUSTED"); return new Dispatch(null,null,null,null); }
            String owner = UUID.randomUUID().toString();
            if (mapper.leaseDelivery(deliveryId, owner) != 1) throw conflict("Webhook 租约已变化", 409);
            delivery.put("attemptCount", oldCount + 1);
            mapper.insertAttempt(id(), delivery);
            String secret = crypto.decrypt(mapper.secret(number(delivery.get("accountId")), number(endpoint.get("secretId"))));
            return new Dispatch(delivery, endpoint, owner, secret);
        });
        if (dispatch == null) return false;
        if (dispatch.owner() == null) return true;
        long started = System.nanoTime();
        WebhookSender.Result result = sender.send((String)dispatch.endpoint().get("url"),
            (String)dispatch.delivery().get("eventId"), (String)dispatch.delivery().get("payload"),
            dispatch.secret(), (int)number(dispatch.endpoint().get("timeoutMs")));
        long elapsedMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
        transactions.executeWithoutResult(status -> {
            long deliveryId = number(dispatch.delivery().get("id"));
            int attempt = (int)number(dispatch.delivery().get("attemptCount"));
            if (mapper.finishAttempt(deliveryId, attempt, result.httpStatus(), result.errorCode(), elapsedMs) != 1) return;
            boolean success = result.httpStatus() != null && result.httpStatus() >= 200 && result.httpStatus() < 300;
            boolean last = attempt >= number(dispatch.endpoint().get("maxAttempts"));
            String next = success ? "DELIVERED" : last ? "EXHAUSTED" : "PENDING";
            int[] delays = {0,60,300,1800,7200,43200};
            int delay = success || last ? 0 : delays[Math.min(attempt, delays.length - 1)] + random.nextInt(20);
            mapper.settleDelivery(deliveryId, dispatch.owner(), next, result.httpStatus(), result.errorCode(), delay);
        });
        return true;
    }

    private Map<String,Object> requireEndpoint(long accountId, long endpointId, boolean lock)
    {
        if (accountId <= 0 || endpointId <= 0) throw bad("Webhook 标识无效");
        Map<String,Object> value = lock ? mapper.lockEndpoint(accountId, endpointId) : mapper.endpoint(accountId, endpointId);
        if (value == null) throw conflict("Webhook 不存在", 404);
        return value;
    }
    private void lockAccount(long accountId) { if (accountId <= 0 || mapper.lockAccount(accountId) == null) throw conflict("账号不可用", 403); }
    private Long duplicate(long accountId, String scope, String key, byte[] hash)
    {
        Map<String,Object> old = mapper.idempotency(accountId, scope, key);
        if (old == null) return null;
        if (!MessageDigest.isEqual(hash, (byte[])old.get("requestHash"))) throw conflict("幂等键参数冲突", 409);
        return number(old.get("resourceId"));
    }
    private Map<String,Object> view(Map<String,Object> row)
    {
        if (row == null) throw conflict("Webhook 不存在", 404);
        Map<String,Object> result = stringIds(row);
        result.remove("secretId");
        result.put("events", events(row));
        return result;
    }
    private Map<String,Object> withSecret(Map<String,Object> row, String secret)
    {
        Map<String,Object> result = view(row);
        result.put("signingSecret", secret);
        result.put("secretAvailable", secret != null);
        return result;
    }
    private List<String> events(Map<String,Object> row)
    {
        try { return json.readValue(String.valueOf(row.get("events")),
            json.getTypeFactory().constructCollectionType(List.class, String.class)); }
        catch (Exception error) { throw new IllegalStateException("Webhook events 格式损坏", error); }
    }
    private String stringify(Object value)
    { try { return json.writeValueAsString(value); } catch (Exception error) { throw new IllegalStateException(error); } }
    private String newSecret()
    { byte[] bytes = new byte[32]; random.nextBytes(bytes); return "whsec_" + Base64.getEncoder().encodeToString(bytes); }
    private long id() { Long value = mapper.nextId(); if (value == null || value <= 0) throw new IllegalStateException("Webhook 标识不可用"); return value; }
    private static long number(Object value)
    { return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value)); }
    private static long revision(String value)
    { try { long n = Long.parseLong(value == null ? "" : value.replace("\"", "")); if (n > 0) return n; }
      catch (NumberFormatException ignored) { } throw conflict("缺少有效 If-Match", 428); }
    private static void requireKey(String value)
    { if (value == null || !value.matches("[\\x21-\\x7e]{1,64}")) throw bad("Idempotency-Key 无效"); }
    private static void checkPage(long accountId, int pageNum, int pageSize)
    { if (accountId <= 0 || pageNum < 1 || pageSize < 1 || pageSize > 100 || (long)(pageNum - 1) * pageSize > Integer.MAX_VALUE)
        throw bad("分页参数无效"); }
    private static DateRange dates(String from, String to)
    {
        if ((from == null || from.isBlank()) && (to == null || to.isBlank())) return new DateRange(null, null);
        try
        {
            LocalDate end = to == null || to.isBlank() ? LocalDate.now(java.time.ZoneOffset.UTC) : LocalDate.parse(to);
            LocalDate start = from == null || from.isBlank() ? end.minusDays(30) : LocalDate.parse(from);
            if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) > 30) throw bad("Webhook 查询最多31天");
            return new DateRange(start, end);
        }
        catch (java.time.format.DateTimeParseException error) { throw bad("日期应为 YYYY-MM-DD"); }
    }
    private static Map<String,Object> page(List<Map<String,Object>> rows, long total, int pageNum, int pageSize)
    { return Map.of("items",rows,"total",total,"pageNum",pageNum,"pageSize",pageSize); }
    private static Map<String,Object> stringIds(Map<String,Object> source)
    {
        Map<String,Object> result = new LinkedHashMap<>(source);
        for (String key : List.of("endpointId","deliveryId","attemptId","taskId","accountId","secretId"))
            if (result.get(key) instanceof Number value) result.put(key, Long.toString(value.longValue()));
        return result;
    }
    private static String scope(String raw)
    { return java.util.HexFormat.of().formatHex(digest("webhook:" + raw)); }
    private static byte[] digest(String raw)
    { try { return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)); }
      catch (Exception error) { throw new IllegalStateException(error); } }
    private static ServiceException bad(String message) { return conflict(message, 400); }
    private static ServiceException conflict(String message, int status) { return new ServiceException(message, status); }
    private record Dispatch(Map<String,Object> delivery, Map<String,Object> endpoint, String owner, String secret) { }
    private record DateRange(LocalDate from, LocalDate to) { }
}
