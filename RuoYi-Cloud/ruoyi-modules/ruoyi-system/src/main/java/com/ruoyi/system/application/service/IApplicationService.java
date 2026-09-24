package com.ruoyi.system.application.service;

import java.util.Map;
import com.ruoyi.system.application.dto.ApplicationConfigRequest;
import com.ruoyi.system.application.dto.ApplicationStatusRequest;
import com.ruoyi.system.application.dto.CreateApplicationRequest;

public interface IApplicationService
{
    Map<String, Object> changeAdminDisabled(long administratorId, long applicationId, boolean disabled, String reason);
    Map<String, Object> list(long accountId, Integer pageNum, Integer pageSize, String status);
    Map<String, Object> detail(long accountId, long applicationId);
    Map<String, Object> config(long accountId, long applicationId, long configVersionId);
    Map<String, Object> choices(long accountId);
    Map<String, Object> create(long accountId, CreateApplicationRequest request, String key);
    Map<String, Object> publish(long accountId, long applicationId, ApplicationConfigRequest request, String ifMatch, String key);
    Map<String, Object> changeStatus(long accountId, long applicationId, ApplicationStatusRequest request, String ifMatch, String key);
}
