package com.ruoyi.common.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;

/** Bounded internal transport, redirects and automatic POST retries are not enabled. */
public final class VoiceHttp
{
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    public VoiceHttp(ObjectMapper json) { this.json=json; }
    public byte[] request(String base,String token,String method,String path,Object body,String key,int maximum)
    {
        if(base==null || base.isBlank() || token==null || token.isBlank()) throw new Failure("VOICE_PROVIDER_NOT_READY",503);
        try {
            var builder=HttpRequest.newBuilder(URI.create(base.replaceAll("/$","")+path)).timeout(Duration.ofSeconds(8))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json");
            if(key!=null) builder.header("Idempotency-Key",key);
            builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
            var pending=client.sendAsync(builder.build(),info->new LimitedBody(maximum));
            HttpResponse<byte[]> response;
            try { response=pending.get(8,TimeUnit.SECONDS); }
            catch(Exception error) { pending.cancel(true);throw error; }
            {
                byte[] data=response.body();
                if(response.statusCode()<200 || response.statusCode()>=300) {
                    String code="VOICE_OUTCOME_UNKNOWN";
                    try { String candidate=json.readTree(data).path("code").asText(); if(candidate.matches("[A-Z_]{1,64}")) code=candidate; } catch(Exception ignored) { }
                    throw new Failure(code,response.statusCode());
                }
                return data;
            }
        } catch(Failure e) { throw e; }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new Failure("VOICE_OUTCOME_UNKNOWN",503); }
        catch(Exception e) { throw new Failure("VOICE_OUTCOME_UNKNOWN",503); }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final int maximum; private Flow.Subscription subscription;
        LimitedBody(int maximum) { this.maximum=maximum; }
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription=subscription;subscription.request(1); }
        public void onNext(List<ByteBuffer> chunks) {
            for(ByteBuffer chunk:chunks) {
                if(chunk.remaining()>maximum-bytes.size()) { subscription.cancel();body.completeExceptionally(new Failure("VOICE_RESULT_TOO_LARGE",502));return; }
                byte[] data=new byte[chunk.remaining()];chunk.get(data);bytes.writeBytes(data);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }
    public static class Failure extends RuntimeException {
        public final String code; public final int status;
        public Failure(String code,int status) { super(code); this.code=code; this.status=status; }
    }
}
