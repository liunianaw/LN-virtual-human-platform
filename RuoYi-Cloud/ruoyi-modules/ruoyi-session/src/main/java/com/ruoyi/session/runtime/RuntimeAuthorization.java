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

/** A browser grant is bound to one immutable Session snapshot and rechecked on every action. */
@Service
public class RuntimeAuthorization
{
    private final RuntimeTokenCodec tokens;
    private final BusinessSessionService business;
    private final BusinessSystemClient system;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public RuntimeAuthorization(RuntimeTokenCodec tokens, BusinessSessionService business,
        BusinessSystemClient system, JdbcTemplate jdbc, ObjectMapper json)
    { this.tokens = tokens; this.business = business; this.system = system; this.jdbc = jdbc; this.json = json; }

    public Grant authenticate(String authorization)
    {
        RuntimeTokenCodec.V2Claims claims = tokens.decodeV2(authorization);
        if (!"BUSINESS_KEY".equals(claims.source())) throw rejected();
        business.verifyRuntime(authorization);
        Long id = jdbc.query("select id from s_session_grant where token_id=? and grant_source='BUSINESS_KEY'",
            rs -> rs.next() ? rs.getLong(1) : null, claims.tokenId());
        if (id == null) throw rejected();
        Grant grant = verify(id);
        if (!grant.tokenId().equals(claims.tokenId())) throw rejected();
        return grant;
    }

    public Grant verify(long grantId)
    {
        Row row = jdbc.query("select g.id,g.token_id,g.grant_source,g.account_id,g.application_id,g.session_id," +
                "s.session_snapshot_id,g.principal_id,g.status,g.expires_at,s.status,s.expires_at,p.status," +
                "g.principal_epoch,p.auth_epoch,g.session_epoch,s.auth_epoch,g.application_epoch,g.scopes," +
                "snap.voice_version_id,snap.provider_voice_ref,snap.official_service_id,snap.official_service_revision,snap.voice_binding " +
                "from s_session_grant g join s_session s on s.id=g.session_id " +
                "join s_principal p on p.id=g.principal_id join s_session_snapshot snap on snap.id=s.session_snapshot_id " +
                "where g.id=? and g.grant_source='BUSINESS_KEY' and p.principal_type='BUSINESS' " +
                "and s.principal_id=g.principal_id and g.account_id=s.account_id and g.application_id=s.application_id",
            rs -> rs.next() ? new Row(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getLong(4),
                rs.getLong(5),rs.getLong(6),rs.getLong(7),rs.getLong(8),rs.getString(9),
                rs.getTimestamp(10).toInstant(),rs.getString(11),rs.getTimestamp(12).toInstant(),
                rs.getString(13),rs.getLong(14),rs.getLong(15),rs.getLong(16),rs.getLong(17),
                rs.getLong(18),rs.getString(19),rs.getLong(20),rs.getString(21),rs.getLong(22),rs.getLong(23),rs.getString(24)) : null,
            grantId);
        Instant now = Instant.now();
        if (row == null || !"ACTIVE".equals(row.grantStatus()) || !"ACTIVE".equals(row.sessionStatus())
            || !"ACTIVE".equals(row.principalStatus()) || !row.grantExpires().isAfter(now)
            || !row.sessionExpires().isAfter(now) || row.principalEpoch()!=row.currentPrincipalEpoch()
            || row.sessionEpoch()!=row.currentSessionEpoch()) throw rejected();
        try
        {
            List<String> scopes = json.readValue(row.scopes(), new TypeReference<List<String>>() {});
            BusinessSystemClient.Snapshot current = system.check(row.accountId(), row.applicationId(), row.sessionId());
            if (row.applicationEpoch()!=current.applicationEpoch()) throw rejected();
            scopes = scopes.stream().filter(current.allowedScopes()::contains).toList();
            if (!scopes.contains("session:read")) throw rejected();
            var execution = row.voiceBinding()==null?null:
                json.readValue(row.voiceBinding(),com.ruoyi.common.voice.VoiceBinding.class);
            VoiceRuntimeBinding voice = new VoiceRuntimeBinding(row.voiceVersionId(), TtsProviderKind.OFFICIAL,
                execution==null?row.providerVoiceRef():execution.providerVoiceRef(),
                row.officialServiceId(), row.officialServiceRevision(), execution);
            return new Grant(new RuntimePrincipal(row.accountId(),row.applicationId(),row.sessionId(),
                row.snapshotId(),Set.copyOf(scopes),voice),row.id(),row.tokenId(),row.source(),
                row.principalId(),row.grantExpires());
        }
        catch (RuntimeProblem error) { throw error; }
        catch (Exception error) { throw rejected(); }
    }
    private static RuntimeProblem rejected()
    { return new RuntimeProblem(HttpStatus.UNAUTHORIZED,"TOKEN_REVOKED","Runtime grant is no longer valid."); }

    public record Grant(RuntimePrincipal principal,long id,String tokenId,String source,long principalId,Instant expiresAt) { }
    private record Row(long id,String tokenId,String source,long accountId,long applicationId,long sessionId,
        long snapshotId,long principalId,String grantStatus,Instant grantExpires,String sessionStatus,
        Instant sessionExpires,String principalStatus,long principalEpoch,long currentPrincipalEpoch,
        long sessionEpoch,long currentSessionEpoch,long applicationEpoch,String scopes,long voiceVersionId,
        String providerVoiceRef,long officialServiceId,long officialServiceRevision,String voiceBinding) { }
}
