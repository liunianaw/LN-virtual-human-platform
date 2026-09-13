package com.ruoyi.session.runtime;

/**
 * Protected integration point for token signature, JTI and originating console
 * login verification. No browser-provided account or Voice values are trusted.
 */
public interface TrustedConsoleDebugGrantVerifier
{
    TrustedConsoleDebugGrantClaims verify(String authorizationHeader);
}
