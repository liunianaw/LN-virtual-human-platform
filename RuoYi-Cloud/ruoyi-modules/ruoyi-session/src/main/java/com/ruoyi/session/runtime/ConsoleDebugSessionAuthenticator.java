package com.ruoyi.session.runtime;

/**
 * Integration seam for validating a Console DEBUG Session Token.  Its future
 * implementation must validate the trusted console-login reference, persistent
 * grant/JTI, ownership, Session epoch and fixed SPEAK_ONLY configuration.
 */
public interface ConsoleDebugSessionAuthenticator
{
    RuntimePrincipal authenticate(String authorizationHeader);
}
