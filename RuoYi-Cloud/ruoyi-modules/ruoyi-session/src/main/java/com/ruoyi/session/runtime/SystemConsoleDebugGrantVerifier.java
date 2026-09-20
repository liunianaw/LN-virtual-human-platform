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
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public SystemConsoleDebugGrantVerifier(RuntimeTokenCodec tokenCodec) { this.tokenCodec = tokenCodec; }

    @Override
    public TrustedConsoleDebugGrantClaims verify(String authorizationHeader)
    {
        RuntimeTokenCodec.Claims claims = tokenCodec.decode(authorizationHeader);
        String bearer = System.getenv("LN_SESSION_TO_SYSTEM_INTERNAL_BEARER");
        String baseUrl = System.getenv("LN_SESSION_TO_SYSTEM_URL");
        if (bearer == null || bearer.isBlank() || baseUrl == null || baseUrl.isBlank())
            throw new RuntimeProblem(HttpStatus.SERVICE_UNAVAILABLE, "DEBUG_AUTH_NOT_CONFIGURED", "Platform DEBUG authorization is not configured.");
        String body = "{\"issuerConsoleRef\":\"" + claims.issuerConsoleRef() + "\",\"accountId\":" + claims.accountId() + "}";
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
        RuntimePrincipal principal = new RuntimePrincipal(claims.accountId(), claims.applicationId(), claims.sessionId(),
                claims.configVersionId(), java.util.Set.of("speak:write"), claims.voice());
        return new TrustedConsoleDebugGrantClaims(claims.tokenId(), claims.accountId(), claims.applicationId(), claims.sessionId(),
                claims.configVersionId(), 1L, claims.configVersionId(), 1L, 1L, claims.issuerConsoleRef(), principal);
    }

    private static RuntimeProblem unavailable(Exception cause)
    {
        return new RuntimeProblem(HttpStatus.SERVICE_UNAVAILABLE, "DEBUG_AUTH_UNAVAILABLE", "Platform DEBUG authorization cannot be verified.");
    }
}
