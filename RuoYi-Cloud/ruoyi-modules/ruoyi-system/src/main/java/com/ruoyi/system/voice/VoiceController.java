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
    public VoiceController(VoiceService voices) { this.voices = voices; }

    /** Only published OFFICIAL voices are visible to ordinary signed-in users. */
    @GetMapping("/voices")
    public AjaxResult listPublished(@RequestParam(required = false) Integer pageNum,
        @RequestParam(required = false) Integer pageSize)
    {
        requireLogin();
        return AjaxResult.success(voices.listOfficial(pageNum, pageSize, null, true));
    }


    @RequiresPermissions("platform:officialVoice:read")
    @GetMapping("/admin/public-voices")
    public AjaxResult listOfficial(@RequestParam(required = false) Integer pageNum,
        @RequestParam(required = false) Integer pageSize, @RequestParam(required = false) String status)
    { requireAdministrator(); return AjaxResult.success(voices.listOfficial(pageNum, pageSize, status, false)); }

    @RequiresPermissions("platform:officialVoice:read")
    @GetMapping("/admin/public-voices/services")
    public AjaxResult listServices()
    { requireAdministrator(); return AjaxResult.success(voices.listAvailableOfficialServices()); }

    @RequiresPermissions("platform:officialVoice:read")
    @GetMapping("/admin/public-voices/{voiceId}")
    public AjaxResult getOfficial(@PathVariable long voiceId)
    { requireAdministrator(); return AjaxResult.success(voices.getOfficial(voiceId)); }

    @RequiresPermissions("platform:officialVoice:write")
    @PostMapping("/admin/public-voices")
    public AjaxResult create(@RequestBody VoiceService.OfficialVoiceInput request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey)
    { return AjaxResult.success(voices.createOfficialCandidate(requireAdministrator().getUserid(), request, idempotencyKey)); }

    @RequiresPermissions("platform:officialVoice:write")
    @PostMapping("/admin/public-voices/{voiceId}/versions")
    public AjaxResult createVersion(@PathVariable long voiceId, @RequestBody VoiceService.OfficialVoiceInput request,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey)
    { return AjaxResult.success(voices.createOfficialVersion(requireAdministrator().getUserid(), voiceId, request, ifMatch, idempotencyKey)); }

    @RequiresPermissions("platform:officialVoice:write")
    @PostMapping("/admin/public-voices/{voiceId}/versions/{versionId}/audition")
    public org.springframework.http.ResponseEntity<byte[]> audition(@PathVariable long voiceId, @PathVariable long versionId,
        @RequestBody AuditionRequest request,
        @RequestHeader("Idempotency-Key") String key)
    {
        byte[] audio = voices.audition(requireAdministrator().getUserid(), voiceId, versionId, request.text(), key);
        return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType("audio/wav"))
            .header("Cache-Control", "no-store").body(audio);
    }
    public record AuditionRequest(String text) { }

    @RequiresPermissions("platform:officialVoice:write")
    @PostMapping("/admin/public-voices/{voiceId}/versions/{versionId}/publish")
    public AjaxResult publish(@PathVariable long voiceId, @PathVariable long versionId,
        @RequestBody VoiceService.PublishRequest request, @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey)
    { return AjaxResult.success(voices.publishOfficial(requireAdministrator().getUserid(), voiceId, versionId, request, ifMatch, idempotencyKey)); }


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
}
