package com.ruoyi.session.runtime;

import java.util.Set;

import org.springframework.http.HttpStatus;

/**
 * Trusted output of the BUSINESS Session token verifier.  Browser request bodies
 * never carry these ownership fields or a Voice binding.
 */
public record RuntimePrincipal(long accountId, long applicationId, long sessionId, long snapshotId,
        Set<String> scopes, VoiceRuntimeBinding voice)
{
    public RuntimePrincipal
    {
        if (accountId <= 0 || applicationId <= 0 || sessionId <= 0 || snapshotId <= 0)
        {
            throw new IllegalArgumentException("Runtime principal identifiers must be positive");
        }
        scopes = Set.copyOf(scopes);
        if (voice == null)
        {
            throw new IllegalArgumentException("Speech runtime requires a Voice binding");
        }
    }

    public void requireSpeakScope()
    {
        if (!scopes.contains("speak:write"))
        {
            throw new RuntimeProblem(HttpStatus.FORBIDDEN, "SCOPE_DENIED", "The Session Token does not permit speech.");
        }
    }

    public void requireAvatarScope()
    {
        if (!scopes.contains("avatar:read")) throw new RuntimeProblem(HttpStatus.FORBIDDEN, "SCOPE_DENIED", "The Session Token does not permit Avatar access.");
    }
}
