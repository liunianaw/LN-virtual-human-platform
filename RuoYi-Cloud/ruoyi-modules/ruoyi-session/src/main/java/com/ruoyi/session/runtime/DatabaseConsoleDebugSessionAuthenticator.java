package com.ruoyi.session.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/** Combines the protected token verifier with current grant/JTI/epoch persistence checks. */
@Component
@ConditionalOnBean(TrustedConsoleDebugGrantVerifier.class)
public class DatabaseConsoleDebugSessionAuthenticator implements ConsoleDebugSessionAuthenticator
{
    private final TrustedConsoleDebugGrantVerifier verifier;
    private final PersistentRuntimeStore store;

    public DatabaseConsoleDebugSessionAuthenticator(TrustedConsoleDebugGrantVerifier verifier, PersistentRuntimeStore store)
    {
        this.verifier = verifier;
        this.store = store;
    }

    @Override
    public RuntimePrincipal authenticate(String authorizationHeader)
    {
        TrustedConsoleDebugGrantClaims claims = verifier.verify(authorizationHeader);
        store.verifyConsoleGrant(claims);
        return claims.principal();
    }
}
