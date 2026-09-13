package com.ruoyi.system.asset.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.asset.domain.AssetFile;
import com.ruoyi.system.asset.domain.AvatarActionRecord;
import com.ruoyi.system.asset.domain.AvatarRecord;
import com.ruoyi.system.asset.domain.AvatarVersionRecord;

/** Avatar 候选预览、发布和删除受限的持久化操作。 */
public interface AvatarPublicationMapper
{
    AvatarVersionRecord selectOwnedVersion(@Param("accountId") Long accountId, @Param("avatarId") Long avatarId,
        @Param("versionId") Long versionId);

    AvatarVersionRecord selectOwnedVersionForUpdate(@Param("accountId") Long accountId, @Param("avatarId") Long avatarId,
        @Param("versionId") Long versionId);

    AvatarRecord selectOwnedAvatarForUpdate(@Param("accountId") Long accountId, @Param("avatarId") Long avatarId);

    List<AvatarActionRecord> selectActions(@Param("avatarVersionId") Long avatarVersionId);

    AssetFile selectAvailableOwnedFile(@Param("accountId") Long accountId, @Param("fileId") Long fileId);

    int countUnavailableVersionFiles(@Param("accountId") Long accountId, @Param("avatarVersionId") Long avatarVersionId);

    int countUnavailableActionFiles(@Param("accountId") Long accountId, @Param("avatarVersionId") Long avatarVersionId);

    int updateVersionToReview(@Param("accountId") Long accountId, @Param("avatarVersionId") Long avatarVersionId);

    int updateTaskToReview(@Param("accountId") Long accountId, @Param("taskId") Long taskId,
        @Param("avatarVersionId") Long avatarVersionId);

    int updateVersionToPublished(@Param("accountId") Long accountId, @Param("avatarVersionId") Long avatarVersionId,
        @Param("acceptedBy") Long acceptedBy);

    int updateAvatarCurrentVersion(@Param("accountId") Long accountId, @Param("avatarId") Long avatarId,
        @Param("avatarVersionId") Long avatarVersionId);

    int countActiveReferences(@Param("avatarId") Long avatarId);

    int countActiveGenerationTasks(@Param("accountId") Long accountId, @Param("avatarId") Long avatarId);

    int markAvatarDeleting(@Param("accountId") Long accountId, @Param("avatarId") Long avatarId);
}
