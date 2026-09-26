package com.ruoyi.session.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.session.business.BusinessSessionService;
import com.ruoyi.session.business.BusinessSystemClient;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** One current grant and one fixed Session/config are the authority for HTTP and WSS. */
@Service
public class RuntimeAuthorization
{
    private final RuntimeTokenCodec tokens;
    private final TrustedConsoleDebugGrantVerifier debug;
    private final PersistentRuntimeStore store;
    private final BusinessSessionService business;
    private final BusinessSystemClient system;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public RuntimeAuthorization(RuntimeTokenCodec tokens, TrustedConsoleDebugGrantVerifier debug,
        PersistentRuntimeStore store, BusinessSessionService business, BusinessSystemClient system,
        JdbcTemplate jdbc, ObjectMapper json)
    {
        this.tokens = tokens; this.debug = debug; this.store = store; this.business = business;
        this.system = system; this.jdbc = jdbc; this.json = json;
    }

    public Grant authenticate(String authorization)
    {
        String tokenId;
        VoiceRuntimeBinding legacyVoice = null;
        if (authorization != null && authorization.startsWith("Bearer ln2."))
        {
            RuntimeTokenCodec.V2Claims claims = tokens.decodeV2(authorization);
            tokenId = claims.tokenId();
            if ("BUSINESS_KEY".equals(claims.source())) business.verifyRuntime(authorization);
            else if ("CONSOLE_DEBUG".equals(claims.source())) store.verifyConsoleGrant(debug.verify(authorization));
            else throw rejected();
        }
        else
        {
            TrustedConsoleDebugGrantClaims claims = debug.verify(authorization);
            store.verifyConsoleGrant(claims);
            tokenId = claims.tokenId();
            legacyVoice = claims.principal().voice();
            try
            {
                jdbc.update("update s_session_grant set runtime_binding=cast(? as json) where token_id=? " +
                    "and grant_source='CONSOLE_DEBUG' and runtime_binding is null",
                    json.writeValueAsString(legacyVoice), tokenId);
            }
            catch (Exception error) { throw rejected(); }
        }
        Long id = jdbc.query("select id from s_session_grant where token_id=?",
            rs -> rs.next() ? rs.getLong(1) : null, tokenId);
        if (id == null) throw rejected();
        Grant grant = verify(id);
        if (!grant.tokenId().equals(tokenId) || (legacyVoice != null && !legacyVoice.equals(grant.principal().voice())))
            throw rejected();
        return grant;
    }

    /** Rechecked for ticket consumption and each new WSS command; no token plaintext is retained. */
    public Grant verify(long grantId)
    {
        Row row = jdbc.query("select g.id,g.token_id,g.grant_source,g.account_id,g.application_id,g.session_id,s.app_config_id," +
                "g.principal_id,g.status,g.expires_at,s.status,s.expires_at,p.status,g.principal_epoch,p.auth_epoch," +
                "g.session_epoch,s.auth_epoch,g.application_epoch,g.scopes,g.runtime_binding,lower(hex(g.issuer_console_ref)) " +
                "from s_session_grant g join s_session s on s.id=g.session_id join s_principal p on p.id=g.principal_id " +
                "where g.id=? and s.principal_id=g.principal_id and g.account_id=s.account_id " +
                "and g.application_id=s.application_id and p.account_id=s.account_id and p.application_id=s.application_id " +
                "and ((g.grant_source='BUSINESS_KEY' and p.principal_type='BUSINESS') " +
                "or (g.grant_source='CONSOLE_DEBUG' and p.principal_type='DEBUG'))",
            rs -> rs.next() ? new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5),
                rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getString(9), rs.getTimestamp(10).toInstant(),
                rs.getString(11), rs.getTimestamp(12).toInstant(), rs.getString(13), rs.getLong(14), rs.getLong(15),
                rs.getLong(16), rs.getLong(17), rs.getLong(18), rs.getString(19), rs.getString(20), rs.getString(21)) : null,
            grantId);
        Instant now = Instant.now();
        if (row == null || !"ACTIVE".equals(row.grantStatus()) || !"ACTIVE".equals(row.sessionStatus())
            || !"ACTIVE".equals(row.principalStatus()) || !row.grantExpires().isAfter(now)
            || !row.sessionExpires().isAfter(now) || row.principalEpoch() != row.currentPrincipalEpoch()
            || row.sessionEpoch() != row.currentSessionEpoch()) throw rejected();
        try
        {
            List<String> scopes = json.readValue(row.scopes(), new TypeReference<List<String>>() {});
            VoiceRuntimeBinding voice;
            if ("BUSINESS_KEY".equals(row.source()))
            {
                BusinessSystemClient.Snapshot current = system.check(row.accountId(), row.applicationId(), row.configId());
                if (row.applicationEpoch() != current.applicationEpoch()) throw rejected();
                scopes = scopes.stream().filter(current.allowedScopes()::contains).toList();
                voice = new VoiceRuntimeBinding(current.voiceVersionId(), TtsProviderKind.OFFICIAL,
                    current.providerVoiceRef(), null, current.officialServiceId(), current.officialServiceRevision());
            }
            else if ("CONSOLE_DEBUG".equals(row.source()))
            {
                if (!(debug instanceof SystemConsoleDebugGrantVerifier verifier)) throw rejected();
                verifier.verifyIssuer(row.issuer(), row.accountId());
                voice = json.readValue(row.runtimeBinding(), VoiceRuntimeBinding.class);
            }
            else throw rejected();
            if (!scopes.contains("session:read")) throw rejected();
            return new Grant(new RuntimePrincipal(row.accountId(), row.applicationId(), row.sessionId(), row.configId(),
                Set.copyOf(scopes), voice), row.id(), row.tokenId(), row.source(), row.principalId(), row.grantExpires());
        }
        catch (RuntimeProblem error) { throw error; }
        catch (Exception error) { throw rejected(); }
    }

    private static RuntimeProblem rejected()
    { return new RuntimeProblem(HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED", "Runtime grant is no longer valid."); }

    public record Grant(RuntimePrincipal principal, long id, String tokenId, String source, long principalId, Instant expiresAt) { }
    private record Row(long id, String tokenId, String source, long accountId, long applicationId, long sessionId,
        long configId, long principalId, String grantStatus, Instant grantExpires, String sessionStatus,
        Instant sessionExpires, String principalStatus, long principalEpoch, long currentPrincipalEpoch,
        long sessionEpoch, long currentSessionEpoch, long applicationEpoch, String scopes, String runtimeBinding,
        String issuer) { }
}
