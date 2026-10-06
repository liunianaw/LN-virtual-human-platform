package com.ruoyi.system.asset.service.impl;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.constant.HttpStatus;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.asset.domain.AssetFile;
import com.ruoyi.system.asset.domain.GenerationServiceConfig;
import com.ruoyi.system.asset.domain.GenerationTask;
import com.ruoyi.system.asset.dto.AssetFileResponse;
import com.ruoyi.system.asset.dto.AvatarGenerationServiceResponse;
import com.ruoyi.system.asset.dto.CreateGenerationTaskRequest;
import com.ruoyi.system.asset.dto.GenerationTaskResponse;
import com.ruoyi.system.asset.mapper.AssetMapper;
import com.ruoyi.system.asset.service.IAssetService;
import com.ruoyi.system.asset.service.IAssetStorageQuotaService;
import com.ruoyi.system.asset.service.IGenerationQuotaService;
import com.ruoyi.system.storage.ObjectStorage;

/** M2 参考图账本、账户授权和制作任务提交。 */
@Service
public class AssetServiceImpl implements IAssetService
{
    private static final long MAX_SOURCE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_SOURCE_DIMENSION = 4096;
    private static final String PIPELINE_VERSION = "m2-asset-v1";

    private final AssetMapper assetMapper;
    private final ObjectProvider<ObjectStorage> storageProvider;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final IGenerationQuotaService quota;
    private final IAssetStorageQuotaService storageQuota;

    public AssetServiceImpl(AssetMapper assetMapper, ObjectProvider<ObjectStorage> storageProvider,
        TransactionTemplate transactionTemplate, ObjectMapper objectMapper, IGenerationQuotaService quota,
        IAssetStorageQuotaService storageQuota)
    {
        this.assetMapper = assetMapper;
        this.storageProvider = storageProvider;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.quota = quota;
        this.storageQuota = storageQuota;
    }

    public AssetFileResponse uploadReference(Long accountId, MultipartFile multipartFile, String rightsNoticeVersion,
        Boolean rightsConfirmed)
    {
        requireAccount(accountId);
        if (!Boolean.TRUE.equals(rightsConfirmed) || isBlank(rightsNoticeVersion) || rightsNoticeVersion.length() > 32)
            throw new ServiceException("请确认参考图使用权并提供有效的权利提示版本");
        ParsedImage image = parseReferenceImage(multipartFile);
        ObjectStorage storage = requireStorage();
        if (isBlank(storage.bucket())) throw new ServiceException("云对象存储桶配置无效");

        Long fileId = nextId();
        String objectKey = "avatar-sources/" + accountId + "/" + fileId + "-"
            + UUID.randomUUID().toString().replace("-", "") + image.extension();
        AssetFile file = new AssetFile();
        file.setId(fileId);
        file.setAccountId(accountId);
        file.setPurpose("AVATAR_SOURCE");
        file.setStorageProvider(storage.provider());
        file.setBucket(storage.bucket());
        file.setObjectKey(objectKey);
        file.setOriginalName(safeOriginalName(multipartFile.getOriginalFilename()));
        file.setContentType(image.contentType());
        file.setSizeBytes((long) image.bytes().length);
        file.setSha256(sha256(image.bytes()));
        file.setWidth(image.width());
        file.setHeight(image.height());
        file.setStatus("UPLOADING");
        file.setRightsNoticeVersion(rightsNoticeVersion.trim());

        transactionTemplate.executeWithoutResult(status -> {
            file.setStorageReservationId(storageQuota.reserve(accountId, fileId, image.bytes().length));
            assetMapper.insertFile(file);
        });
        try
        {
            storage.put(objectKey, image.bytes(), image.contentType());
            String url = storage.readUrl(objectKey);
            transactionTemplate.executeWithoutResult(status -> storageQuota.complete(accountId, fileId));
            file.setStatus("AVAILABLE");
            return fileResponse(file, url);
        }
        catch (RuntimeException e)
        {
            if (cleanupUnrecordedObject(storage, objectKey))
            {
                try { transactionTemplate.executeWithoutResult(status -> storageQuota.fail(accountId, fileId)); }
                catch (RuntimeException releaseFailure) { e.addSuppressed(releaseFailure); }
            }
            throw e;
        }
    }

    public AssetFileResponse readReference(Long accountId, Long fileId)
    {
        requireAccount(accountId);
        AssetFile file = assetMapper.selectAvailableFile(accountId, fileId);
        if (file == null) throw forbidden("无权访问此参考图");
        return fileResponse(file, requireStorage().readUrl(file.getObjectKey()));
    }

