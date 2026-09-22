package com.ruoyi.system.asset.service;

import java.util.Map;
import com.ruoyi.system.asset.dto.AvatarPreviewResponse;
import com.ruoyi.system.asset.dto.AvatarStatusReasonRequest;
import com.ruoyi.system.asset.dto.PublishAvatarVersionRequest;

public interface IAvatarPublicationService
{
    AvatarPreviewResponse preview(Long accountId, Long avatarId, Long versionId);
    Map<String, Object> runtimePackage(Long accountId, Long avatarId, Long versionId);
    AvatarPreviewResponse publish(Long accountId, Long avatarId, Long versionId, PublishAvatarVersionRequest request);
    void requireOwnedPublishedVersion(Long accountId, Long avatarId, Long versionId);
    Map<String, Object> listPublic(int pageNum, int pageSize);
    Map<String, Object> listOwned(Long accountId, int pageNum, int pageSize, String status, String keyword);
    Map<String, Object> listAdminPublic(int pageNum, int pageSize, String status);
    Map<String, Object> detail(Long accountId, Long avatarId);
    Map<String, Object> references(Long accountId, Long avatarId);
    Map<String, Object> unpublish(Long operatorId, Long avatarId, AvatarStatusReasonRequest request);
    Map<String, Object> disable(Long operatorId, Long avatarId, AvatarStatusReasonRequest request);
    Map<String, Object> deleteAvatar(Long accountId, Long avatarId, String ifMatch, String idempotencyKey);
}
