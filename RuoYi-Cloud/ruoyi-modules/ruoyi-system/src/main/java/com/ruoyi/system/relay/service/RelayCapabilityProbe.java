package com.ruoyi.system.relay.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.stereotype.Component;
import com.ruoyi.common.core.exception.ServiceException;

/** No-cost LN_RELAY/1 capability handshake over a DNS-pinned, verified HTTPS socket. */
@Component
public class RelayCapabilityProbe
{
    public record Result(boolean ok, String errorCode) { }
    private final RelayTarget targets;
    private final ObjectMapper json;
    public RelayCapabilityProbe(RelayTarget targets, ObjectMapper json) { this.targets = targets; this.json = json; }

    public Result check(String baseUrl, String token, Map<String, Boolean> selected)
    {
        try
        {
            URI uri = targets.validate(baseUrl);
            InetAddress address = targets.resolve(uri);
            try (Socket plain = new Socket())
            {
                plain.connect(new InetSocketAddress(address, 443), 5000);
                plain.setSoTimeout(5000);
                try (SSLSocket socket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(plain, uri.getHost(), 443, true))
                {
                    SSLParameters parameters = socket.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    socket.setSSLParameters(parameters);
                    socket.setSoTimeout(5000);
                    socket.startHandshake();
                    String path = (uri.getRawPath() == null ? "" : uri.getRawPath()) + "/capabilities";
                    String request = "GET " + path + " HTTP/1.1\r\nHost: " + uri.getHost()
                        + "\r\nAuthorization: Bearer " + token + "\r\nX-LN-Protocol-Version: 1"
                        + "\r\nX-Request-Id: " + UUID.randomUUID() + "\r\nAccept: application/json\r\nConnection: close\r\n\r\n";
                    socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    InputStream in = socket.getInputStream();
                    String status = line(in);
                    if (!status.matches("HTTP/1\\.[01] [0-9]{3}.*")) return new Result(false, "PROTOCOL");
                    int code = Integer.parseInt(status.substring(9, 12));
                    boolean chunked = false, contentType = false;
                    int length = -1;
                    boolean headersEnded = false;
                    for (int i = 0; i < 80; i++)
                    {
                        String header = line(in);
                        if (header.isEmpty()) { headersEnded = true; break; }
                        int colon = header.indexOf(':');
                        if (colon <= 0) return new Result(false, "PROTOCOL");
                        String name = header.substring(0, colon).trim().toLowerCase();
                        String value = header.substring(colon + 1).trim().toLowerCase();
                        if (name.equals("transfer-encoding"))
                        {
                            if (!value.equals("chunked")) return new Result(false, "PROTOCOL");
                            chunked = true;
                        }
                        if (name.equals("content-type")) contentType = value.startsWith("application/json") || value.contains("+json");
                        if (name.equals("content-length")) length = Integer.parseInt(value);
                    }
                    if (!headersEnded) return new Result(false, "PROTOCOL");
                    if (code == 401 || code == 403) return new Result(false, "AUTH");
                    if (code >= 300 && code < 400) return new Result(false, "REDIRECT");
                    if (code != 200) return new Result(false, "HTTP");
                    if (!contentType) return new Result(false, "PROTOCOL");
                    return validateHandshake(body(in, length, chunked, 65536), selected);
                }
            }
        }
        catch (ServiceException error) { return new Result(false, "TARGET"); }
        catch (SSLException error) { return new Result(false, "TLS"); }
        catch (ProtocolFailure error) { return new Result(false, "PROTOCOL"); }
        catch (NumberFormatException error) { return new Result(false, "PROTOCOL"); }
        catch (SocketTimeoutException error) { return new Result(false, "NETWORK"); }
        catch (Exception error) { return new Result(false, "NETWORK"); }
    }

    Result validateHandshake(byte[] body, Map<String, Boolean> selected)
    {
        try
        {
            JsonNode root = json.readTree(body);
            JsonNode data = root.has("data") ? root.path("data") : root;
            if (!"LN_RELAY".equals(data.path("protocol").asText()) || !"1".equals(data.path("protocolVersion").asText()))
                return new Result(false, "PROTOCOL");
            JsonNode capabilities = data.path("capabilities");
            for (String capability : new String[] { "llm", "asr", "cancel" })
                if (Boolean.TRUE.equals(selected.get(capability)) && !capabilities.path(capability).asBoolean(false))
                    return new Result(false, "CAPABILITY");
            if (Boolean.TRUE.equals(selected.get("asr")))
            {
                JsonNode mimeTypes = data.path("asr").path("inputMimeTypes");
                if (!mimeTypes.isArray() || mimeTypes.isEmpty()) return new Result(false, "PROTOCOL");
            }
            return new Result(true, null);
        }
        catch (Exception error) { return new Result(false, "PROTOCOL"); }
    }

    private static String line(InputStream in) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < 4096; i++)
        {
            int value = in.read();
            if (value < 0) throw new ProtocolFailure();
            if (value == '\n') return out.toString(StandardCharsets.US_ASCII).replaceFirst("\\r$", "");
            out.write(value);
        }
        throw new ProtocolFailure();
    }

    private static byte[] body(InputStream in, int length, boolean chunked, int max) throws IOException
    {
        if (length > max) throw new ProtocolFailure();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (chunked)
        {
            while (true)
            {
                String chunk = line(in).split(";", 2)[0];
                int size = Integer.parseInt(chunk, 16);
                if (size < 0 || size > max - out.size()) throw new ProtocolFailure();
                if (size == 0) return out.toByteArray();
                byte[] part = in.readNBytes(size);
                if (part.length != size || in.read() != '\r' || in.read() != '\n') throw new ProtocolFailure();
                out.write(part);
            }
        }
        byte[] bytes = in.readNBytes(length < 0 ? max + 1 : length);
        if (bytes.length > max || length >= 0 && bytes.length != length) throw new ProtocolFailure();
        return bytes;
    }
    private static class ProtocolFailure extends IOException { }
}