    private GenerationTaskResponse createGenerationTask(Long accountId, CreateGenerationTaskRequest request, boolean officialCreation)
    {
        requireAccount(accountId);
        validateCreateRequest(request);
        String visibility = officialCreation ? "OFFICIAL" : "PRIVATE";
        byte[] requestHash = taskRequestHash(accountId, request, visibility);
        GenerationTask existing = assetMapper.selectTaskByAccountAndRequest(accountId, request.getRequestId());
        if (existing != null) return matchingExistingTask(accountId, request, visibility, requestHash, existing);
        try
        {
            GenerationTaskResponse response = transactionTemplate.execute(status -> createGenerationTaskInTransaction(accountId, request, visibility, requestHash));
            if (response == null) throw new ServiceException("创建制作任务失败");
            return response;
        }
        catch (DataIntegrityViolationException e)
        {
            GenerationTask duplicate = assetMapper.selectTaskByAccountAndRequest(accountId, request.getRequestId());
            if (duplicate != null) return matchingExistingTask(accountId, request, visibility, requestHash, duplicate);
            throw e;
        }
        catch (ServiceException e)
        {
            if (e.getCode() == 429)
            {
                GenerationTask duplicate = assetMapper.selectTaskByAccountAndRequest(accountId, request.getRequestId());
                if (duplicate != null) return matchingExistingTask(accountId, request, visibility, requestHash, duplicate);
            }
            throw e;
        }
    }

    @Override
    public GenerationTaskResponse createGenerationTask(Long accountId, CreateGenerationTaskRequest request)
    {
        return createGenerationTask(accountId, request, SecurityUtils.isAdmin());
    }

    public GenerationTaskResponse readGenerationTask(Long accountId, Long taskId)
    {
        requireAccount(accountId);
        GenerationTask task = assetMapper.selectTaskByAccountAndId(accountId, taskId);
        if (task == null || !matchesRole(task)) throw forbidden("无权访问此制作任务");
        return taskResponse(task);
    }

    /** 返回当前账号最近的制作任务，供后台人工验收入口恢复工作上下文。 */
    public List<GenerationTaskResponse> listGenerationTasks(Long accountId)
    {
        requireAccount(accountId);
        return assetMapper.selectRecentTasksByAccount(accountId).stream().filter(this::matchesRole).map(this::taskResponse).toList();
    }

    public Map<String, Object> pageGenerationTasks(Long accountId, int pageNum, int pageSize)
    {
        requireAccount(accountId);
        if (pageNum < 1 || pageSize < 1 || pageSize > 100 || (long) (pageNum - 1) * pageSize > Integer.MAX_VALUE)
            throw new ServiceException("分页参数无效", HttpStatus.BAD_REQUEST);
        return Map.of("items", assetMapper.selectPageTasksByAccount(accountId, pageSize, (pageNum - 1) * pageSize)
            .stream().map(this::taskResponse).toList(), "total", assetMapper.countTasksByAccount(accountId),
            "pageNum", pageNum, "pageSize", pageSize);
    }

    public List<Map<String, Object>> listGenerationSteps(Long accountId, Long taskId)
    {
        readGenerationTask(accountId, taskId);
        return assetMapper.selectTaskStepsByAccount(accountId, taskId);
    }

    @Override
    public Map<String, Object> pageConsoleGenerationTasks(Long accountId, int pageNum, int pageSize)
    {
        requireAccount(accountId);
        if (!accountId.equals(SecurityUtils.getUserId())) throw forbidden("只能查看当前账号的制作任务");
        if (pageNum < 1 || pageSize < 1 || pageSize > 100 || (long) (pageNum - 1) * pageSize > Integer.MAX_VALUE)
            throw new ServiceException("分页参数无效", HttpStatus.BAD_REQUEST);
        String visibility = SecurityUtils.isAdmin() ? "OFFICIAL" : "PRIVATE";
        return Map.of("items", assetMapper.selectConsoleTasks(accountId, visibility, pageSize, (pageNum - 1) * pageSize)
            .stream().map(this::taskResponse).toList(), "total", assetMapper.countConsoleTasks(accountId, visibility),
            "pageNum", pageNum, "pageSize", pageSize);
    }

    /** 返回当前账号可选的启用官方制作服务；Mapper 仅查询可公开展示的字段。 */
    public List<AvatarGenerationServiceResponse> listAvatarGenerationServices(Long accountId)
    {
        requireAccount(accountId);
        return assetMapper.selectActiveAvatarGenerationServices();
    }

    private boolean matchesRole(GenerationTask task)
    {
        return SecurityUtils.isAdmin() == "OFFICIAL".equals(assetMapper.avatarVisibility(task.getAccountId(), task.getAvatarId()));
    }

