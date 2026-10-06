package com.ruoyi.system.voice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import com.ruoyi.system.voice.mapper.OfficialVoiceMapper;
import com.ruoyi.system.voice.mapper.OfficialVoiceMapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.core.exception.ServiceException;

/** Administrator-managed official voice candidates. */
@Service
public class VoiceServiceImpl implements IOfficialVoiceService
{
    private final VoiceBindingService bindings;
    private final OfficialVoiceMapper mapper;
    private final ObjectMapper objectMapper;
    private final OfficialVoiceAuditionClient auditions;
    private final com.ruoyi.system.operations.OperationsService operations;

    public VoiceServiceImpl(OfficialVoiceMapper mapper, ObjectMapper objectMapper, OfficialVoiceAuditionClient auditions,
        com.ruoyi.system.operations.OperationsService operations, VoiceBindingService bindings)
    { this.bindings=bindings; this.mapper = mapper; this.objectMapper = objectMapper; this.auditions = auditions; this.operations = operations; }

    public List<OfficialServiceResponse> listAvailableOfficialServices()
    {
        requireAdmin();
        return mapper.services().stream().map(v -> new OfficialServiceResponse(text(v.id()),v.name(),v.providerCode(),v.modelId(),text(v.revision()),availableAliases(v.providerCode(),v.modelId()),bindings.catalog().require(v.providerCode(),v.modelId()))).toList();
    }

    public VoicePage listOfficial(Integer requestedPage,Integer requestedSize,String status,boolean publishedOnly)
    {
        if(!publishedOnly) requireAdmin();
        int page=requestedPage==null?1:requestedPage,size=requestedSize==null?20:requestedSize;
        if(page<1 || size<1 || size>100 || (long)(page-1)*size>Integer.MAX_VALUE) throw badRequest("分页参数无效");
        String filter=blankToNull(status);
        if(filter!=null && !Set.of("DRAFT","PUBLISHED","UNLISTED","DISABLED").contains(filter)) throw badRequest("状态筛选无效");
        List<VoiceSummary> items=mapper.page(publishedOnly,filter,size,(page-1)*size).stream().map(v->new VoiceSummary(text(v.id()),v.name(),v.description(),v.status(),nullableText(v.currentVersionId()),text(v.revision()),v.createdAt().toString(),v.number(),v.alias(),v.language(),v.model())).toList();
        return new VoicePage(items,mapper.count(publishedOnly,filter),page,size);
    }

    public VoiceDetail getOfficial(long voiceId)
    {
        requireAdmin(); Head head=officialHead(voiceId,false);
        List<VoiceVersion> versions=mapper.versions(voiceId).stream().map(v->version(v.id(),v.number(),v.serviceId(),v.alias(),v.language(),v.model(),v.parameters(),v.snapshot(),v.createdAt().toString())).toList();
        return new VoiceDetail(text(head.id()),head.name(),head.description(),head.status(),nullableText(head.currentVersionId()),text(head.revision()),head.updatedAt().toString(),versions);
    }

    @Transactional
    public VoiceMutation createOfficialCandidate(long administratorId, OfficialVoiceInput input, String key)
    {
        requireAdmin(); validateOfficialInput(input); requireIdempotency(key);
        byte[] hash = digest("create|" + normalized(input));
        Long previous = existingIdempotentResource(administratorId, scope("official-voice:create"), key, hash);
        if (previous != null) return mutationFor(previous);
        ServiceRow service = officialService(input.officialServiceId(), true);
        verifyExpectedServiceRevision(input.expectedServiceRevision(), service.revision());
        long voiceId = nextId(), versionId = nextId(); Instant now = Instant.now();
        mapper.insertVoice(voiceId,administratorId,input.name().trim(),blankToNull(input.description()));

        insertOfficialVersion(administratorId, voiceId, versionId, 1, input, service, now);
        recordIdempotency(administratorId, scope("official-voice:create"), key, hash, voiceId);
        return new VoiceMutation(text(voiceId), text(versionId), "DRAFT", "1");
    }

    @Transactional
    public VoiceMutation createOfficialVersion(long administratorId, long voiceId, OfficialVoiceInput input, String ifMatch, String key)
    {
        requireAdmin(); validateOfficialInput(input); requireIdempotency(key);
        Head voice = officialHeadForUpdate(voiceId, administratorId); verifyIfMatch(ifMatch, voice.revision());
        byte[] hash = digest("version|" + voiceId + "|" + normalized(input));
        Long previous = existingIdempotentResource(administratorId, scope("official-voice:version:" + voiceId), key, hash);
        if (previous != null) return mutationFor(voiceId, previous);
        ServiceRow service = officialService(input.officialServiceId(), true);
        verifyExpectedServiceRevision(input.expectedServiceRevision(), service.revision());
        int next = mapper.nextVersion(voiceId);
        long versionId = nextId();
        insertOfficialVersion(administratorId, voiceId, versionId, next, input, service, Instant.now());
        recordIdempotency(administratorId, scope("official-voice:version:" + voiceId), key, hash, versionId);
        return new VoiceMutation(text(voiceId), text(versionId), voice.status(), text(voice.revision()));
    }

