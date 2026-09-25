package com.ruoyi.system.developer.openapi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.system.asset.dto.AvatarActionGenerationRequest;
import com.ruoyi.system.asset.dto.AvatarActionSelectionRequest;
import com.ruoyi.system.asset.dto.AvatarAssemblyRequest;
import com.ruoyi.system.asset.dto.AvatarAttemptDiscardRequest;
import com.ruoyi.system.asset.dto.AvatarAttemptRequest;
import com.ruoyi.system.asset.dto.CreateAvatarVersionRequest;
import com.ruoyi.system.asset.dto.CreateGenerationTaskRequest;
import com.ruoyi.system.asset.dto.GenerationTaskResponse;
import com.ruoyi.system.asset.dto.PublishAvatarVersionRequest;
import com.ruoyi.system.asset.service.IAssetService;
import com.ruoyi.system.asset.service.IAvatarProductionService;
import com.ruoyi.system.asset.service.IAvatarPublicationService;
import com.ruoyi.system.developer.access.service.IAccessKeyService;
import com.ruoyi.system.voice.VoiceService;

/** Management Key HTTP boundary; all mutations retain the console domain service and its transaction. */
@RestController
@RequestMapping("/openapi/v1/management")
public class ManagementAssetController
{
    private final IAssetService assets;
    private final IAvatarProductionService production;
    private final IAvatarPublicationService publication;
    private final VoiceService voices;

    public ManagementAssetController(IAssetService assets, IAvatarProductionService production,
        IAvatarPublicationService publication, VoiceService voices)
    { this.assets = assets; this.production = production; this.publication = publication; this.voices = voices; }

    @PostMapping("/avatar-reference-files")
    public AjaxResult upload(HttpServletRequest request, @RequestParam("file") MultipartFile file,
        @RequestParam("rightsNoticeVersion") String notice, @RequestParam("rightsConfirmed") Boolean confirmed)
    { return AjaxResult.success(assets.uploadReference(account(request), file, notice, confirmed)); }

    @GetMapping("/avatar-reference-files/{fileId}")
    public AjaxResult file(HttpServletRequest request, @PathVariable long fileId)
    { return AjaxResult.success(assets.readReference(account(request), fileId)); }

    @GetMapping("/avatar-generation-services")
    public AjaxResult services(HttpServletRequest request)
    { return AjaxResult.success(assets.listAvatarGenerationServices(account(request))); }

    @PostMapping("/avatar-generation-tasks")
    public AjaxResult createTask(HttpServletRequest request, @Valid @RequestBody CreateGenerationTaskRequest input)
    { return AjaxResult.success(task(assets.createGenerationTask(account(request), input))); }

    @GetMapping("/avatar-generation-tasks")
    public AjaxResult tasks(HttpServletRequest request, @RequestParam(defaultValue = "1") int pageNum,
        @RequestParam(defaultValue = "20") int pageSize)
    {
        Map<String, Object> page = assets.pageGenerationTasks(account(request), pageNum, pageSize);
        @SuppressWarnings("unchecked") List<GenerationTaskResponse> items = (List<GenerationTaskResponse>) page.get("items");
        return AjaxResult.success(Map.of("items", items.stream().map(ManagementAssetController::task).toList(),
            "total", page.get("total"), "pageNum", pageNum, "pageSize", pageSize));
    }

    @GetMapping("/avatar-generation-tasks/{taskId}")
    public AjaxResult task(HttpServletRequest request, @PathVariable long taskId)
    { return AjaxResult.success(task(assets.readGenerationTask(account(request), taskId))); }

    @GetMapping("/avatar-generation-tasks/{taskId}/steps")
    public AjaxResult steps(HttpServletRequest request, @PathVariable long taskId)
    { return AjaxResult.success(assets.listGenerationSteps(account(request), taskId)); }

