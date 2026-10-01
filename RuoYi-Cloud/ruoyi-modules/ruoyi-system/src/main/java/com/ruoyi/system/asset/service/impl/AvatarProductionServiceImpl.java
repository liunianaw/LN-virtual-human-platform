package com.ruoyi.system.asset.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.asset.mapper.AvatarProductionMapper;
import com.ruoyi.system.asset.mapper.AssetMapper;
import com.ruoyi.system.asset.domain.AssetFile;
import com.ruoyi.system.asset.domain.GenerationServiceConfig;
import com.ruoyi.system.asset.dto.AvatarActionGenerationRequest;
import com.ruoyi.system.asset.dto.AvatarActionSelectionRequest;
import com.ruoyi.system.asset.dto.AvatarAssemblyRequest;
import com.ruoyi.system.asset.dto.AvatarAttemptDiscardRequest;
import com.ruoyi.system.asset.dto.AvatarAttemptRequest;
import com.ruoyi.system.asset.dto.AvatarSelectedResult;
import com.ruoyi.system.asset.dto.CreateAvatarVersionRequest;
import com.ruoyi.system.asset.service.IAvatarProductionService;
import com.ruoyi.system.asset.service.IAssetStorageQuotaService;
import com.ruoyi.system.asset.service.IGenerationQuotaService;
import com.ruoyi.system.storage.ObjectStorage;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AvatarProductionServiceImpl implements IAvatarProductionService
{
    private static final List<String> ACTIONS = List.of("idle","speaking","listening","thinking","nod","shake_head","wave","happy");
    private static final String PIPELINE_VERSION = "m2-asset-v1";
    private final AvatarProductionMapper mapper;
    private final AssetMapper assetMapper;
    private final ObjectMapper json;
    private final ObjectProvider<ObjectStorage> storage;
    private final IGenerationQuotaService quota;
    private final IAssetStorageQuotaService storageQuota;

    public AvatarProductionServiceImpl(AvatarProductionMapper mapper, AssetMapper assetMapper, ObjectMapper json,
        ObjectProvider<ObjectStorage> storage, IGenerationQuotaService quota, IAssetStorageQuotaService storageQuota)
    { this.mapper = mapper; this.assetMapper = assetMapper; this.json = json; this.storage = storage; this.quota = quota; this.storageQuota = storageQuota; }

    public Map<String, Object> production(Long accountId, Long avatarId, Long versionId)
    {
        Map<String, Object> response = requireOwned(accountId, avatarId, versionId);
        Map<String, Map<String, Object>> cards = new LinkedHashMap<>();
        for (String action : ACTIONS)
        {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("actionCode", action); card.put("actionRevision", 0); card.put("stage", "QUEUED");
            for (String field : List.of("latestAttemptId","selectedResultId","acceptedResultId","errorCode","safeMessage","nextRetryAt","stageStartedAt")) card.put(field, null);
            card.put("resultIds", new ArrayList<String>()); card.put("allowedOperations", new ArrayList<String>());
            cards.put(action, card);
        }
        for (Map<String, Object> row : mapper.selections(accountId, versionId)) cards.get(row.get("actionCode")).putAll(row);
        for (Map<String, Object> row : mapper.steps(accountId, versionId)) cards.get(row.get("actionCode")).putAll(row);
        for (Map<String, Object> row : mapper.results(accountId, versionId))
        {
            Map<String, Object> card = cards.get(row.get("actionCode"));
            @SuppressWarnings("unchecked") List<String> ids = (List<String>) card.get("resultIds");
            ids.add((String) row.get("resultId"));
        }
        int completed = 0, accepted = 0;
        for (Map<String, Object> card : cards.values())
        {
            List<String> operations = new ArrayList<>();
            boolean hasResults = !((List<?>) card.get("resultIds")).isEmpty();
            if (hasResults) { completed++; operations.add("preview"); operations.add("select"); }
            if (card.get("acceptedResultId") != null) accepted++;
            if (hasResults && "QUEUED".equals(card.get("stage")) && card.get("latestAttemptId") == null)
                card.put("stage", "READY_FOR_REVIEW");
            if ("SUCCEEDED".equals(card.get("stage")))
            {
                if (hasResults) card.put("stage", "READY_FOR_REVIEW");
                else
                {
                    card.put("stage", "FAILED");
                    card.put("errorCode", "RESULT_RECORD_MISSING");
                    card.put("safeMessage", "动作已加工但结果记录缺失，请恢复或重做该动作");
                }
            }
            String stage = String.valueOf(card.get("stage"));
            if (!List.of("READY","RUNNING","POLLING").contains(stage)) operations.add("regenerate");
            if (("UNKNOWN".equals(stage) || "FAILED".equals(stage)) && asBoolean(card.get("recoverable")))
                operations.add("recovery");
            if (card.get("latestAttemptId") != null && card.get("acceptedResultId") != null && !asBoolean(card.get("selectionClosed")))
                operations.add("discard");
            card.remove("recoverable");
            card.put("allowedOperations", operations);
        }
        response.put("actions", new ArrayList<>(cards.values())); response.put("totalActionCount", 8);
        response.put("completedActionCount", completed); response.put("acceptedActionCount", accepted);
        // Enabled by the assembly command only after the full snapshot is validated.
        boolean canAssemble = accepted == 8 && mapper.countAssemblyBlockers(accountId, versionId) == 0;
        response.put("canAssemble", canAssemble);
        response.put("assemblyStage", "REVIEW".equals(response.get("versionStatus")) ? "READY" : "BUILDING");
        return response;
    }

    public Map<String, Object> preview(Long accountId, Long avatarId, Long versionId, String actionCode, Long resultId)
    {
        requireOwned(accountId, avatarId, versionId);
        if (!ACTIONS.contains(actionCode)) throw new ServiceException("无效动作", 400);
        Map<String, Object> result = mapper.resultPreview(accountId, versionId, actionCode, resultId);
        if (result == null) throw new ServiceException("无权访问此动作结果", 403);
        ObjectStorage objects = storage.getIfAvailable();
        if (objects == null) throw new ServiceException("云存储未配置");
        result.put("atlasUrl", objects.readUrl((String) result.remove("objectKey")));
        result.put("expiresAt", objects.readUrlExpiresAt().toString());
        try {
            result.put("frameLayout", json.readTree((String) result.get("frameLayout")));
            result.put("qaReport", json.readTree((String) result.get("qaReport")));
        } catch (Exception error) { throw new ServiceException("动作结果格式无效"); }
        return result;
    }

    @Transactional
    public Object select(Long accountId, Long avatarId, Long versionId, String actionCode, AvatarActionSelectionRequest request)
    {
        requireOwned(accountId, avatarId, versionId);
        if (!ACTIONS.contains(actionCode) || request == null || !Boolean.TRUE.equals(request.visualAccepted())
            || request.requestId() == null || !request.requestId().matches("[A-Za-z0-9._:-]{1,64}")
            || request.expectedActionRevision() == null || request.expectedActionRevision() < 0 || request.resultId() == null)
            throw new ServiceException("动作确认参数无效", 400);
        Map<String, Object> version = mapper.lockVersion(accountId, versionId);
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(
                (request.resultId() + ":" + request.expectedActionRevision() + ":true").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Map<String, Object> previous = mapper.operation(accountId, versionId, actionCode, request.requestId());
            if (previous != null) {
                if (!java.util.Arrays.equals(hash, (byte[]) previous.get("requestHash"))) throw new ServiceException("同一请求编号的参数不同", 409);
                return json.readTree((String) previous.get("responseJson"));
            }
            if (version == null || !List.of("BUILDING","REVIEW").contains(version.get("status")))
                throw new ServiceException("已发布或不可编辑版本不能更改动作", 409);
            Map<String, Object> result = mapper.resultPreview(accountId, versionId, actionCode, request.resultId());
            if (result == null) throw new ServiceException("动作结果不属于此版本", 403);
            if (!json.readTree((String) result.get("qaReport")).path("geometryOk").asBoolean(false))
                throw new ServiceException("此动作几何检查未通过，不能采用", 409);
            mapper.initializeSelection(accountId, versionId, actionCode);
            if (mapper.saveSelection(accountId, versionId, actionCode, request.resultId(), request.expectedActionRevision()) != 1)
                throw new ServiceException("动作已变化，请刷新后确认", 409);
            if (mapper.invalidateAssembly(accountId, versionId) != 1) throw new ServiceException("版本已变化", 409);
            Map<String, Object> response = Map.of("selectedResultId", request.resultId().toString(),
                "acceptedResultId", request.resultId().toString(), "actionRevision", request.expectedActionRevision() + 1);
            mapper.rememberSelection(accountId, versionId, actionCode, request.requestId(), hash, json.writeValueAsString(response));
            return response;
        } catch (ServiceException error) { throw error; }
        catch (Exception error) { throw new ServiceException("无法保存动作确认"); }
    }

    @Transactional
    public Object generate(Long accountId, Long avatarId, Long versionId, String actionCode, AvatarActionGenerationRequest request)
    {
        requireOwned(accountId, avatarId, versionId);
        if (!ACTIONS.contains(actionCode) || request == null || request.requestId() == null
            || !request.requestId().matches("[A-Za-z0-9._:-]{1,64}") || request.expectedActionRevision() == null
            || request.expectedActionRevision() < 0)
            throw new ServiceException("动作生成参数无效", 400);
        try {
            String material = json.writeValueAsString(Map.of(
                "expectedActionRevision", request.expectedActionRevision(),
                "acknowledgeUncertainCharge", Boolean.TRUE.equals(request.acknowledgeUncertainCharge()),
                "supersedesAttemptId", request.supersedesAttemptId() == null ? "" : request.supersedesAttemptId().toString()));
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Map<String, Object> previous = mapper.generationOperation(accountId, versionId, actionCode, request.requestId());
            if (previous != null) {
                if (!java.util.Arrays.equals(hash, (byte[]) previous.get("requestHash"))) throw new ServiceException("同一请求编号的参数不同", 409);
                return json.readTree((String) previous.get("responseJson"));
            }
            Map<String, Object> context = mapper.generationContext(accountId, versionId, actionCode);
            if (context == null) throw new ServiceException("制作服务已停用或候选版本不可恢复", 409);
            if (!avatarId.toString().equals(context.get("avatarId")) || !List.of("BUILDING","REVIEW").contains(context.get("versionStatus")))
                throw new ServiceException("已发布或不可编辑版本不能重做动作", 409);
            if (!"AVAILABLE".equals(context.get("sourceStatus")))
                throw new ServiceException("参考图已删除或不可用，请使用新参考图创建完整候选版本", 409);
            if (((Number) context.get("actionRevision")).longValue() != request.expectedActionRevision())
                throw new ServiceException("动作已变化，请刷新后重试", 409);
            String latestStage = (String) context.get("latestStage");
            if (latestStage != null && List.of("READY","RUNNING","POLLING").contains(latestStage))
                throw new ServiceException("此动作已有进行中的制作", 409);
            if ("UNKNOWN".equals(latestStage)) {
                String latestAttemptId = (String) context.get("latestAttemptId");
                if (!Boolean.TRUE.equals(request.acknowledgeUncertainCharge()) || request.supersedesAttemptId() == null
                    || !request.supersedesAttemptId().toString().equals(latestAttemptId))
                    throw new ServiceException("上次结果待核对；再次生成前必须确认可能重复计费", 409);
            }
            Long taskId = nextId(), stepId = nextId(), attemptId = nextId(), reservationId = nextId();
            quota.reserve(accountId, taskId, reservationId);
            mapper.insertActionTask(taskId, accountId, avatarId, versionId, Long.valueOf((String) context.get("sourceFileId")),
                Long.valueOf((String) context.get("officialServiceId")), (String) context.get("serviceSnapshot"),
                (String) context.get("pipelineVersion"), reservationId, request.requestId());
            assetMapper.insertGenerationActionStep(stepId, accountId, taskId, "ACTION_" + actionCode, actionCode, attemptId);
            mapper.initializeSelection(accountId, versionId, actionCode);
            if (mapper.startGeneration(accountId, versionId, actionCode, request.expectedActionRevision(), attemptId) != 1
                || mapper.invalidateAssembly(accountId, versionId) != 1) throw new ServiceException("动作已变化，请刷新后重试", 409);
            String eventId = java.util.UUID.randomUUID().toString().replace("-", "");
            assetMapper.insertOutbox(nextId(), accountId, eventId, "AVATAR_GENERATION_REQUESTED", "GENERATION_TASK",
                taskId.toString(), java.util.UUID.randomUUID().toString().replace("-", ""), json.writeValueAsString(Map.of(
                    "taskId", taskId, "avatarId", avatarId, "avatarVersionId", versionId, "actionCode", actionCode, "status", "QUEUED")));
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("taskId", taskId.toString()); response.put("stepId", stepId.toString());
            response.put("attemptId", attemptId.toString()); response.put("actionCode", actionCode);
            response.put("actionRevision", request.expectedActionRevision() + 1); response.put("stage", "QUEUED");
            mapper.rememberGeneration(accountId, versionId, actionCode, request.requestId(), hash, json.writeValueAsString(response));
            return response;
        } catch (ServiceException error) { throw error; }
        catch (Exception error) { throw new ServiceException("无法创建动作制作任务"); }
    }

    private Long nextId()
    {
        Long id = assetMapper.nextId();
        if (id == null || id <= 0) throw new ServiceException("无法生成业务标识");
        return id;
    }

    @Transactional
    public Object recover(Long accountId, Long avatarId, Long versionId, String actionCode, Long attemptId,
        AvatarAttemptRequest request)
    {
        requireOwned(accountId, avatarId, versionId);
        validateAttemptRequest(actionCode, attemptId, request);
        try {
            byte[] hash = operationHash(Map.of("attemptId", attemptId.toString(),
                "expectedActionRevision", request.expectedActionRevision()));
            Map<String, Object> previous = mapper.attemptOperation(accountId, versionId, actionCode, "RECOVERY", request.requestId());
            if (previous != null) return previousOperation(previous, hash, "同一请求编号的恢复参数不同");
            Map<String, Object> context = mapper.attemptContext(accountId, versionId, actionCode, attemptId);
            if (context == null || !attemptId.toString().equals(context.get("latestAttemptId")))
                throw new ServiceException("该尝试不属于当前动作或已不是最新尝试", 409);
            if (((Number) context.get("actionRevision")).longValue() != request.expectedActionRevision())
                throw new ServiceException("动作已变化，请刷新后恢复", 409);
            if (!List.of("UNKNOWN", "FAILED").contains(context.get("attemptStatus"))
                || !List.of("UNKNOWN", "FAILED").contains(context.get("stepStatus")))
                throw new ServiceException("该尝试当前不需要恢复", 409);
            if (context.get("providerTaskId") == null && context.get("receiptTaskId") == null && context.get("imageUrl") == null)
                throw new ServiceException("没有可安全恢复的厂商任务或结果证据", 409);
            quota.resume(accountId, Long.valueOf((String) context.get("taskId")));
            if (mapper.scheduleAttemptRecovery(accountId, versionId, actionCode, attemptId, request.expectedActionRevision()) != 1
                || mapper.reactivateAttempt(accountId, versionId, actionCode, attemptId) != 1
                || mapper.reactivateTask(accountId, versionId, actionCode, attemptId) != 1
                || mapper.invalidateAssembly(accountId, versionId) != 1)
                throw new ServiceException("恢复状态已变化，请刷新后重试", 409);
            assetMapper.insertOutbox(nextId(), accountId, java.util.UUID.randomUUID().toString().replace("-", ""),
                "AVATAR_GENERATION_REQUESTED", "GENERATION_TASK", (String) context.get("taskId"),
                java.util.UUID.randomUUID().toString().replace("-", ""), json.writeValueAsString(Map.of(
                    "taskId", context.get("taskId"), "avatarId", avatarId.toString(), "avatarVersionId", versionId.toString(),
                    "actionCode", actionCode, "recoveryOnly", true, "attemptId", attemptId.toString())));
            Map<String, Object> response = Map.of("attemptId", attemptId.toString(), "stage", "POLLING",
                "recoveryScheduled", true, "actionRevision", request.expectedActionRevision() + 1);
            mapper.rememberAttemptOperation(accountId, versionId, actionCode, "RECOVERY", request.requestId(), hash,
                json.writeValueAsString(response));
            return response;
        } catch (ServiceException error) { throw error; }
        catch (Exception error) { throw new ServiceException("无法安排动作结果恢复"); }
    }

    @Transactional
    public Object discard(Long accountId, Long avatarId, Long versionId, String actionCode, Long attemptId,
        AvatarAttemptDiscardRequest request)
    {
        requireOwned(accountId, avatarId, versionId);
        if (request == null || request.retainResultId() == null)
            throw new ServiceException("放弃本轮参数无效", 400);
        validateAttemptRequest(actionCode, attemptId,
            new AvatarAttemptRequest(request.requestId(), request.expectedActionRevision()));
        try {
            byte[] hash = operationHash(Map.of("attemptId", attemptId.toString(),
                "expectedActionRevision", request.expectedActionRevision(), "retainResultId", request.retainResultId().toString()));
            Map<String, Object> previous = mapper.attemptOperation(accountId, versionId, actionCode, "DISCARD", request.requestId());
            if (previous != null) return previousOperation(previous, hash, "同一请求编号的放弃参数不同");
            Map<String, Object> context = mapper.attemptContext(accountId, versionId, actionCode, attemptId);
            if (context == null || !attemptId.toString().equals(context.get("latestAttemptId")))
                throw new ServiceException("该尝试不属于当前动作或已不是最新尝试", 409);
            if (((Number) context.get("actionRevision")).longValue() != request.expectedActionRevision()
                || !request.retainResultId().toString().equals(context.get("acceptedResultId")))
                throw new ServiceException("保留结果或动作版本已变化，请刷新后重试", 409);
            if (mapper.closeAttemptSelection(accountId, versionId, actionCode, attemptId, request.retainResultId(),
                request.expectedActionRevision()) != 1)
                throw new ServiceException("动作已变化，请刷新后重试", 409);
            if (mapper.stopAttemptRecovery(accountId, versionId, actionCode, attemptId) != 1)
                throw new ServiceException("本轮制作状态已变化，请刷新后重试", 409);
            if (mapper.invalidateAssembly(accountId, versionId) != 1)
                throw new ServiceException("版本已变化，请刷新后重试", 409);
            Map<String, Object> response = Map.of("attemptId", attemptId.toString(), "selectionClosed", true,
                "selectedResultId", request.retainResultId().toString(), "actionRevision", request.expectedActionRevision() + 1);
            mapper.rememberAttemptOperation(accountId, versionId, actionCode, "DISCARD", request.requestId(), hash,
                json.writeValueAsString(response));
            return response;
        } catch (ServiceException error) { throw error; }
        catch (Exception error) { throw new ServiceException("无法放弃本轮动作结果"); }
    }

    @Transactional
    public Object createVersion(Long accountId, Long avatarId, CreateAvatarVersionRequest request)
    {
        if (accountId == null || accountId <= 0 || avatarId == null || avatarId <= 0 || request == null
            || request.requestId() == null || !request.requestId().matches("[A-Za-z0-9._:-]{1,64}")
            || request.expectedAvatarRevision() == null || request.expectedAvatarRevision() <= 0)
            throw new ServiceException("新版本参数无效", 400);
        if (request.baseVersionId() != null && (request.sourceFileId() != null || request.officialServiceId() != null))
            throw new ServiceException("继承旧版本与更换参考图/服务不能同时提交", 400);
        if (request.baseVersionId() == null && (request.sourceFileId() == null || request.officialServiceId() == null))
            throw new ServiceException("完整重新制作必须提供参考图和制作服务", 400);
        try {
            Map<String, Object> material = new LinkedHashMap<>();
            material.put("expectedAvatarRevision", request.expectedAvatarRevision());
            material.put("baseVersionId", request.baseVersionId() == null ? null : request.baseVersionId().toString());
            material.put("sourceFileId", request.sourceFileId() == null ? null : request.sourceFileId().toString());
            material.put("officialServiceId", request.officialServiceId() == null ? null : request.officialServiceId().toString());
            material.put("expectedServiceRevision", request.expectedServiceRevision());
            byte[] hash = operationHash(material);
            Map<String, Object> avatar = mapper.lockOwnedAvatar(accountId, avatarId);
            if (avatar == null) throw new ServiceException("无权为此角色创建版本", 403);
            if (SecurityUtils.isAdmin() != "OFFICIAL".equals(avatar.get("visibility")))
                throw new ServiceException("只有管理员可以制作官方角色的新版本", 403);
            Map<String, Object> previous = mapper.versionOperation(accountId, avatarId, request.requestId());
            if (previous != null) return previousOperation(previous, hash, "同一请求编号的新版本参数不同");
            if (((Number) avatar.get("revision")).longValue() != request.expectedAvatarRevision())
                throw new ServiceException("角色已变化，请刷新后重试", 409);
            Long versionId = nextId();
            int versionNo = ((Number) avatar.get("maxVersionNo")).intValue() + 1;
            String taskId = null;
            if (request.baseVersionId() != null)
            {
                Map<String, Object> base = mapper.baseVersion(accountId, avatarId, request.baseVersionId());
                if (base == null || !"PUBLISHED".equals(base.get("status"))
                    || ((Number) base.get("actionCount")).intValue() != ACTIONS.size()
                    || ((Number) base.get("reusableResultCount")).intValue() != ACTIONS.size()
                    || base.get("manifestFileId") == null)
                    throw new ServiceException("基础版本必须是完整且已发布的同角色版本", 409);
                var inheritedRecipe = json.readTree((String) base.get("generationRecipe"));
                if (!inheritedRecipe.isObject()) throw new ServiceException("基础版本制作配方无效", 409);
                ((com.fasterxml.jackson.databind.node.ObjectNode) inheritedRecipe).put(
                    "inheritedFromVersionId", request.baseVersionId().toString());
                mapper.insertCandidateVersion(versionId, accountId, avatarId, versionNo,
                    Long.valueOf((String) base.get("sourceFileId")), (String) base.get("pipelineVersion"),
                    json.writeValueAsString(inheritedRecipe));
                for (String action : ACTIONS)
                {
                    Long resultId = nextId();
                    if (mapper.copyActionResult(resultId, accountId, request.baseVersionId(), versionId, action) != 1
                        || mapper.initializeCopiedSelection(accountId, versionId, action, resultId) != 1)
                        throw new ServiceException("基础版本动作不完整，无法创建候选", 409);
                }
            }
            else
            {
                AssetFile source = assetMapper.selectAvailableFile(accountId, request.sourceFileId());
                if (source == null) throw new ServiceException("参考图不存在、不可用或不属于当前账号", 403);
                GenerationServiceConfig generationService = assetMapper.selectActiveAvatarGenerationService(request.officialServiceId());
                if (generationService == null) throw new ServiceException("指定的 Avatar 制作服务不可用", 409);
                if (request.expectedServiceRevision() != null
                    && generationService.getRevision() != request.expectedServiceRevision().longValue())
                    throw new ServiceException("制作服务配置已变化，请重新选择", 409);
                String snapshot = serviceSnapshot(generationService);
                String recipe = json.writeValueAsString(Map.of("pipelineVersion", PIPELINE_VERSION,
                    "sourceSha256", java.util.HexFormat.of().formatHex(source.getSha256())));
                mapper.insertCandidateVersion(versionId, accountId, avatarId, versionNo, source.getId(), PIPELINE_VERSION, recipe);
                Long generationTaskId = nextId(), reservationId = nextId();
                quota.reserve(accountId, generationTaskId, reservationId);
                assetMapper.insertGenerationTask(generationTaskId, accountId, avatarId, versionId, source.getId(),
                    generationService.getId(), snapshot, PIPELINE_VERSION, reservationId, request.requestId(), hash);
                for (String action : ACTIONS)
                    assetMapper.insertGenerationActionStep(nextId(), accountId, generationTaskId, "ACTION_" + action, action, nextId());
                assetMapper.insertOutbox(nextId(), accountId, java.util.UUID.randomUUID().toString().replace("-", ""),
                    "AVATAR_GENERATION_REQUESTED", "GENERATION_TASK", generationTaskId.toString(),
                    java.util.UUID.randomUUID().toString().replace("-", ""), json.writeValueAsString(Map.of(
                        "taskId", generationTaskId.toString(), "avatarId", avatarId.toString(),
                        "avatarVersionId", versionId.toString(), "sourceFileId", source.getId().toString(), "status", "QUEUED")));
                taskId = generationTaskId.toString();
            }
            if (mapper.bumpAvatarRevision(accountId, avatarId, request.expectedAvatarRevision()) != 1)
                throw new ServiceException("角色已变化，请刷新后重试", 409);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("avatarId", avatarId.toString()); response.put("versionId", versionId.toString());
            response.put("candidateRevision", 0); response.put("taskId", taskId);
            mapper.rememberVersionOperation(accountId, avatarId, request.requestId(), hash, json.writeValueAsString(response));
            return response;
        } catch (ServiceException error) { throw error; }
        catch (Exception error) { throw new ServiceException("无法创建角色新版本"); }
    }

    @Transactional
    public Object assemble(Long accountId, Long avatarId, Long versionId, AvatarAssemblyRequest request)
    {
        requireOwned(accountId, avatarId, versionId);
        if (request == null || request.requestId() == null || !request.requestId().matches("[A-Za-z0-9._:-]{1,64}")
            || request.expectedCandidateRevision() == null || request.expectedCandidateRevision() < 0
            || request.selectedResults() == null || request.selectedResults().size() != 8)
            throw new ServiceException("组装参数无效", 400);
        try {
            Map<String, Long> submitted = new LinkedHashMap<>();
            for (AvatarSelectedResult item : request.selectedResults())
                if (item == null || !ACTIONS.contains(item.actionCode()) || item.resultId() == null
                    || submitted.putIfAbsent(item.actionCode(), item.resultId()) != null)
                    throw new ServiceException("组装必须恰好包含八个互异动作结果", 400);
            if (!submitted.keySet().equals(new java.util.LinkedHashSet<>(ACTIONS)))
                throw new ServiceException("组装动作集合不完整", 400);
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("expectedCandidateRevision", request.expectedCandidateRevision());
            canonical.put("selectedResults", ACTIONS.stream().map(action -> Map.of("actionCode", action,
                "resultId", submitted.get(action).toString())).toList());
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(
                json.writeValueAsBytes(canonical));
            Map<String, Object> previous = mapper.assemblyOperation(accountId, versionId, request.requestId());
            if (previous != null) {
                if (!java.util.Arrays.equals(hash, (byte[]) previous.get("requestHash")))
                    throw new ServiceException("同一请求编号的组装参数不同", 409);
                return json.readTree((String) previous.get("responseJson"));
            }
            Map<String, Object> version = mapper.lockVersion(accountId, versionId);
            if (version == null || !"BUILDING".equals(version.get("status"))
                || ((Number) version.get("candidateRevision")).longValue() != request.expectedCandidateRevision())
                throw new ServiceException("候选版本已变化，请刷新后组装", 409);
            if (mapper.countAssemblyBlockers(accountId, versionId) != 0)
                throw new ServiceException("仍有运行中、未知或未关闭选用的动作", 409);
            List<Map<String, Object>> selected = mapper.selectedResults(accountId, versionId);
            if (selected.size() != 8) throw new ServiceException("八个动作尚未全部确认", 409);
            for (Map<String, Object> row : selected) {
                Long expected = submitted.get((String) row.get("actionCode"));
                if (expected == null || !expected.toString().equals(row.get("resultId")) || row.get("baseFileId") == null
                    || row.get("baseObjectKey") == null || row.get("baseContentHash") == null)
                    throw new ServiceException("已确认动作与组装快照不一致", 409);
            }
            ObjectStorage objects = storage.getIfAvailable();
            if (objects == null) throw new ServiceException("云存储未配置");
            String expiresAt = objects.readUrlExpiresAt().toString();
            Map<String, Object> idle = selected.get(0);
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("packageType", "LN_AVATAR"); descriptor.put("schemaVersion", 1);
            descriptor.put("framing", "FULL_BODY"); descriptor.put("lipSyncMode", "BASIC_SPEAKING");
            descriptor.put("avatarId", avatarId.toString()); descriptor.put("versionId", versionId.toString());
            descriptor.put("width", 512); descriptor.put("height", 768);
            descriptor.put("anchor", Map.of("x", 256, "y", 768));
            descriptor.put("preview", assetDescriptor(((Number) idle.get("baseFileId")).longValue(),
                (String) idle.get("baseObjectKey"), (String) idle.get("baseContentHash"), objects, expiresAt));
            descriptor.put("baseImage", assetDescriptor(((Number) idle.get("baseFileId")).longValue(),
                (String) idle.get("baseObjectKey"), (String) idle.get("baseContentHash"), objects, expiresAt));
            List<Map<String, Object>> actions = new ArrayList<>();
            for (Map<String, Object> row : selected) {
                Map<String, Object> action = new LinkedHashMap<>();
                action.put("code", row.get("actionCode"));
                action.put("frameCount", row.get("frameCount")); action.put("fps", row.get("fps"));
                action.put("loop", ((Number) row.get("loopEnabled")).intValue() == 1);
                action.put("atlas", assetDescriptor(((Number) row.get("atlasFileId")).longValue(),
                    (String) row.get("atlasObjectKey"), (String) row.get("contentHash"), objects, expiresAt));
                action.put("frames", json.readTree((String) row.get("frameLayout")).get("frames"));
                actions.add(action);
            }
            descriptor.put("actions", actions);
            byte[] manifestBytes = json.writeValueAsBytes(descriptor);
            byte[] manifestHash = java.security.MessageDigest.getInstance("SHA-256").digest(manifestBytes);
            String objectKey = "avatar-packages/" + accountId + "/" + avatarId + "/" + versionId + "/"
                + request.expectedCandidateRevision() + "/manifest.json";
            objects.put(objectKey, manifestBytes, "application/json");
            AssetFile manifestFile = new AssetFile();
            manifestFile.setId(nextId()); manifestFile.setAccountId(accountId); manifestFile.setPurpose("MANIFEST");
            manifestFile.setStorageProvider(objects.provider()); manifestFile.setBucket(objects.bucket());
            manifestFile.setObjectKey(objectKey); manifestFile.setOriginalName("manifest.json");
            manifestFile.setContentType("application/json"); manifestFile.setSizeBytes((long) manifestBytes.length);
            manifestFile.setSha256(manifestHash); manifestFile.setStatus("AVAILABLE");
            manifestFile.setStorageReservationId(storageQuota.reserve(accountId, manifestFile.getId(), manifestBytes.length));
            assetMapper.insertFile(manifestFile);
            storageQuota.complete(accountId, manifestFile.getId());
            mapper.deleteDraftActions(accountId, versionId);
            for (Map<String, Object> row : selected)
                if (mapper.insertFormalAction(nextId(), accountId, versionId, Long.valueOf((String) row.get("resultId"))) != 1)
                    throw new ServiceException("无法写入正式动作");
            if (mapper.finishAssembly(accountId, versionId, request.expectedCandidateRevision(),
                ((Number) idle.get("baseFileId")).longValue(), manifestFile.getId(),
                ((Number) idle.get("baseFileId")).longValue()) != 1)
                throw new ServiceException("候选版本已变化，请重新组装", 409);
            Map<String, Object> response = Map.of("assemblyId", nextId().toString(), "stage", "REVIEW",
                "candidateRevision", request.expectedCandidateRevision());
            mapper.rememberAssembly(accountId, versionId, request.requestId(), hash, json.writeValueAsString(response));
            return response;
        } catch (ServiceException error) { throw error; }
        catch (Exception error) { throw new ServiceException("无法组装角色版本"); }
    }

    private Map<String, Object> requireOwned(Long accountId, Long avatarId, Long versionId)
    {
        if (accountId == null || accountId <= 0) throw new ServiceException("未登录", 401);
        Map<String, Object> version = mapper.ownedVersion(accountId, avatarId, versionId);
        if (version == null) throw new ServiceException("无权管理此角色版本", 403);
        if (SecurityUtils.isAdmin() != "OFFICIAL".equals(version.get("visibility")))
            throw new ServiceException("角色发布范围与当前身份不匹配", 403);
        return new LinkedHashMap<>(version);
    }

    private void validateAttemptRequest(String actionCode, Long attemptId, AvatarAttemptRequest request)
    {
        if (!ACTIONS.contains(actionCode) || attemptId == null || attemptId <= 0 || request == null
            || request.requestId() == null || !request.requestId().matches("[A-Za-z0-9._:-]{1,64}")
            || request.expectedActionRevision() == null || request.expectedActionRevision() < 0)
            throw new ServiceException("动作尝试参数无效", 400);
    }

    private byte[] operationHash(Map<String, ?> material) throws Exception
    {
        return java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(material));
    }

    private Map<String, Object> assetDescriptor(Long fileId, String objectKey, String contentHash,
        ObjectStorage objects, String expiresAt)
    {
        return Map.of("fileId", fileId.toString(), "sha256", contentHash.toLowerCase(java.util.Locale.ROOT),
            "url", objects.readUrl(objectKey), "expiresAt", expiresAt);
    }

    private String serviceSnapshot(GenerationServiceConfig service) throws Exception
    {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("serviceId", service.getId()); snapshot.put("name", service.getName());
        snapshot.put("providerCode", service.getProviderCode()); snapshot.put("endpoint", service.getEndpoint());
        snapshot.put("modelId", service.getModelId()); snapshot.put("parameters", service.getParameters());
        snapshot.put("revision", service.getRevision());
        return json.writeValueAsString(snapshot);
    }

    private Object previousOperation(Map<String, Object> previous, byte[] hash, String conflict) throws Exception
    {
        if (!java.util.Arrays.equals(hash, (byte[]) previous.get("requestHash"))) throw new ServiceException(conflict, 409);
        return json.readTree((String) previous.get("responseJson"));
    }

    private boolean asBoolean(Object value)
    {
        return Boolean.TRUE.equals(value) || (value instanceof Number number && number.intValue() != 0);
    }
}