    @Transactional
    public VoiceMutation publishOfficial(long administratorId, long voiceId, long versionId, PublishRequest request, String ifMatch, String key)
    {
        requireAdmin(); bindings.available(versionId,false);
        if (request == null || !request.auditionConfirmed()) throw badRequest("发布前必须确认已完成试听");
        requireIdempotency(key);
        int completedAuditions = mapper.completedAuditions(administratorId,scope("official-voice:audition:"+voiceId+":"+versionId),versionId);

        if (completedAuditions == 0) throw conflict("发布前必须完成该版本官方试听");
        Head voice = officialHeadForUpdate(voiceId, administratorId); verifyIfMatch(ifMatch, voice.revision());
        byte[] hash = digest("publish|" + voiceId + "|" + versionId + "|true|" + voice.revision());
        Long previous = existingIdempotentResource(administratorId, scope("official-voice:publish:" + voiceId), key, hash);
        if (previous != null) return mutationFor(voiceId, previous);
        VersionRow version = mapper.version(voiceId,versionId);

        if (version == null) throw forbidden("官方声音版本不存在");
        ServiceRow service = officialService(version.serviceId(), true);
        // The version owns its frozen semantics; credential rotation may advance the service revision.
        if(mapper.publish(voiceId,versionId,voice.revision())!=1) throw conflict("VOICE_REQUEST_CONFLICT");

        recordIdempotency(administratorId, scope("official-voice:publish:" + voiceId), key, hash, versionId);
        return new VoiceMutation(text(voiceId), text(versionId), "PUBLISHED", text(voice.revision() + 1));
    }

    public byte[] audition(long administratorId, long voiceId, long versionId, String text, String key)
    {
        requireIdempotency(key);
        if (!com.ruoyi.common.security.utils.SecurityUtils.isAdmin()) throw forbidden("仅管理员可试听官方声音");
        if (text == null || text.isBlank() || text.codePointCount(0,text.length()) > 200) throw badRequest("试听文本限 1～200 字符");
        Head head = officialHead(voiceId, false);
        if (Set.of("DISABLED", "DELETING", "DELETED").contains(head.status())) throw conflict("官方声音不可用");
        VersionRow version=mapper.version(voiceId,versionId);

        if (version == null) throw forbidden("官方声音版本不存在");
        long serviceId = version.serviceId();
        Long revision = snapshotRevision(version.snapshot());
        ServiceRow service = officialService(serviceId, false);
        if (revision == null) throw conflict("VOICE_BINDING_UNAVAILABLE");
        String auditionScope = scope("official-voice:audition:" + voiceId + ":" + versionId);
        byte[] hash = digest(text + "|" + revision);
        int claimed=mapper.claimAudition(administratorId,auditionScope,key,hash,versionId);

        if (claimed != 1) existingIdempotentResource(administratorId,auditionScope,key,hash);
        String operation = "audition-" + hex(digest(administratorId+"|"+voiceId+"|"+versionId+"|"+key)).substring(0,48);
        int characters=text.codePointCount(0,text.length());
        auditionFact(administratorId, operation, "STARTED", characters, null);
        try
        {
            byte[] audio = auditions.audition(versionId, administratorId, key, text);
            mapper.finishAudition(administratorId,auditionScope,key,"SUCCEEDED");

            auditionFact(administratorId, operation, "SUCCEEDED", characters, null);
            return audio;
        }
        catch (Exception error)
        {
            mapper.finishAudition(administratorId,auditionScope,key,"FAILED");

            auditionFact(administratorId, operation, "UNKNOWN", characters, "AUDITION_UNAVAILABLE");
            throw error;
        }
    }

    private void auditionFact(long accountId, String operation, String status, int characters, String code)
    {
        operations.accept(new com.ruoyi.system.operations.CallFactEvent(hex(digest(operation + "-" + status)), operation,
            accountId, "TTS", status, null, null, null, null,
            new com.ruoyi.system.operations.CallFactEvent.Usage((long) characters, null, null, false),
            null, null, "UNKNOWN", code));
    }

