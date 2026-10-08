package com.ruoyi.system.application.service;

import java.util.Map;
import com.ruoyi.system.application.dto.ApplicationConfigRequest;
import com.ruoyi.system.application.dto.ApplicationStatusRequest;
import com.ruoyi.system.application.dto.CreateApplicationRequest;

public interface IApplicationService
{
    Map<String, Object> list(long accountId, Integer pageNum, Integer pageSize, String status);
    Map<String, Object> search(long accountId, Integer pageNum, Integer pageSize, String status, String keyword);
    Map<String, Object> detail(long accountId, long applicationId);
    Map<String, Object> choices(long accountId, long applicationId);
    Map<String, Object> create(long accountId, CreateApplicationRequest request, String key);
    Map<String, Object> update(long accountId, long applicationId, ApplicationConfigRequest request, String ifMatch, String key);
    Map<String, Object> changeStatus(long accountId, long applicationId, ApplicationStatusRequest request, String ifMatch, String key);
}