    private GenerationTaskResponse createGenerationTaskInTransaction(Long accountId, CreateGenerationTaskRequest request,
        String visibility, byte[] requestHash)
    {
        GenerationTask existing = assetMapper.selectTaskByAccountAndRequest(accountId, request.getRequestId());
        if (existing != null) return matchingExistingTask(accountId, request, visibility, requestHash, existing);
        AssetFile sourceFile = assetMapper.selectAvailableFile(accountId, request.getSourceFileId());
        if (sourceFile == null) throw forbidden("参考图不存在、不可用或不属于当前账号");
        GenerationServiceConfig service = assetMapper.selectActiveAvatarGenerationService(request.getOfficialServiceId());
        if (service == null) throw new ServiceException("指定的 Avatar 制作服务不可用");
        if (service.getRevision() != request.getExpectedServiceRevision().longValue())
            throw new ServiceException("制作服务配置已变化，请重新选择", HttpStatus.CONFLICT);
        Long avatarId = nextId();
        Long avatarVersionId = nextId();
        Long taskId = nextId();
        Long reservationId = nextId();
        quota.reserve(accountId, taskId, reservationId, 8);
        assetMapper.insertAvatar(avatarId, accountId, request.getName().trim(), visibility);
        assetMapper.insertAvatarVersion(avatarVersionId, avatarId, accountId, sourceFile.getId(), PIPELINE_VERSION,
            json(Map.of("pipelineVersion", PIPELINE_VERSION, "sourceSha256", hex(sourceFile.getSha256()))));
        assetMapper.insertGenerationTask(taskId, accountId, avatarId, avatarVersionId, sourceFile.getId(), service.getId(),
            serviceSnapshot(service), PIPELINE_VERSION, reservationId, request.getRequestId(), requestHash);
        for (String action : List.of("idle", "speaking", "listening", "thinking", "nod", "shake_head", "wave", "happy"))
            assetMapper.insertGenerationActionStep(nextId(), accountId, taskId, "ACTION_" + action, action, nextId());
        String eventId = UUID.randomUUID().toString().replace("-", "");
        assetMapper.insertOutbox(nextId(), accountId, eventId, "AVATAR_GENERATION_REQUESTED", "GENERATION_TASK",
            taskId.toString(), UUID.randomUUID().toString().replace("-", ""), json(Map.of(
                "taskId", taskId, "avatarId", avatarId, "avatarVersionId", avatarVersionId,
                "sourceFileId", sourceFile.getId(), "quotaReservationId", reservationId, "status", "QUEUED")));

        GenerationTask task = new GenerationTask();
        task.setId(taskId);
        task.setAccountId(accountId);
        task.setAvatarId(avatarId);
        task.setAvatarVersionId(avatarVersionId);
        task.setSourceFileId(sourceFile.getId());
        task.setQuotaReservationId(reservationId);
        task.setRequestId(request.getRequestId());
        task.setStatus("QUEUED");
        task.setInternalState("READY");
        task.setProgress(0);
        return taskResponse(task);
    }