    private void insertOfficialVersion(long accountId, long voiceId, long versionId, int number, OfficialVoiceInput input, ServiceRow service, Instant now)
    {
        mapper.insertVersion(versionId,voiceId,accountId,number,service.id(),input.voiceAlias()==null?"":input.voiceAlias().trim(),blankToNull(input.language()),service.modelId(),parameters(input.parameters()),snapshot(service));

        bindings.save(accountId,versionId,input,service.providerCode(),service.modelId(),service.revision(),service.endpoint(),jsonObject(service.parameters()));
    }

    private Head officialHead(long voiceId,boolean locked)
    { Head value=mapper.head(voiceId,null,locked);if(value==null) throw forbidden("官方声音不存在");return value; }
    private Head officialHeadForUpdate(long voiceId,long accountId)
    { Head value=mapper.head(voiceId,accountId,true);if(value==null) throw forbidden("官方声音不存在或无权操作");return value; }

    private VoiceMutation mutationFor(long voiceId) { return mutationFor(voiceId, latestVersionId(voiceId)); }
    private VoiceMutation mutationFor(long voiceId, long versionId)
    { Head voice = officialHead(voiceId, false); return new VoiceMutation(text(voiceId), text(versionId), voice.status(), text(voice.revision())); }
    private long latestVersionId(long voiceId)
    { Long id=mapper.latest(voiceId);if(id==null) throw new IllegalStateException("官方声音缺少版本");return id; }
    private ServiceRow officialService(long id,boolean locked)
    {
        ServiceRow service=mapper.service(id,locked);if(service==null) throw conflict("官方 TTS 服务不可用");
        try { bindings.catalog().require(service.providerCode(),service.modelId()); } catch(IllegalArgumentException error) { throw conflict(error.getMessage()); }
        return service;
    }

    private void validateOfficialInput(OfficialVoiceInput input)
    {
        if (input == null || blank(input.name()) || input.name().trim().length() > 100 || input.officialServiceId() == null || input.officialServiceId() <= 0
            || blank(input.expectedServiceRevision()) || input.referenceAssetId()==null && blank(input.voiceAlias()) || input.voiceAlias()!=null && input.voiceAlias().trim().length() > 128 || input.parameters() == null
            || input.description() != null && input.description().length() > 1000) throw badRequest("官方声音参数无效");
        if(input.modelAlias()!=null && !input.modelAlias().isBlank()) throw badRequest("VOICE_PARAMETER_INVALID");
        if (input.parameters().values().stream().anyMatch(value -> !(value instanceof Number))) throw badRequest("VOICE_PARAMETER_INVALID");
    }


    private List<String> availableAliases(String provider, String model)
    { return bindings.catalog().require(provider,model).voices().stream().map(com.ruoyi.common.voice.VoiceCapability.Voice::id).toList(); }
    private static void requireAdmin() { if (!com.ruoyi.common.security.utils.SecurityUtils.isAdmin()) throw forbidden("仅管理员可配置官方声音"); }
    private void verifyExpectedServiceRevision(String expected, long actual)
    { if (!Long.toString(actual).equals(expected.trim())) throw conflict("官方 TTS 服务配置已变化，请刷新后重试"); }
    private static void verifyIfMatch(String value, long actual)
    {
        if (value == null || value.isBlank()) throw new ServiceException("缺少 If-Match", HttpStatus.PRECONDITION_REQUIRED.value());
        if (!Long.toString(actual).equals(value.trim())) throw new ServiceException("声音配置已变化，请刷新后重试", HttpStatus.PRECONDITION_FAILED.value());
    }
    private void requireIdempotency(String value)
    { if (value == null || !value.matches("[A-Za-z0-9._:-]{1,64}")) throw badRequest("Idempotency-Key 无效"); }
    private Long existingIdempotentResource(long accountId, String scope, String key, byte[] hash)
    {
        Idempotency item=mapper.idempotency(accountId,scope,key);

        if (item == null) return null;
        if (!Arrays.equals(item.hash(), hash)) throw conflict("同一 Idempotency-Key 的参数不同"); return item.resourceId();
    }
    private void recordIdempotency(long accountId, String scope, String key, byte[] hash, long resourceId)
    {
        mapper.complete(nextId(),accountId,scope,key,hash,resourceId);
    }

