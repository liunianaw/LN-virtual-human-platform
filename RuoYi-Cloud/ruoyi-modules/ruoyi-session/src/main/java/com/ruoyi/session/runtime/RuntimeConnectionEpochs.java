package com.ruoyi.session.runtime;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** DB allocates epochs; Redis accepts only a strictly newer generation across nodes. */
@Component
public class RuntimeConnectionEpochs
{
    private static final DefaultRedisScript<Long> ADVANCE = new DefaultRedisScript<>(
        "local old=redis.call('get',KEYS[1]); if old and tonumber(old)>=tonumber(ARGV[1]) then return 0 end; " +
            "redis.call('set',KEYS[1],ARGV[1],'EX',90000); return 1", Long.class);
    private final StringRedisTemplate redis;
    private final PersistentRuntimeStore store;
    public RuntimeConnectionEpochs(StringRedisTemplate redis, PersistentRuntimeStore store)
    { this.redis = redis; this.store = store; }

    public void advance(RuntimePrincipal principal, long epoch)
    {
        try
        {
            Long accepted = redis.execute(ADVANCE, List.of(key(principal.sessionId())), Long.toString(epoch));
            if (accepted == null || accepted != 1) throw unavailable();
        }
        catch (org.springframework.data.redis.RedisConnectionFailureException error) { throw unavailable(); }
    }

    public boolean current(RuntimePrincipal principal, long epoch)
    {
        if (!store.currentConnection(principal, epoch)) return false;
        try { return Long.toString(epoch).equals(redis.opsForValue().get(key(principal.sessionId()))); }
        catch (org.springframework.data.redis.RedisConnectionFailureException error) { throw unavailable(); }
    }

    private static String key(long sessionId) { return "ln:runtime:connection:" + sessionId; }
    private static RuntimeProblem unavailable()
    { return new RuntimeProblem(HttpStatus.SERVICE_UNAVAILABLE, "CONNECTION_STATE_UNAVAILABLE", "Connection ownership is unavailable."); }
}
