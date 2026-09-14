package com.ruoyi.system.voice;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.security.utils.SecurityUtils;

/** Minimal console Voice API; provider credentials and arbitrary relay URLs are deliberately absent. */
@RestController
@RequestMapping("/api/v1/voices")
public class VoiceController
{
    private final VoiceService voices;
    public VoiceController(VoiceService voices) { this.voices = voices; }

    @PostMapping
    public VoiceService.VoiceResponse create(@RequestBody VoiceService.VoiceRequest request)
    { return voices.create(requireAccount(), request); }

    @PostMapping("/{voiceId}/versions")
    public VoiceService.VoiceResponse createVersion(@PathVariable long voiceId, @RequestBody VoiceService.VoiceRequest request)
    { return voices.createVersion(requireAccount(), voiceId, request); }

    @PostMapping("/{voiceId}/versions/{versionId}/publish")
    public VoiceService.VoiceResponse publish(@PathVariable long voiceId, @PathVariable long versionId)
    { return voices.publish(requireAccount(), voiceId, versionId); }

    @GetMapping("/{voiceId}")
    public VoiceService.VoiceResponse read(@PathVariable long voiceId) { return voices.read(requireAccount(), voiceId); }

    @PostMapping("/admin/official")
    public VoiceService.VoiceResponse createOfficial(@RequestBody VoiceService.OfficialVoiceRequest request)
    {
        if (!SecurityUtils.isAdmin()) throw new com.ruoyi.common.core.exception.ServiceException("仅管理员可配置官方 Voice", 403);
        return voices.createOfficial(requireAccount(), request);
    }

    private static long requireAccount()
    {
        Long userId = SecurityUtils.getUserId();
        if (userId == null || userId <= 0) throw new com.ruoyi.common.core.exception.ServiceException("当前后台登录无效", 401);
        return userId;
    }
}
