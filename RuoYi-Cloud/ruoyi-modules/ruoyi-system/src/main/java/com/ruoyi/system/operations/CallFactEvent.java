package com.ruoyi.system.operations;

import java.math.BigDecimal;

/** Whitelisted cross-service calling fact.  It deliberately has no request body, credential or URL field. */
public record CallFactEvent(String eventId, String operationKey, Long accountId, String capability, String status,
    Long applicationId, Long sessionId, Long turnId, String providerRequestId, Usage usage,
    BigDecimal costAmount, String currency, String costSource, String errorCode)
{
    public record Usage(Long inputChars, Long imageCount, Long audioDurationMs, Boolean usageAvailable,
        Long inputTokens, Long outputTokens)
    {
        public Usage(Long inputChars, Long imageCount, Long audioDurationMs, Boolean usageAvailable)
        { this(inputChars, imageCount, audioDurationMs, usageAvailable, null, null); }
    }
}
