package com.ruoyi.session.runtime;

/** Claims returned only after a trusted token signature and console-login check. */
public record TrustedConsoleDebugGrantClaims(String tokenId, long accountId, long applicationId, long sessionId,
        long configVersionId, long accountEpoch, long applicationEpoch, long principalEpoch, long sessionEpoch,
        RuntimePrincipal principal)
{
}
