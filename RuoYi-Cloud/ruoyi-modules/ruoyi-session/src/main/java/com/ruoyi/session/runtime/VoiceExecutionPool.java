package com.ruoyi.session.runtime;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Business synthesis and auditions share the same bounded capacity. */
@Component
public class VoiceExecutionPool
{
    private final ThreadPoolExecutor executor;
    private final long queueNanos;

    public VoiceExecutionPool(VoiceRuntimeProperties properties)
    {
        var limits = properties.getOfficial();
        if (limits.getConcurrency() < 1 || limits.getConcurrency() > 16 || limits.getQueueCapacity() < 1
            || limits.getQueueCapacity() > 128 || limits.getQueueTimeout().isNegative()
            || limits.getQueueTimeout().isZero() || limits.getQueueTimeout().toSeconds() > 60
            || limits.getTimeout().isNegative() || limits.getTimeout().isZero() || limits.getTimeout().toSeconds() > 120)
            throw new IllegalArgumentException("Invalid voice execution limits");
        queueNanos = limits.getQueueTimeout().toNanos();
        executor = new ThreadPoolExecutor(limits.getConcurrency(), limits.getConcurrency(), 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(limits.getQueueCapacity()), task -> {
                Thread thread = new Thread(task, "voice-synthesis"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    }

    public <T> Future<T> submit(Callable<T> task)
    {
        long queued = System.nanoTime();
        try { return executor.submit(() -> {
            if (System.nanoTime() - queued > queueNanos) throw problem("VOICE_QUEUE_TIMEOUT");
            return task.call();
        }); }
        catch (RejectedExecutionException error) { throw problem("VOICE_QUEUE_FULL"); }
    }

    public void execute(Runnable task, java.util.function.Consumer<String> rejected)
    {
        long queued = System.nanoTime();
        try { executor.execute(() -> {
            if (System.nanoTime() - queued > queueNanos) rejected.accept("VOICE_QUEUE_TIMEOUT");
            else task.run();
        }); }
        catch (RejectedExecutionException error) { throw problem("VOICE_QUEUE_FULL"); }
    }

    private static RuntimeProblem problem(String code)
    { return new RuntimeProblem(HttpStatus.TOO_MANY_REQUESTS, code, "Voice execution capacity is unavailable."); }

    @PreDestroy public void close() { executor.shutdownNow(); }
}
