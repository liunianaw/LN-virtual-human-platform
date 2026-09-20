package com.ruoyi.system.asset.domain;

/** 可投递的本地 Outbox 事件；payload 只存白名单元数据。 */
public class OutboxEvent
{
    private Long id; private Long accountId; private String eventId; private String eventType; private String traceId; private String payload;
    public Long getId() { return id; } public void setId(Long value) { id = value; }
    public Long getAccountId() { return accountId; } public void setAccountId(Long value) { accountId = value; }
    public String getEventId() { return eventId; } public void setEventId(String value) { eventId = value; }
    public String getEventType() { return eventType; } public void setEventType(String value) { eventType = value; }
    public String getTraceId() { return traceId; } public void setTraceId(String value) { traceId = value; }
    public String getPayload() { return payload; } public void setPayload(String value) { payload = value; }
}
