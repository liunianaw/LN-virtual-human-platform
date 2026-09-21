package com.ruoyi.system.voice;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.model.LoginUser;

/** Browser endpoints are routed as /api/v1 unchanged; IDs and revisions stay decimal strings in DTOs. */
@RestController
@RequestMapping("/api/v1")
public class VoiceController
{
    private final VoiceService voices;
    private final DebugSessionService sessions;

    public VoiceController(VoiceService voices, DebugSessionService sessions)
    { this.voices = voices; this.sessions = sessions; }

    /** Only published OFFICIAL voices are visible to ordinary signed-in users. */
    @GetMapping("/voices")
    public AjaxResult listPublished(@RequestParam(required = false) Integer pageNum,
        @RequestParam(required = false) Integer pageSize)
    {
        requireLogin();
        return AjaxResult.success(voices.listOfficial(pageNum, pageSize, null, true));
    }

    /** Preserved private Relay endpoints; their feature is deferred but existing callers/data are not removed. */
    @PostMapping("/voices")
    public AjaxResult createPrivate(@RequestBody VoiceService.PrivateVoiceInput request)
    { return AjaxResult.success(voices.createPrivate(requireLogin().getUserid(), request)); }

    @PostMapping("/voices/{voiceId}/versions")
    public AjaxResult createPrivateVersion(@PathVariable long voiceId, @RequestBody VoiceService.PrivateVoiceInput request)
    { return AjaxResult.success(voices.createPrivateVersion(requireLogin().getUserid(), voiceId, request)); }

    @PostMapping("/voices/{voiceId}/versions/{versionId}/publish")
    public AjaxResult publishPrivate(@PathVariable long voiceId, @PathVariable long versionId)
    { return AjaxResult.success(voices.publishPrivate(requireLogin().getUserid(), voiceId, versionId)); }

    @GetMapping("/voices/{voiceId}")
    public AjaxResult readPrivate(@PathVariable long voiceId)
    { return AjaxResult.success(voices.readPrivate(requireLogin().getUserid(), voiceId)); }

    @RequiresPermissions("platform:officialVoice:read")
    @GetMapping("/admin/voices")
    public AjaxResult listOfficial(@RequestParam(required = false) Integer pageNum,
        @RequestParam(required = false) Integer pageSize, @RequestParam(required = false) String status)
    { requireAdministrator(); return AjaxResult.success(voices.listOfficial(pageNum, pageSize, status, false)); }

    @RequiresPermissions("platform:officialVoice:read")
    @GetMapping("/admin/voices/services")
    public AjaxResult listServices()
    { requireAdministrator(); return AjaxResult.success(voices.listAvailableOfficialServices()); }

    @RequiresPermissions("platform:officialVoice:read")
    @GetMapping("/admin/voices/{voiceId}")
    public AjaxResult getOfficial(@PathVariable long voiceId)
    { requireAdministrator(); return AjaxResult.success(voices.getOfficial(voiceId)); }

    @RequiresPermissions("platform:officialVoice:write")
    @PostMapping("/admin/voices")
    public AjaxResult create(@RequestBody VoiceService.OfficialVoiceInput request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey)
    { return AjaxResult.success(voices.createOfficialCandidate(requireAdministrator().getUserid(), request, idempotencyKey)); }

    /** Retained only as an explicit adapter for the old M2 administrator call; it now saves a DRAFT. */
    @RequiresPermissions("platform:officialVoice:write")
    @PostMapping("/voices/admin/official")
    public AjaxResult createLegacyOfficial(@RequestBody LegacyOfficialVoiceRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey)
    {
        LoginUser login = requireAdministrator();
        VoiceService.OfficialServiceResponse service = voices.listAvailableOfficialServices().stream()
            .filter(item -> item.serviceId().equals(Long.toString(request.officialServiceId() == null ? 0 : request.officialServiceId())))
            .findFirst().orElseThrow(() -> new ServiceException("官方 TTS 服务不可用", 409));
        return AjaxResult.success(voices.createOfficialCandidate(login.getUserid(), new VoiceService.OfficialVoiceInput(request.name(),
            request.description(), request.officialServiceId(), service.revision(), "Cherry", "zh-CN", null, java.util.Map.of()), idempotencyKey));
    }

    @RequiresPermissions("platform:officialVoice:write")
    @PostMapping("/admin/voices/{voiceId}/versions")
    public AjaxResult createVersion(@PathVariable long voiceId, @RequestBody VoiceService.OfficialVoiceInput request,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey)
    { return AjaxResult.success(voices.createOfficialVersion(requireAdministrator().getUserid(), voiceId, request, ifMatch, idempotencyKey)); }

    @RequiresPermissions("platform:officialVoice:write")
    @PostMapping("/admin/voices/{voiceId}/versions/{versionId}/publish")
    public AjaxResult publish(@PathVariable long voiceId, @PathVariable long versionId,
        @RequestBody VoiceService.PublishRequest request, @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey)
    { return AjaxResult.success(voices.publishOfficial(requireAdministrator().getUserid(), voiceId, versionId, request, ifMatch, idempotencyKey)); }

    /** Candidate preview always creates an isolated VOICE_PREVIEW binding, never a public application binding. */
    @RequiresPermissions("platform:officialVoice:write")
    @PostMapping("/admin/voices/{voiceId}/versions/{versionId}/preview-sessions")
    public AjaxResult preview(@PathVariable long voiceId, @PathVariable long versionId,
        @RequestBody PreviewRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey)
    {
        LoginUser login = requireAdministrator();
        if (request == null || request.avatarVersionId() == null || request.avatarVersionId() <= 0)
            throw new ServiceException("avatarVersionId 无效", 400);
        VoiceService.PreviewApplication app = voices.preparePreview(login.getUserid(), voiceId, versionId, request.avatarVersionId());
        DebugSessionService.DebugSession session = sessions.createPreview(login.getUserid(), login, Long.parseLong(app.applicationId()), idempotencyKey);
        return AjaxResult.success(new PreviewSessionResponse(Long.toString(session.sessionId()), app.applicationId(), app.configVersionId()));
    }

    private static LoginUser requireAdministrator()
    {
        LoginUser login = requireLogin();
        if (!SecurityUtils.isAdmin()) throw new ServiceException("仅管理员可配置官方声音", 403);
        return login;
    }
    private static LoginUser requireLogin()
    {
        LoginUser login = SecurityUtils.getLoginUser();
        if (login == null || login.getUserid() == null || login.getUserid() <= 0 || login.getToken() == null)
            throw new ServiceException("当前后台登录无效", 401);
        return login;
    }
    public record PreviewRequest(Long avatarVersionId) { }
    public record PreviewSessionResponse(String sessionId, String applicationId, String configVersionId) { }
    public record LegacyOfficialVoiceRequest(String name, String description, Long officialServiceId) { }
}
