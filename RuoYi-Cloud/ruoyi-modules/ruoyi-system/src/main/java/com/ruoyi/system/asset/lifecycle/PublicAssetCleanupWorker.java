package com.ruoyi.system.asset.lifecycle;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Small, persisted-state-driven worker. It is safe for retries because the storage adapter treats absent objects as deletion success. */
@Component
public class PublicAssetCleanupWorker
{
    private final PublicAssetLifecycleService lifecycle;
    public PublicAssetCleanupWorker(PublicAssetLifecycleService lifecycle) { this.lifecycle = lifecycle; }
    @Scheduled(fixedDelayString = "${platform.asset.lifecycle.cleanup-delay-ms:30000}")
    public void cleanup() { lifecycle.runOneCleanup("avatars"); lifecycle.runOneCleanup("voices"); }
}
