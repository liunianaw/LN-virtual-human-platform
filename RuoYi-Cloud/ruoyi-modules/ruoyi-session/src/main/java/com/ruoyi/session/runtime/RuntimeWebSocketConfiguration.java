package com.ruoyi.session.runtime;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** Minimal authenticated WSS route; only runtime stop is accepted until event delivery is wired. */
@Configuration
@EnableWebSocket
public class RuntimeWebSocketConfiguration implements WebSocketConfigurer
{
    private final ConsoleDebugSessionAuthenticator authenticator;
    private final SpeakOnlyRuntimeService runtime;
    private final ObjectMapper objectMapper;

    public RuntimeWebSocketConfiguration(ConsoleDebugSessionAuthenticator authenticator, SpeakOnlyRuntimeService runtime,
            ObjectMapper objectMapper)
    {
        this.authenticator = authenticator;
        this.runtime = runtime;
        this.objectMapper = objectMapper;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry)
    {
        registry.addHandler(new RuntimeHandler(), "/api/v1/runtime/ws")
                .addInterceptors(new RuntimeHandshakeAuthenticator());
    }

    private final class RuntimeHandshakeAuthenticator implements HandshakeInterceptor
    {
        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, org.springframework.web.socket.WebSocketHandler handler,
                Map<String, Object> attributes)
        {
            try
            {
                attributes.put("runtimePrincipal", authenticator.authenticate(request.getHeaders().getFirst("Authorization")));
                return true;
            }
            catch (RuntimeProblem problem)
            {
                response.setStatusCode(problem.status());
                return false;
            }
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, org.springframework.web.socket.WebSocketHandler handler,
                Exception exception)
        {
            // no-op
        }
    }

    private final class RuntimeHandler extends TextWebSocketHandler
    {
        @Override
        public void afterConnectionEstablished(WebSocketSession session) throws IOException
        {
            session.sendMessage(new TextMessage("{\"type\":\"connection.ready\"}"));
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException
        {
            RuntimePrincipal principal = (RuntimePrincipal) session.getAttributes().get("runtimePrincipal");
            try
            {
                JsonNode node = objectMapper.readTree(message.getPayload());
                if (!"turn.stop".equals(node.path("type").asText()) || !node.hasNonNull("turnId"))
                {
                    throw new RuntimeProblem(org.springframework.http.HttpStatus.BAD_REQUEST, "PROTOCOL_ERROR", "Only turn.stop is available.");
                }
                SpeakOnlyRuntimeService.StopResult stopped = runtime.stop(principal, node.get("turnId").asText());
                session.sendMessage(new TextMessage("{\"type\":\"turn.stopped\",\"turnId\":\"" + stopped.turnId()
                        + "\",\"alreadyStopped\":" + stopped.alreadyStopped() + "}"));
            }
            catch (RuntimeProblem problem)
            {
                session.sendMessage(new TextMessage("{\"type\":\"request.error\",\"code\":\"" + problem.code() + "\"}"));
            }
            catch (Exception exception)
            {
                session.sendMessage(new TextMessage("{\"type\":\"request.error\",\"code\":\"PROTOCOL_ERROR\"}"));
            }
        }
    }
}