    @GetMapping("/avatars")
    public AjaxResult avatars(HttpServletRequest request, @RequestParam(defaultValue = "1") int pageNum,
        @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) String status,
        @RequestParam(required = false) String keyword)
    { return AjaxResult.success(avatarPage(publication.listOwned(account(request), pageNum, pageSize, status, keyword))); }

    @GetMapping("/avatars/public")
    public AjaxResult publicAvatars(HttpServletRequest request, @RequestParam(defaultValue = "1") int pageNum,
        @RequestParam(defaultValue = "20") int pageSize)
    { account(request); return AjaxResult.success(avatarPage(publication.listPublic(pageNum, pageSize))); }

    @GetMapping("/avatars/{avatarId}")
    public AjaxResult avatar(HttpServletRequest request, @PathVariable long avatarId)
    {
        Map<String, Object> detail = publication.detail(account(request), avatarId);
        Map<String, Object> result = safe(detail, "avatarId", "name", "visibility", "status", "currentVersionId", "revision");
        result.put("versions", rows(detail.get("versions"), "versionId", "versionNo", "status", "createdAt"));
        return AjaxResult.success(result);
    }

    @GetMapping("/avatars/{avatarId}/references")
    public AjaxResult references(HttpServletRequest request, @PathVariable long avatarId)
    {
        Map<String, Object> detail = publication.references(account(request), avatarId);
        return AjaxResult.success(Map.of("counts", safe((Map<?, ?>) detail.get("counts"),
            "applications", "sessions", "generations"),
            "applications", rows(detail.get("applications"), "applicationId", "name", "status")));
    }

    @PostMapping("/avatars/{avatarId}/versions")
    public AjaxResult createVersion(HttpServletRequest request, @PathVariable long avatarId,
        @Valid @RequestBody CreateAvatarVersionRequest input)
    { return AjaxResult.success(production.createVersion(account(request), avatarId, input)); }

    @GetMapping("/avatars/{avatarId}/versions/{versionId}/production")
    public AjaxResult production(HttpServletRequest request, @PathVariable long avatarId, @PathVariable long versionId)
    { return AjaxResult.success(production(production.production(account(request), avatarId, versionId))); }

    @PostMapping("/avatars/{avatarId}/versions/{versionId}/actions/{actionCode}/selection")
    public AjaxResult select(HttpServletRequest request, @PathVariable long avatarId, @PathVariable long versionId,
        @PathVariable String actionCode, @Valid @RequestBody AvatarActionSelectionRequest input)
    { return AjaxResult.success(production.select(account(request), avatarId, versionId, actionCode, input)); }

    @PostMapping("/avatars/{avatarId}/versions/{versionId}/actions/{actionCode}/generations")
    public AjaxResult generate(HttpServletRequest request, @PathVariable long avatarId, @PathVariable long versionId,
        @PathVariable String actionCode, @Valid @RequestBody AvatarActionGenerationRequest input)
    { return AjaxResult.success(production.generate(account(request), avatarId, versionId, actionCode, input)); }

    @PostMapping("/avatars/{avatarId}/versions/{versionId}/actions/{actionCode}/attempts/{attemptId}/recovery")
    public AjaxResult recover(HttpServletRequest request, @PathVariable long avatarId, @PathVariable long versionId,
        @PathVariable String actionCode, @PathVariable long attemptId, @Valid @RequestBody AvatarAttemptRequest input)
    { return AjaxResult.success(production.recover(account(request), avatarId, versionId, actionCode, attemptId, input)); }

    @PostMapping("/avatars/{avatarId}/versions/{versionId}/actions/{actionCode}/attempts/{attemptId}/discard")
    public AjaxResult discard(HttpServletRequest request, @PathVariable long avatarId, @PathVariable long versionId,
        @PathVariable String actionCode, @PathVariable long attemptId, @Valid @RequestBody AvatarAttemptDiscardRequest input)
    { return AjaxResult.success(production.discard(account(request), avatarId, versionId, actionCode, attemptId, input)); }

    @PostMapping("/avatars/{avatarId}/versions/{versionId}/assemble")
    public AjaxResult assemble(HttpServletRequest request, @PathVariable long avatarId, @PathVariable long versionId,
        @Valid @RequestBody AvatarAssemblyRequest input)
    { return AjaxResult.success(production.assemble(account(request), avatarId, versionId, input)); }

    @GetMapping("/avatars/{avatarId}/versions/{versionId}/actions/{actionCode}/results/{resultId}/preview")
    public AjaxResult result(HttpServletRequest request, @PathVariable long avatarId, @PathVariable long versionId,
        @PathVariable String actionCode, @PathVariable long resultId)
    { return AjaxResult.success(safe(production.preview(account(request), avatarId, versionId, actionCode, resultId),
        "resultId", "actionCode", "frameCount", "fps", "loopEnabled", "frameLayout", "atlasUrl", "expiresAt")); }

    @GetMapping("/avatars/{avatarId}/versions/{versionId}/preview")
    public AjaxResult preview(HttpServletRequest request, @PathVariable long avatarId, @PathVariable long versionId)
    { return AjaxResult.success(publication.preview(account(request), avatarId, versionId)); }

    @PostMapping("/avatars/{avatarId}/versions/{versionId}/publish")
    public AjaxResult publish(HttpServletRequest request, @PathVariable long avatarId, @PathVariable long versionId,
        @Valid @RequestBody PublishAvatarVersionRequest input)
    { return AjaxResult.success(publication.publish(account(request), avatarId, versionId, input)); }

    @DeleteMapping("/avatars/{avatarId}")
    public ResponseEntity<AjaxResult> delete(HttpServletRequest request, @PathVariable long avatarId,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String key)
    { return ResponseEntity.accepted().body(AjaxResult.success(publication.deleteAvatar(account(request), avatarId, ifMatch, key))); }

    @GetMapping("/voices")
    public AjaxResult voices(HttpServletRequest request, @RequestParam(defaultValue = "1") int pageNum,
        @RequestParam(defaultValue = "20") int pageSize)
    { account(request); return AjaxResult.success(voices.listOfficial(pageNum, pageSize, null, true)); }

    private static long account(HttpServletRequest request)
    {
        Object value = request.getAttribute(ManagementKeyFilter.PRINCIPAL);
        if (!(value instanceof IAccessKeyService.Principal principal)) throw new ServiceException("接入凭证无效", 401);
        return principal.accountId();
    }

    private static TaskView task(GenerationTaskResponse task)
    { return new TaskView(String.valueOf(task.getTaskId()), String.valueOf(task.getAvatarId()),
        String.valueOf(task.getAvatarVersionId()), task.getStatus(), task.getProgress(), task.getErrorCode(),
        task.getCreatedAt()); }

    private record TaskView(String taskId, String avatarId, String avatarVersionId, String status,
        Integer progress, String errorCode, java.time.LocalDateTime createdAt) { }

    private static Map<String, Object> avatarPage(Map<String, Object> page)
    { return Map.of("items", rows(page.get("items"), "avatarId", "currentVersionId", "versionId", "name",
        "visibility", "status", "revision", "versionCount", "previewUrl", "previewExpiresAt"),
        "total", page.get("total"), "pageNum", page.get("pageNum"), "pageSize", page.get("pageSize")); }

    private static Map<String, Object> production(Map<String, Object> details)
    {
        Map<String, Object> result = safe(details, "avatarId", "versionId", "versionStatus", "candidateRevision",
            "assemblyRevision", "totalActionCount", "completedActionCount", "acceptedActionCount", "canAssemble", "assemblyStage");
        result.put("actions", rows(details.get("actions"), "actionCode", "actionRevision", "stage", "latestAttemptId",
            "selectedResultId", "acceptedResultId", "errorCode", "safeMessage", "nextRetryAt", "stageStartedAt",
            "resultIds", "allowedOperations"));
        return result;
    }

    private static List<Map<String, Object>> rows(Object value, String... fields)
    {
        if (!(value instanceof List<?> list)) throw new IllegalStateException("资产响应缺少列表");
        return list.stream().map(item -> {
            if (!(item instanceof Map<?, ?> row)) throw new IllegalStateException("资产响应列表格式无效");
            return safe(row, fields);
        }).toList();
    }

    private static Map<String, Object> safe(Map<?, ?> source, String... fields)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : fields) if (source.containsKey(field)) result.put(field, source.get(field));
        return result;
    }
}
