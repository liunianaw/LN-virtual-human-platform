package com.ruoyi.session.runtime;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Validates S locally, then asks system whether its originating console login is still live. */
@Component
public class SystemConsoleDebugGrantVerifier implements TrustedConsoleDebugGrantVerifier
{
    private final RuntimeTokenCodec tokenCodec;
    private final PersistentRuntimeStore store;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public SystemConsoleDebugGrantVerifier(RuntimeTokenCodec tokenCodec, PersistentRuntimeStore store) { this.tokenCodec = tokenCodec; this.store = store; }

    @Override
    public TrustedConsoleDebugGrantClaims verify(String authorizationHeader)
    {
        boolean v2 = authorizationHeader != null && authorizationHeader.startsWith("Bearer ln2.");
        RuntimeTokenCodec.V2Claims newer = v2 ? tokenCodec.decodeV2(authorizationHeader) : null;
        RuntimeTokenCodec.Claims claims = v2 ? null : tokenCodec.decode(authorizationHeader);
        PersistentRuntimeStore.ConsoleGrantMetadata metadata = v2 ? store.consoleGrantMetadata(newer) : null;
        String issuer = v2 ? metadata.issuerConsoleRef() : claims.issuerConsoleRef();
        long accountId = v2 ? newer.accountId() : claims.accountId();
        long applicationId = v2 ? newer.applicationId() : claims.applicationId();
        long sessionId = v2 ? newer.sessionId() : claims.sessionId();
        long configId = v2 ? newer.configVersionId() : claims.configVersionId();
        VoiceRuntimeBinding voice = v2 ? metadata.voice() : claims.voice();
        String tokenId = v2 ? newer.tokenId() : claims.tokenId();
        verifyIssuer(issuer, accountId);
        RuntimePrincipal principal = new RuntimePrincipal(accountId, applicationId, sessionId,
                configId, java.util.Set.of("session:read", "avatar:read", "speak:write"), voice);
        return new TrustedConsoleDebugGrantClaims(tokenId, accountId, applicationId, sessionId,
                configId, v2 ? metadata.accountEpoch() : 1L, v2 ? metadata.applicationEpoch() : configId,
                v2 ? metadata.principalEpoch() : 1L, v2 ? metadata.sessionEpoch() : 1L, issuer, principal);
    }

    public void verifyIssuer(String issuer, long accountId)
    {
        if (issuer == null || !issuer.matches("[0-9a-f]{64}") || accountId <= 0)
            throw new RuntimeProblem(HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED", "Invalid DEBUG issuer.");
        String bearer = System.getenv("LN_SESSION_TO_SYSTEM_INTERNAL_BEARER");
        String baseUrl = System.getenv("LN_SESSION_TO_SYSTEM_URL");
        if (bearer == null || bearer.isBlank() || baseUrl == null || baseUrl.isBlank())
            throw new RuntimeProblem(HttpStatus.SERVICE_UNAVAILABLE, "DEBUG_AUTH_NOT_CONFIGURED", "Platform DEBUG authorization is not configured.");
        String body = "{\"issuerConsoleRef\":\"" + issuer + "\",\"accountId\":" + accountId + "}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/v1/console-debug-grants/verify"))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try
        {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
                throw new RuntimeProblem(HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED", "The Console DEBUG Session Token is no longer valid.");
        }
        catch (RuntimeProblem e) { throw e; }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw unavailable(e);
        }
        catch (IOException | IllegalArgumentException e) { throw unavailable(e); }
    }

    private static RuntimeProblem unavailable(Exception cause)
    {
        return new RuntimeProblem(HttpStatus.SERVICE_UNAVAILABLE, "DEBUG_AUTH_UNAVAILABLE", "Platform DEBUG authorization cannot be verified.");
    }
}
