package com.ruoyi.system.developer.openapi;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.ruoyi.common.core.exception.ServiceException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.ruoyi.system.developer.openapi")
public class OpenApiErrors
{
    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<Map<String, Object>> service(ServiceException error)
    {
        int status = error.getCode() == null ? 500 : error.getCode();
        String code = switch (status) { case 400 -> "INVALID_REQUEST"; case 401 -> "AUTH_INVALID";
            case 403 -> "FORBIDDEN"; case 409 -> "CONFLICT"; default -> "ACCESS_ERROR"; };
        return ResponseEntity.status(status).body(body(code, status >= 500 ? "接入操作失败" : error.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> invalid(Exception error)
    { return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body("INVALID_REQUEST", "请求参数无效")); }

    private static Map<String, Object> body(String code, String message)
    { return Map.of("code", code, "message", message, "retryable", false, "requestId", UUID.randomUUID().toString()); }
}