    private ParsedImage parseReferenceImage(MultipartFile file)
    {
        if (file == null || file.isEmpty() || file.getSize() > MAX_SOURCE_BYTES)
            throw new ServiceException("参考图不能为空，且大小不能超过 10 MB");
        try
        {
            byte[] bytes = file.getBytes();
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes)))
            {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new ServiceException("参考图必须为真实 PNG 或 JPEG 图片");
                var reader = readers.next();
                try
                {
                    reader.setInput(input);
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!format.equals("png") && !format.equals("jpeg") && !format.equals("jpg"))
                        throw new ServiceException("参考图仅支持 PNG 或 JPEG");
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (width <= 0 || height <= 0 || width > MAX_SOURCE_DIMENSION || height > MAX_SOURCE_DIMENSION)
                        throw new ServiceException("参考图尺寸不能超过 4096 × 4096");
                    if (reader.read(0) == null) throw new ServiceException("无法读取参考图");
                    return format.equals("png")
                        ? new ParsedImage(bytes, width, height, "image/png", ".png")
                        : new ParsedImage(bytes, width, height, "image/jpeg", ".jpg");
                }
                finally { reader.dispose(); }
            }
        }
        catch (ServiceException e) { throw e; }
        catch (Exception e) { throw new ServiceException("无法读取参考图"); }
    }

    private void validateCreateRequest(CreateGenerationTaskRequest request)
    {
        if (request == null || request.getSourceFileId() == null || request.getSourceFileId() <= 0
            || request.getOfficialServiceId() == null || request.getOfficialServiceId() <= 0
            || request.getExpectedServiceRevision() == null || request.getExpectedServiceRevision() <= 0
            || isBlank(request.getRequestId()) || request.getRequestId().length() > 64
            || !request.getRequestId().matches("[A-Za-z0-9._:-]+")
            || isBlank(request.getName()) || request.getName().trim().length() > 100)
            throw new ServiceException("制作任务参数无效");
    }

    private AssetFileResponse fileResponse(AssetFile file, String readUrl)
    {
        AssetFileResponse response = new AssetFileResponse();
        response.setFileId(file.getId());
        response.setContentType(file.getContentType());
        response.setSizeBytes(file.getSizeBytes());
        response.setWidth(file.getWidth());
        response.setHeight(file.getHeight());
        response.setReadUrl(readUrl);
        return response;
    }

    private GenerationTaskResponse taskResponse(GenerationTask task)
    {
        GenerationTaskResponse response = new GenerationTaskResponse();
        response.setTaskId(task.getId());
        response.setAvatarId(task.getAvatarId());
        response.setAvatarVersionId(task.getAvatarVersionId());
        response.setSourceFileId(task.getSourceFileId());
        response.setRequestId(task.getRequestId());
        response.setStatus(task.getStatus());
        response.setInternalState(task.getInternalState());
        response.setProgress(task.getProgress());
        response.setErrorCode(task.getErrorCode());
        response.setCreatedAt(task.getCreatedAt());
        return response;
    }

    private String serviceSnapshot(GenerationServiceConfig service)
    {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("serviceId", service.getId());
        snapshot.put("name", service.getName());
        snapshot.put("providerCode", service.getProviderCode());
        snapshot.put("endpoint", service.getEndpoint());
        snapshot.put("modelId", service.getModelId());
        snapshot.put("parameters", service.getParameters());
        snapshot.put("revision", service.getRevision());
        return json(snapshot);
    }

    private String json(Map<String, ?> value)
    {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new ServiceException("无法生成任务元数据"); }
    }

    private Long nextId()
    {
        Long id = assetMapper.nextId();
        if (id == null || id <= 0) throw new ServiceException("无法生成业务标识");
        return id;
    }

    private GenerationTaskResponse matchingExistingTask(Long accountId, CreateGenerationTaskRequest request,
        String visibility, byte[] requestHash, GenerationTask task)
    {
        if (assetMapper.countMatchingTaskRequest(accountId, task.getId(), request.getSourceFileId(),
            request.getOfficialServiceId(), request.getName().trim(), visibility, requestHash) != 1)
            throw new ServiceException("同一 requestId 的制作参数不同", HttpStatus.CONFLICT);
        return taskResponse(task);
    }

    private byte[] taskRequestHash(Long accountId, CreateGenerationTaskRequest request, String visibility)
    {
        try
        {
            return MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(Map.of(
                "accountId", accountId.toString(), "sourceFileId", request.getSourceFileId().toString(),
                "officialServiceId", request.getOfficialServiceId().toString(), "name", request.getName().trim(),
                "expectedServiceRevision", request.getExpectedServiceRevision(), "visibility", visibility)));
        }
        catch (Exception e) { throw new ServiceException("无法生成任务幂等摘要"); }
    }

    private ObjectStorage requireStorage()
    {
        ObjectStorage storage = storageProvider.getIfAvailable();
        if (storage == null) throw new ServiceException("云对象存储尚未配置");
        return storage;
    }

    private boolean cleanupUnrecordedObject(ObjectStorage storage, String objectKey)
    {
        try { storage.delete(objectKey); return true; }
        catch (RuntimeException ignored)
        {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("参考图对象清理失败，保留存储预占待核对");
            return false;
        }
    }

    private static void requireAccount(Long accountId)
    {
        if (accountId == null || accountId <= 0) throw new ServiceException("当前登录账号无效");
    }

    private static ServiceException forbidden(String message)
    {
        return new ServiceException(message, HttpStatus.FORBIDDEN);
    }

    private static boolean isBlank(String value)
    {
        return value == null || value.trim().isEmpty();
    }

    private static String safeOriginalName(String value)
    {
        String name = value == null ? null : value.replaceAll("[\\p{Cntrl}]", "_");
        if (name != null && name.length() > 255) throw new ServiceException("参考图文件名过长");
        return name;
    }

    private static byte[] sha256(byte[] bytes)
    {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (Exception e) { throw new ServiceException("无法计算参考图摘要"); }
    }

    private static String hex(byte[] bytes)
    {
        if (bytes == null) return null;
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(String.format("%02x", b));
        return value.toString();
    }

    private record ParsedImage(byte[] bytes, int width, int height, String contentType, String extension) { }
}
