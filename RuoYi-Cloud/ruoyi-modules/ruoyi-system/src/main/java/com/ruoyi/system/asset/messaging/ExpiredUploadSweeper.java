package com.ruoyi.system.asset.messaging;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.system.asset.mapper.AssetMapper;
import com.ruoyi.system.asset.service.IAssetStorageQuotaService;
import com.ruoyi.system.storage.ObjectStorage;

/** Reclaims unfinished reference uploads only after object deletion succeeds. */
@Component
public class ExpiredUploadSweeper
{
    private static final Logger LOG = LoggerFactory.getLogger(ExpiredUploadSweeper.class);
    private final AssetMapper mapper;
    private final ObjectProvider<ObjectStorage> storage;
    private final TransactionTemplate transactions;
    private final IAssetStorageQuotaService quota;

    public ExpiredUploadSweeper(AssetMapper mapper, ObjectProvider<ObjectStorage> storage,
        TransactionTemplate transactions, IAssetStorageQuotaService quota)
    { this.mapper = mapper; this.storage = storage; this.transactions = transactions; this.quota = quota; }

    @Scheduled(fixedDelay = 60000)
    public void sweep()
    {
        ObjectStorage objects = storage.getIfAvailable();
        if (objects == null) return;
        for (Map<String, Object> file : mapper.expiredStorageUploads())
        {
            long accountId = ((Number) file.get("accountId")).longValue();
            long fileId = ((Number) file.get("fileId")).longValue();
            try
            {
                if ("UPLOADING".equals(file.get("status")))
                {
                    Integer claimed = transactions.execute(status -> mapper.claimExpiredStorageUpload(accountId, fileId));
                    if (claimed == null || claimed != 1) continue;
                }
                objects.delete((String) file.get("objectKey"));
                transactions.executeWithoutResult(status -> quota.fail(accountId, fileId));
            }
            catch (RuntimeException error)
            {
                LOG.warn("unfinished reference upload cleanup failed: fileId={}", fileId);
            }
        }
    }
}
