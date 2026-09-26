package com.ruoyi.session.business;

import com.ruoyi.session.runtime.RuntimeProblem;
import com.ruoyi.session.runtime.RuntimeTokenCodec;
import com.ruoyi.session.runtime.SpeakOnlyRuntimeService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class BusinessSessionService
{
    private final BusinessSystemClient system;
    private final BusinessSessionStore store;
    private final RuntimeTokenCodec tokens;
    private final SpeakOnlyRuntimeService runtime;
    private final BusinessCredentialStore credentials;
    private final com.ruoyi.session.runtime.RuntimeEventPublisher events;

    public BusinessSessionService(BusinessSystemClient system, BusinessSessionStore store, RuntimeTokenCodec tokens,
        SpeakOnlyRuntimeService runtime, BusinessCredentialStore credentials,
        com.ruoyi.session.runtime.RuntimeEventPublisher events)
    { this.system = system; this.store = store; this.tokens = tokens; this.runtime = runtime;
      this.credentials = credentials; this.events = events; }

    public View create(String secret, String externalUserId, String idempotencyKey, String queryCredential)
    {
        validUser(externalUserId);
        validKey(idempotencyKey);
        BusinessSystemClient.Snapshot snapshot = system.authenticate(secret, "sessions:create", null);
        byte[] requestHash = credentials.fingerprint(queryCredential);
        BusinessSessionStore.Preparation prepared = store.prepare(snapshot.accountId(), snapshot.applicationId(), snapshot.configId(),
            externalUserId, idempotencyKey, requestHash);
        BusinessSessionStore.Session row = prepared.session();
        boolean creating = "CREATING".equals(row.status());
        if (creating) resumeCreate(row);
        else system.check(row.accountId(), row.applicationId(), row.configId());
        if (creating && queryCredential != null)
        {
            try { credentials.put(row.id(), queryCredential, row.expiresAt()); }
            catch (RuntimeProblem error) { end(store.find(row.id())); throw error; }
        }
        return view(store.find(row.id()));
    }

    public View read(String secret, long sessionId, String externalUserId)
    {
        validUser(externalUserId);
        BusinessSystemClient.Snapshot key = system.authenticate(secret, "sessions:read", null);
        BusinessSessionStore.Session row = store.owned(key.accountId(), key.applicationId(), sessionId, externalUserId);
        if ("DELETED".equals(row.status()) || "FAILED".equals(row.status())) throw problem(HttpStatus.GONE, "SESSION_ENDED");
        if (!row.expiresAt().isAfter(Instant.now()))
        {
            end(row);
            throw problem(HttpStatus.GONE, "SESSION_ENDED");
        }
        system.check(row.accountId(), row.applicationId(), row.configId());
        return view(row);
    }

    public BusinessSessionStore.IssuedGrant mint(String secret, long sessionId, String externalUserId,
        String idempotencyKey, List<String> requestedScopes)
    {
        validUser(externalUserId);
        validKey(idempotencyKey);
        BusinessSystemClient.Snapshot key = system.authenticate(secret, "sessions:grant", null);
        BusinessSessionStore.Session row = store.owned(key.accountId(), key.applicationId(), sessionId, externalUserId);
        BusinessSystemClient.Snapshot snapshot = system.authenticate(secret, "sessions:grant", row.configId());
        system.check(row.accountId(), row.applicationId(), row.configId());
        if (requestedScopes != null && requestedScopes.stream().anyMatch(java.util.Objects::isNull))
            throw problem(HttpStatus.BAD_REQUEST, "SCOPE_INVALID");
        List<String> scopes = requestedScopes == null || requestedScopes.isEmpty() ? snapshot.allowedScopes() : List.copyOf(requestedScopes);
        if (scopes.isEmpty() || scopes.size() != Set.copyOf(scopes).size() || !snapshot.allowedScopes().containsAll(scopes))
            throw problem(HttpStatus.BAD_REQUEST, "SCOPE_INVALID");
        return store.mint(row, snapshot, idempotencyKey, scopes, tokens);
    }

    public View close(String secret, long sessionId, String externalUserId, String idempotencyKey, String ifMatch)
    {
        validUser(externalUserId);
        if (idempotencyKey == null || idempotencyKey.isBlank()) validRevision(ifMatch);
        else validKey(idempotencyKey);
        BusinessSystemClient.Snapshot key = system.authenticate(secret, "sessions:end", null);
        BusinessSessionStore.Session row = store.owned(key.accountId(), key.applicationId(), sessionId, externalUserId);
        if (ifMatch != null && !ifMatch.isBlank() && !ifMatch.equals(Long.toString(row.revision())))
            throw problem(HttpStatus.PRECONDITION_FAILED, "REVISION_MISMATCH");
        if (idempotencyKey != null && !idempotencyKey.isBlank())
        {
            BusinessSessionStore.ActionClaim claim = store.claimAction(row.accountId(), row.id(), "end", idempotencyKey, externalUserId);
            if (claim.completed()) return view(store.find(sessionId));
        }
        end(row);
        if (idempotencyKey != null && !idempotencyKey.isBlank())
            store.finishAction(row.accountId(), row.id(), "end", idempotencyKey, 1);
        return view(store.find(sessionId));
    }

    public int revoke(String secret, long sessionId, String externalUserId, String idempotencyKey, boolean allSessions)
    {
        validUser(externalUserId);
        validKey(idempotencyKey);
        BusinessSystemClient.Snapshot key = system.authenticate(secret, "sessions:revoke", null);
        BusinessSessionStore.Session row = store.owned(key.accountId(), key.applicationId(), sessionId, externalUserId);
        BusinessSessionStore.ActionClaim claim = store.claimAction(row.accountId(), row.id(), "revoke", idempotencyKey, externalUserId + ":" + allSessions);
        if (claim.completed()) return claim.count();
        if (!allSessions)
        {
            end(row);
            store.finishAction(row.accountId(), row.id(), "revoke", idempotencyKey, 1);
            return 1;
        }
        List<BusinessSessionStore.Session> sessions = store.beginUserClose(row.accountId(), row.applicationId(), externalUserId);
        for (BusinessSessionStore.Session session : sessions) end(session);
        store.finishAction(row.accountId(), row.id(), "revoke", idempotencyKey, sessions.size());
        return sessions.size();
    }

    /** Called by DEV-07 before every new runtime command, ticket, or media read. */
    public VerifiedRuntime verifyRuntime(String authorization)
    {
        RuntimeTokenCodec.V2Claims claims = tokens.decodeV2(authorization);
        if (!"BUSINESS_KEY".equals(claims.source())) throw problem(HttpStatus.UNAUTHORIZED, "TOKEN_SOURCE_INVALID");
        BusinessSessionStore.VerifiedGrant grant = store.verify(claims);
        BusinessSystemClient.Snapshot current = system.check(claims.accountId(), claims.applicationId(), claims.configVersionId());
        if (grant.applicationEpoch() != current.applicationEpoch()) throw problem(HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED");
        List<String> effectiveScopes = grant.scopes().stream().filter(current.allowedScopes()::contains).toList();
        return new VerifiedRuntime(claims.accountId(), claims.applicationId(), claims.sessionId(),
            claims.configVersionId(), effectiveScopes);
    }

    /** Only successful user actions extend idle time; heartbeats and token issuance do not. */
    public void successfulActivity(long sessionId) { store.touchSuccessfulActivity(sessionId); }

    private void resumeCreate(BusinessSessionStore.Session row)
    {
        try
        {
            system.reserve(row.accountId(), row.applicationId(), row.configId(), row.id(), row.referenceOperationId());
            system.confirm(row.accountId(), row.applicationId(), row.configId(), row.id(), row.referenceOperationId());
            store.activate(row.id());
        }
        catch (RuntimeProblem error)
        {
            if (error.status().is4xxClientError())
            {
                store.failCreate(row.id());
                system.release(row.accountId(), row.applicationId(), row.configId(), row.id(), row.referenceOperationId());
                store.finishFailed(row.id());
            }
            throw error;
        }
    }

    private void end(BusinessSessionStore.Session row)
    {
        BusinessSessionStore.Session closing = store.beginClose(row);
        if ("DELETED".equals(closing.status())) return;
        runtime.revokeSession(row.id());
        events.revokeSession(row.id());
        credentials.remove(row.id());
        system.release(row.accountId(), row.applicationId(), row.configId(), row.id(), row.referenceOperationId());
        store.finishClose(row.id());
    }

    @Scheduled(fixedDelayString = "${LN_BUSINESS_SESSION_RECOVERY_MS:30000}")
    public void recover()
    {
        for (Long id : store.cleanupCandidates())
        {
            try
            {
                BusinessSessionStore.Session row = store.find(id);
                if (row == null) continue;
                if ("CREATING".equals(row.status())) resumeCreate(row);
                else if ("DELETING".equals(row.status())) end(row);
                else if ("FAILED".equals(row.status()))
                {
                    system.release(row.accountId(), row.applicationId(), row.configId(), row.id(), row.referenceOperationId());
                    store.finishFailed(row.id());
                }
                else if ("ACTIVE".equals(row.status()) && !row.expiresAt().isAfter(Instant.now())) end(row);
            }
            catch (RuntimeException ignored) { /* Durable state remains for the next retry. */ }
        }
    }

    private static View view(BusinessSessionStore.Session row)
    {
        return new View(Long.toString(row.id()), Long.toString(row.applicationId()), Long.toString(row.configId()),
            row.status(), row.createdAt(), row.lastActivityAt(), row.expiresAt(), Long.toString(row.revision()));
    }
    private static void validUser(String user)
    {
        if (user == null || user.isBlank() || user.length() > 191 || user.codePoints().anyMatch(cp -> Character.isISOControl(cp)))
            throw problem(HttpStatus.BAD_REQUEST, "EXTERNAL_USER_INVALID");
    }
    private static void validKey(String key)
    {
        if (key == null || !key.matches("[\\x21-\\x7e]{1,64}")) throw problem(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED");
    }
    private static void validRevision(String value)
    {
        if (value == null || !value.matches("[1-9][0-9]*")) throw problem(HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_REQUIRED");
    }
    private static RuntimeProblem problem(HttpStatus status, String code) { return new RuntimeProblem(status, code, code); }

    public record View(String sessionId, String applicationId, String configVersionId, String status,
        Instant createdAt, Instant lastActivityAt, Instant expiresAt, String revision) { }
    public record VerifiedRuntime(long accountId, long applicationId, long sessionId, long configVersionId, List<String> scopes) { }
}
