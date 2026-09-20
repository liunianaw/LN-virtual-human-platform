package com.ruoyi.system.asset.service;

import java.util.Map;
import com.ruoyi.system.asset.dto.AvatarActionGenerationRequest;
import com.ruoyi.system.asset.dto.AvatarActionSelectionRequest;
import com.ruoyi.system.asset.dto.AvatarAssemblyRequest;
import com.ruoyi.system.asset.dto.AvatarAttemptDiscardRequest;
import com.ruoyi.system.asset.dto.AvatarAttemptRequest;
import com.ruoyi.system.asset.dto.CreateAvatarVersionRequest;

public interface IAvatarProductionService
{
    Map<String, Object> production(Long accountId, Long avatarId, Long versionId);
    Map<String, Object> preview(Long accountId, Long avatarId, Long versionId, String actionCode, Long resultId);
    Object select(Long accountId, Long avatarId, Long versionId, String actionCode, AvatarActionSelectionRequest request);
    Object generate(Long accountId, Long avatarId, Long versionId, String actionCode, AvatarActionGenerationRequest request);
    Object recover(Long accountId, Long avatarId, Long versionId, String actionCode, Long attemptId, AvatarAttemptRequest request);
    Object discard(Long accountId, Long avatarId, Long versionId, String actionCode, Long attemptId, AvatarAttemptDiscardRequest request);
    Object createVersion(Long accountId, Long avatarId, CreateAvatarVersionRequest request);
    Object assemble(Long accountId, Long avatarId, Long versionId, AvatarAssemblyRequest request);
}
