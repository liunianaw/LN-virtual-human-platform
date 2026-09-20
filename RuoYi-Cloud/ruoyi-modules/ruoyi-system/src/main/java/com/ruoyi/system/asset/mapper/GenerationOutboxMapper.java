package com.ruoyi.system.asset.mapper;

import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.asset.domain.OutboxEvent;

/** system Outbox publisher 的持久化边界；媒体 Worker 不直接操作 Outbox。 */
public interface GenerationOutboxMapper
{
    OutboxEvent selectPublishableForUpdate();

    int claimForPublish(@Param("id") Long id, @Param("publisherId") String publisherId);

    int markPublished(@Param("id") Long id, @Param("publisherId") String publisherId);

    int releaseForRetry(@Param("id") Long id, @Param("publisherId") String publisherId,
        @Param("retryDelaySeconds") int retryDelaySeconds);
}
