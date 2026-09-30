package com.ruoyi.system.developer.openapi;

import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.system.developer.access.service.IAccessKeyService;

/** The external principal is kept in the request, never in console identity headers. */
@Component
public class ManagementKeyFilter extends OncePerRequestFilter
{
    public static final String PRINCIPAL = ManagementKeyFilter.class.getName() + ".principal";
    private final IAccessKeyService keys;
    public ManagementKeyFilter(IAccessKeyService keys) { this.keys = keys; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request)
    { return !request.getRequestURI().startsWith("/openapi/v1/management/"); }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException
    {
        try
        {
            String scope = requiredScope(request.getMethod(), request.getRequestURI());
            if (scope == null) { response.sendError(404); return; }
            var principal = keys.authenticate(request.getHeader("Authorization"), "MANAGEMENT", scope);
            request.setAttribute(PRINCIPAL, principal);
            chain.doFilter(request, response);
        }
        catch (ServiceException error)
        {
            response.setStatus(error.getCode());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"ACCESS_DENIED\",\"message\":\"接入凭证无效或权限不足\",\"retryable\":false,\"requestId\":\"" + UUID.randomUUID() + "\"}");
        }
    }

    static String requiredScope(String method, String path)
    {
        String base = "/openapi/v1/management/";
        if (!path.startsWith(base)) return null;
        String resource = path.substring(base.length());
        String id = "[0-9]+";
        String version = "avatars/" + id + "/versions/" + id;
        if (resource.matches("access-keys(?:/" + id + "/(?:rotations|disable|delete))?")
            || resource.matches("applications/" + id + "/secrets(?:/reset|/" + id + "/(?:disable|delete))?"))
            return "keys:write";
        if ("GET".equals(method))
        {
            if (resource.matches("(?:usage|call-records)(?:/.*)?")) return "usage:read";
            if (resource.matches("webhook-endpoints(?:/[0-9]+/deliveries|/deliveries/[0-9]+/attempts)?")) return "webhooks:write";
            if (resource.matches("avatars(?:/public|/" + id + "(?:/references|/versions/" + id + "/preview)?)?")) return "assets:read";
            if (resource.matches("avatar-generation-tasks(?:/" + id + "(?:/steps)?)?"
                + "|avatar-generation-services|" + version + "/(?:production|actions/[^/]+/results/" + id + "/preview)"))
                return "generation:read";
            if (resource.matches("avatar-reference-files/" + id)) return "assets:read";
            if (resource.equals("voices")) return "config:read";
            if (resource.matches("relay-services(?:/" + id + ")?")) return "config:read";
            if (resource.matches("skills(?:/candidates|/" + id + ")?")) return "config:read";
            if (resource.matches("applications(?:/resources|/" + id + "(?:/config-versions(?:/" + id + ")?)?)?"))
                return "config:read";
        }
        if ("POST".equals(method))
        {
            if (resource.matches("webhook-endpoints(?:/" + id + "/(?:secret|status))?")) return "webhooks:write";
            if (resource.equals("avatar-reference-files") || resource.matches(version + "/publish|avatars/" + id + "/versions")) return "assets:write";
            if (resource.equals("avatar-generation-tasks")
                || resource.matches(version + "/(?:assemble|actions/[^/]+/(?:selection|generations|attempts/" + id + "/(?:recovery|discard)))"))
                return "generation:write";
            if (resource.matches("relay-services(?:/" + id + "/(?:versions|token|status|connection-test))?"))
                return "config:write";
            if (resource.matches("skills(?:/" + id + "/(?:versions|status|connection-check))?")) return "config:write";
            if (resource.matches("applications(?:/" + id + "/(?:config-versions|status))?"))
                return "config:write";
        }
        if ("PUT".equals(method) && resource.matches("relay-services/" + id + "/grants")) return "config:write";
        if ("DELETE".equals(method) && resource.matches("avatars/" + id)) return "assets:write";
        if ("DELETE".equals(method) && resource.matches("relay-services/" + id)) return "config:write";
        if ("DELETE".equals(method) && resource.matches("skills/" + id)) return "config:write";
        return null;
    }
}
