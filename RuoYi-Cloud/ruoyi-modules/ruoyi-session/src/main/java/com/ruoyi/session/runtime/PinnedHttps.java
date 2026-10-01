package com.ruoyi.session.runtime;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.stereotype.Component;

/** A bounded HTTPS request pinned to the IP returned by system after its fresh public-DNS check. */
@Component
public class PinnedHttps
{
    public Response request(URI target, String pinnedAddress, String method, Map<String, String> headers,
        byte[] body, int timeoutMs, long maxResponseBytes, Runnable beforeWrite) throws IOException
    {
        if (!"https".equalsIgnoreCase(target.getScheme()) || target.getHost() == null
            || target.getPort() != -1 && target.getPort() != 443
            || !pinnedAddress.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}|[0-9a-fA-F:]{2,45}")
            || !java.util.Set.of("GET", "POST").contains(method)
            || body.length > 3145728 || timeoutMs < 1000 || timeoutMs > 120000
            || maxResponseBytes < 1 || maxResponseBytes > 10485760)
            throw new IOException("Invalid pinned request");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        Socket plain = new Socket();
        try
        {
            plain.connect(new InetSocketAddress(InetAddress.getByName(pinnedAddress), 443), timeoutMs);
            SSLSocket socket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                .createSocket(plain, target.getHost(), 443, true);
            SSLParameters tls = socket.getSSLParameters();
            tls.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(tls);
            socket.setSoTimeout(remainingMs(deadline));
            socket.startHandshake();
            beforeWrite.run();
            remainingMs(deadline);
            String path = target.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            if (target.getRawQuery() != null) path += "?" + target.getRawQuery();
            StringBuilder request = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(target.getHost()).append("\r\nConnection: close\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n");
            for (Map.Entry<String, String> header : headers.entrySet())
            {
                if (!header.getKey().matches("[A-Za-z0-9-]{1,64}") || header.getValue() == null
                    || header.getValue().chars().anyMatch(c -> c < 32 || c >= 127))
                    throw new IOException("Invalid request header");
                request.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            }
            socket.getOutputStream().write((request + "\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();
            InputStream in = new Deadline(socket, deadline);
            String status = line(in);
            if (!status.matches("HTTP/1\\.[01] [0-9]{3}.*")) throw new IOException("Invalid HTTP status");
            int code = Integer.parseInt(status.substring(9, 12));
            boolean chunked = false;
            long length = -1;
            String type = "";
            boolean ended = false;
            for (int i = 0; i < 80; i++)
            {
                String header = line(in);
                if (header.isEmpty()) { ended = true; break; }
                int colon = header.indexOf(':');
                if (colon < 1) throw new IOException("Invalid HTTP header");
                String name = header.substring(0, colon).toLowerCase(Locale.ROOT);
                String value = header.substring(colon + 1).trim();
                if ("transfer-encoding".equals(name)) chunked = "chunked".equalsIgnoreCase(value);
                if ("content-length".equals(name)) length = Long.parseLong(value);
                if ("content-type".equals(name)) type = value.toLowerCase(Locale.ROOT);
            }
            if (!ended || code != 200 || length > maxResponseBytes) throw new IOException("Upstream HTTP failure");
            InputStream framed = chunked ? new Chunked(in) : length >= 0 ? new Sized(in, length) : in;
            return new Response(socket, new Bounded(framed, maxResponseBytes), type);
        }
        catch (Exception error)
        {
            plain.close();
            if (error instanceof IOException failure) throw failure;
            if (error instanceof RuntimeException failure) throw failure;
            throw new IOException("Pinned HTTPS unavailable", error);
        }
    }

    private static int remainingMs(long deadline) throws SocketTimeoutException
    {
        long left = deadline - System.nanoTime();
        if (left <= 0) throw new SocketTimeoutException("Upstream timed out");
        return (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(left));
    }

    private static final class Deadline extends InputStream
    {
        private final SSLSocket socket;
        private final InputStream in;
        private final long deadline;
        Deadline(SSLSocket socket, long deadline) throws IOException
        { this.socket = socket; this.in = socket.getInputStream(); this.deadline = deadline; }
        @Override public int read() throws IOException
        { socket.setSoTimeout(remainingMs(deadline)); return in.read(); }
        @Override public int read(byte[] b, int off, int len) throws IOException
        { socket.setSoTimeout(remainingMs(deadline)); return in.read(b, off, len); }
        @Override public int available() throws IOException { return in.available(); }
    }

    static String line(InputStream in) throws IOException
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < 8192; i++)
        {
            int c = in.read();
            if (c == -1) throw new EOFException();
            if (c == '\n') return bytes.toString(StandardCharsets.UTF_8).replaceFirst("\\r$", "");
            bytes.write(c);
        }
        throw new IOException("HTTP line too long");
    }

    public record Response(SSLSocket socket, InputStream body, String contentType) implements AutoCloseable
    {
        @Override public void close() throws IOException { socket.close(); }
    }

    private static class Sized extends InputStream
    {
        private final InputStream in;
        private long left;
        Sized(InputStream in, long left) { this.in = in; this.left = left; }
        @Override public int read() throws IOException
        {
            if (left == 0) return -1;
            int c = in.read();
            if (c < 0) throw new EOFException();
            left--;
            return c;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException
        {
            if (left == 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n < 0) throw new EOFException();
            left -= n;
            return n;
        }
        @Override public int available() throws IOException { return (int) Math.min(left, in.available()); }
    }

    private static final class Chunked extends InputStream
    {
        private final InputStream in;
        private long left;
        private boolean done;
        Chunked(InputStream in) { this.in = in; }
        @Override public int read() throws IOException
        {
            if (done) return -1;
            if (left == 0)
            {
                String size = line(in).split(";", 2)[0];
                try { left = Long.parseLong(size, 16); }
                catch (NumberFormatException error) { throw new IOException("Invalid chunk", error); }
                if (left < 0 || left > 10485760) throw new IOException("Invalid chunk size");
                if (left == 0) { done = true; return -1; }
            }
            int c = in.read();
            if (c < 0) throw new EOFException();
            left--;
            if (left == 0 && (in.read() != '\r' || in.read() != '\n')) throw new IOException("Invalid chunk ending");
            return c;
        }
        @Override public int available() throws IOException { return (int) Math.min(left, in.available()); }
    }

    private static final class Bounded extends InputStream
    {
        private final InputStream in;
        private long left;
        Bounded(InputStream in, long left) { this.in = in; this.left = left; }
        @Override public int read() throws IOException
        {
            int c = in.read();
            if (c >= 0 && --left < 0) throw new IOException("Response too large");
            return c;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException
        {
            if (len == 0) return 0;
            int first = read();
            if (first < 0) return -1;
            b[off] = (byte) first;
            int ready = Math.min(len - 1, in.available());
            if (ready <= 0) return 1;
            int n = in.read(b, off + 1, ready);
            if (n < 0) return 1;
            left -= n;
            if (left < 0) throw new IOException("Response too large");
            return 1 + n;
        }
    }
}
