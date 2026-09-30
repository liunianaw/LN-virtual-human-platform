package com.ruoyi.system.developer.webhook.service;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.stereotype.Component;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.relay.service.RelayTarget;

/** DNS is checked on each attempt, then the verified IP is pinned under the original TLS hostname. */
@Component
public class WebhookSender
{
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]{0,251}[A-Za-z0-9]");
    public record Result(Integer httpStatus, String errorCode) { }

    public String validate(String raw)
    {
        URI uri = uri(raw);
        resolve(uri);
        return uri.toASCIIString();
    }

    public Result send(String raw, String eventId, String payload, String secret, int timeoutMs)
    {
        try
        {
            URI uri = uri(raw);
            InetAddress address = resolve(uri);
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            String signature = signature(secret, eventId, timestamp, body);
            try (Socket plain = new Socket())
            {
                plain.connect(new InetSocketAddress(address, 443), timeoutMs);
                plain.setSoTimeout(timeoutMs);
                try (SSLSocket socket = (SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault())
                    .createSocket(plain, uri.getHost(), 443, true))
                {
                    SSLParameters parameters = socket.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    socket.setSSLParameters(parameters);
                    socket.setSoTimeout(timeoutMs);
                    socket.startHandshake();
                    String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
                    if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
                    String headers = "POST " + path + " HTTP/1.1\r\nHost: " + uri.getHost()
                        + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length
                        + "\r\nwebhook-id: " + eventId + "\r\nwebhook-timestamp: " + timestamp
                        + "\r\nwebhook-signature: " + signature + "\r\nConnection: close\r\n\r\n";
                    socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(body);
                    socket.getOutputStream().flush();
                    String statusLine = readLine(socket.getInputStream());
                    if (!statusLine.matches("HTTP/1\\.[01] [0-9]{3}.*")) return new Result(null, "PROTOCOL");
                    int code = Integer.parseInt(statusLine.substring(9, 12));
                    return new Result(code, code >= 200 && code < 300 ? null : code >= 300 && code < 400 ? "REDIRECT" : "HTTP_ERROR");
                }
            }
        }
        catch (ServiceException error) { return new Result(null, "TARGET"); }
        catch (SSLException error) { return new Result(null, "TLS"); }
        catch (Exception error) { return new Result(null, "NETWORK"); }
    }

    static String signature(String secret, String eventId, String timestamp, byte[] body) throws GeneralSecurityException
    {
        if (secret == null || !secret.startsWith("whsec_") || eventId == null || !eventId.matches("[A-Za-z0-9_-]{1,64}"))
            throw new GeneralSecurityException("Invalid webhook signing material");
        byte[] key;
        try { key = Base64.getDecoder().decode(secret.substring(6)); }
        catch (IllegalArgumentException error) { throw new GeneralSecurityException(error); }
        if (key.length != 32) throw new GeneralSecurityException("Invalid webhook key size");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update((eventId + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
        return "v1," + Base64.getEncoder().encodeToString(mac.doFinal(body));
    }

    private static URI uri(String raw)
    {
        try
        {
            if (raw == null || raw.length() > 2048) throw new IllegalArgumentException();
            URI uri = URI.create(raw.trim());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || !HOST.matcher(host).matches()
                || uri.getPort() != -1 && uri.getPort() != 443 || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null || uri.getRawPath() != null && uri.getRawPath().length() > 1024
                || uri.toASCIIString().matches(".*[\\r\\n].*")) throw new IllegalArgumentException();
            String lower = host.toLowerCase(java.util.Locale.ROOT);
            if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.endsWith(".local")
                || lower.endsWith(".internal") || lower.endsWith(".test") || lower.endsWith(".invalid"))
                throw new IllegalArgumentException();
            return uri;
        }
        catch (Exception error) { throw new ServiceException("Webhook 地址无效", 400); }
    }

    private static InetAddress resolve(URI uri)
    {
        try
        {
            InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
            if (addresses.length == 0) throw new IllegalArgumentException();
            for (InetAddress address : addresses) if (!RelayTarget.publicAddress(address)) throw new IllegalArgumentException();
            return addresses[0];
        }
        catch (Exception error) { throw new ServiceException("Webhook 地址或 DNS 不安全", 400); }
    }

    private static String readLine(InputStream in) throws java.io.IOException
    {
        byte[] line = new byte[256];
        int length = 0, value;
        while (length < line.length && (value = in.read()) >= 0)
        {
            if (value == '\n') return new String(line, 0, length, StandardCharsets.US_ASCII).replaceFirst("\\r$", "");
            line[length++] = (byte)value;
        }
        throw new java.io.IOException("Invalid webhook response");
    }
}
