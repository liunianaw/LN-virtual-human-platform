package com.ruoyi.system.asset.service;

import java.util.List;
import java.util.Map;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.system.asset.dto.AssetFileResponse;
import com.ruoyi.system.asset.dto.AvatarGenerationServiceResponse;
import com.ruoyi.system.asset.dto.CreateGenerationTaskRequest;
import com.ruoyi.system.asset.dto.GenerationTaskResponse;

public interface IAssetService
{
    AssetFileResponse uploadReference(Long accountId, MultipartFile file, String rightsNoticeVersion, Boolean rightsConfirmed);
    AssetFileResponse readReference(Long accountId, Long fileId);
    GenerationTaskResponse createGenerationTask(Long accountId, CreateGenerationTaskRequest request);
    GenerationTaskResponse readGenerationTask(Long accountId, Long taskId);
    List<GenerationTaskResponse> listGenerationTasks(Long accountId);
    Map<String, Object> pageGenerationTasks(Long accountId, int pageNum, int pageSize);
    List<Map<String, Object>> listGenerationSteps(Long accountId, Long taskId);
    List<AvatarGenerationServiceResponse> listAvatarGenerationServices(Long accountId);
}
