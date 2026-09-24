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
            // DEV-02 onward adds explicit resource-to-scope mappings here as each family is implemented.
            String path = request.getRequestURI();
            if (!path.startsWith("/openapi/v1/management/access-keys")
                && !path.matches("/openapi/v1/management/applications/[0-9]+/secrets(?:/reset|/[0-9]+/(?:disable|delete))?"))
            { response.sendError(404); return; }
            var principal = keys.authenticate(request.getHeader("Authorization"), "MANAGEMENT", "keys:write");
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
}