    private VoiceVersion version(long id, int no, long serviceId, String alias, String language, String model, String parameters, String snapshot, String createdAt)
    { var b=bindings.read(id);return new VoiceVersion(text(id), no, text(serviceId), b.providerVoiceRef(), b.language(), model, new LinkedHashMap<>(b.parameters()), jsonObject(snapshot), createdAt,b.providerType(),b.modelRevision(),b.capabilityVersion(),b.referenceAssetId(),b.referenceText(),b.fallback()==null?null:b.fallback().voiceVersionId(),b.allowVoiceChange()); }
    private Long snapshotRevision(String snapshot)
    {
        try { Object value = objectMapper.readValue(snapshot, new TypeReference<Map<String, Object>>() {}).get("revision"); return value instanceof Number number ? number.longValue() : value == null ? null : Long.valueOf(value.toString()); }
        catch (Exception e) { return null; }
    }
    private Map<String, Object> jsonObject(String json)
    {
        try { return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {}); }
        catch (Exception e) { throw new IllegalStateException("声音配置快照损坏", e); }
    }
    private String snapshot(ServiceRow service)
    { return json(Map.of("serviceId", text(service.id()), "name", service.name(), "providerCode", service.providerCode(), "modelId", service.modelId(), "revision", text(service.revision()))); }
    private String parameters(Map<String, Object> values) { return json(new LinkedHashMap<>(values)); }
    private String json(Map<String, ?> value)
    {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw badRequest("声音配置无法序列化"); }
    }
    private String normalized(OfficialVoiceInput input)
    { return json(new java.util.TreeMap<>(Map.ofEntries(
        Map.entry("name",input.name().trim()),Map.entry("description",input.description()==null?"":input.description().trim()),
        Map.entry("service",input.officialServiceId()),Map.entry("revision",input.expectedServiceRevision().trim()),
        Map.entry("voice",input.voiceAlias()==null?"":input.voiceAlias().trim()),Map.entry("language",input.language()==null?"":input.language()),
        Map.entry("parameters",new java.util.TreeMap<>(input.parameters())),
        Map.entry("fallback",input.fallbackVoiceVersionId()==null?"":input.fallbackVoiceVersionId()),
        Map.entry("allowVoiceChange",input.allowVoiceChange()),Map.entry("reference",input.referenceAssetId()==null?"":input.referenceAssetId()),
        Map.entry("referenceText",input.referenceText()==null?"":input.referenceText())))); }
    private static String scope(String operation) { return hex(digest(operation)); }
    private static byte[] digest(String value)
    {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException("无法生成请求摘要", e); }
    }
    private long nextId()
    { long value=mapper.nextId();if(value<=0) throw new IllegalStateException("无法生成 Voice 标识");return value; }

    private static String nullableText(Long value) { return value == null ? null : text(value); }
    private static String text(long value) { return Long.toString(value); }
    private static String hex(byte[] bytes) { StringBuilder value = new StringBuilder(bytes.length * 2); for (byte b : bytes) value.append(String.format("%02x", b)); return value.toString(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String blankToNull(String value) { return blank(value) ? null : value.trim(); }
    private static ServiceException badRequest(String message) { return new ServiceException(message, HttpStatus.BAD_REQUEST.value()); }
    private static ServiceException forbidden(String message) { return new ServiceException(message, HttpStatus.FORBIDDEN.value()); }
    private static ServiceException conflict(String message) { return new ServiceException(message, HttpStatus.CONFLICT.value()); }

    public record OfficialVoiceInput(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=100) String name, @jakarta.validation.constraints.Size(max=1000) String description, @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Positive Long officialServiceId, @jakarta.validation.constraints.NotBlank String expectedServiceRevision, @jakarta.validation.constraints.Size(max=128) String voiceAlias, String language, String modelAlias, @jakarta.validation.constraints.NotNull Map<String, Object> parameters, @jakarta.validation.constraints.Positive Long fallbackVoiceVersionId, boolean allowVoiceChange, @jakarta.validation.constraints.Positive Long referenceAssetId, @jakarta.validation.constraints.Size(max=2000) String referenceText) { }
    public record PublishRequest(boolean auditionConfirmed) { }
    public record OfficialServiceResponse(String serviceId, String name, String providerCode, String modelId, String revision, List<String> availableVoiceAliases, com.ruoyi.common.voice.VoiceCapability capability) { }
    public record VoiceMutation(String voiceId, String versionId, String status, String revision) { }
    public record VoiceSummary(String voiceId, String name, String description, String status, String currentVersionId, String revision, String createdAt, Integer currentVersionNo, String voiceAlias, String language, String modelId) { }
    public record VoiceVersion(String versionId, int versionNo, String officialServiceId, String voiceAlias, String language, String modelId, Map<String, Object> parameters, Map<String, Object> serviceSnapshot, String createdAt,String providerType,String modelRevision,String capabilityVersion,String referenceAssetId,String referenceText,String fallbackVoiceVersionId,boolean allowVoiceChange) { }
    public record VoiceDetail(String voiceId, String name, String description, String status, String currentVersionId, String revision, String updatedAt, List<VoiceVersion> versions) { }
    public record VoicePage(List<VoiceSummary> items, int total, int pageNum, int pageSize) { }
}
