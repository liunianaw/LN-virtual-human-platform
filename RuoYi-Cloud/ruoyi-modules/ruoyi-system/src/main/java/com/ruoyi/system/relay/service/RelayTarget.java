package com.ruoyi.system.relay.service;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import com.ruoyi.common.core.exception.ServiceException;

/** Validate DNS at configuration and again immediately before each connection. */
@Component
public class RelayTarget
{
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]{0,251}[A-Za-z0-9]");
    private static final Pattern PATH = Pattern.compile("(?:/[A-Za-z0-9_-]+)*");

    public URI validate(String raw)
    {
        try
        {
            URI uri = URI.create(raw == null ? "" : raw.trim());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || !HOST.matcher(host).matches()
                || uri.getPort() != -1 && uri.getPort() != 443 || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !PATH.matcher(uri.getRawPath()).matches() || raw.length() > 2048)
                throw invalid();
            String lower = host.toLowerCase(Locale.ROOT);
            if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.endsWith(".local")
                || lower.endsWith(".internal") || lower.endsWith(".test") || lower.endsWith(".invalid")) throw invalid();
            return uri;
        }
        catch (ServiceException error) { throw error; }
        catch (Exception error) { throw invalid(); }
    }

    public InetAddress resolve(URI uri)
    {
        try
        {
            InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
            if (addresses.length == 0) throw invalid();
            for (InetAddress address : addresses) if (!publicAddress(address)) throw invalid();
            return addresses[0];
        }
        catch (ServiceException error) { throw error; }
        catch (Exception error) { throw invalid(); }
    }

    static boolean publicAddress(InetAddress address)
    {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address)
        {
            int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255;
            return a > 0 && a < 224 && a != 10 && a != 127
                && !(a == 100 && b >= 64 && b <= 127)
                && !(a == 169 && b == 254) && !(a == 172 && b >= 16 && b <= 31)
                && !(a == 192 && (b == 0 || b == 168 || b == 88 && c == 99))
                && !(a == 198 && (b == 18 || b == 19 || b == 51 && c == 100))
                && !(a == 203 && b == 0 && c == 113);
        }
        if (address instanceof Inet6Address)
        {
            // Global unicast only; deny documentation and tunnel prefixes that can hide private IPv4 targets.
            return (bytes[0] & 0xe0) == 0x20
                && !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && (bytes[3] & 0xff) == 0xb8)
                && !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0 && bytes[3] == 0)
                && !(bytes[0] == 0x20 && bytes[1] == 0x02);
        }
        return false;
    }

    private static ServiceException invalid() { return new ServiceException("Relay 目标地址或 DNS 不安全", 400); }
}
