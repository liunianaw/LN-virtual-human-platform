package com.ruoyi.system.operations;

import com.ruoyi.system.voice.InternalBearerGuard;
import com.ruoyi.system.operations.service.ITtsQuotaService;
import java.util.Map;
import com.ruoyi.common.core.exception.ServiceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/tts-quota")
public class TtsQuotaInternalController
{
    private final InternalBearerGuard guard;
    private final ITtsQuotaService quota;
    public TtsQuotaInternalController(InternalBearerGuard guard, ITtsQuotaService quota)
    { this.guard = guard; this.quota = quota; }

    @PostMapping("/reserve")
    public Reservation reserve(@RequestHeader(HttpHeaders.AUTHORIZATION) String bearer, @RequestBody Request body)
    {
        guard.requireSession(bearer);
        return new Reservation(Long.toString(quota.reserve(body.accountId(), body.applicationId(), body.businessId(), body.units())));
    }

    @PostMapping("/lookup")
    public Reservation lookup(@RequestHeader(HttpHeaders.AUTHORIZATION) String bearer, @RequestBody Request body)
    {
        guard.requireSession(bearer);
        Long id=quota.findReservation(body.accountId(),body.businessId());
        return new Reservation(id==null?null:Long.toString(id));
    }

    public record Reservation(String reservationId) { }

    @PostMapping("/finish")
    public void finish(@RequestHeader(HttpHeaders.AUTHORIZATION) String bearer, @RequestBody Request body)
    { guard.requireSession(bearer); quota.finish(body.accountId(), body.businessId(), body.outcome()); }

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<Map<String, String>> error(ServiceException problem)
    {
        int status = problem.getCode() == null ? 500 : problem.getCode();
        return ResponseEntity.status(status).body(Map.of("code", Integer.toString(status), "message", problem.getMessage()));
    }

    public record Request(long accountId, long applicationId, String businessId, long units, String outcome) { }
}
