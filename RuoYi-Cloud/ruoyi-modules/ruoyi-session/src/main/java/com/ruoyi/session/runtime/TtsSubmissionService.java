package com.ruoyi.session.runtime;

import com.ruoyi.session.business.BusinessSystemClient;
import com.ruoyi.session.runtime.mapper.TtsLifecycleMapper;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Rechecks authority and reserves official quota before each new provider side effect. */
@Service
public class TtsSubmissionService
{
    private static final Logger LOG = LoggerFactory.getLogger(TtsSubmissionService.class);
    private final RuntimeAuthorization access;
    private final RuntimeConnectionEpochs epochs;
    private final PersistentRuntimeStore store;
    private final BusinessSystemClient system;
    private final IVoiceOrchestrationService voices;
    private final SpeakOnlyRuntimeService runtime;
    private final TtsLifecycleMapper lifecycle;
    private final VoiceRuntimeProperties properties;
    private final String owner = UUID.randomUUID().toString();

    public TtsSubmissionService(RuntimeAuthorization access, RuntimeConnectionEpochs epochs,
        PersistentRuntimeStore store, BusinessSystemClient system, IVoiceOrchestrationService voices,
        SpeakOnlyRuntimeService runtime, TtsLifecycleMapper lifecycle, VoiceRuntimeProperties properties)
    { this.access = access; this.epochs = epochs; this.store = store; this.system = system; this.voices = voices;
      this.runtime = runtime; this.lifecycle = lifecycle; this.properties = properties; }

    String owner() { return owner; }

    public void submit(RuntimeAuthorization.Grant grant, long epoch, Iterable<TtsSynthesisWork> work)
    {
        for (TtsSynthesisWork item : work) submitOne(grant, epoch, item);
    }

    private void submitOne(RuntimeAuthorization.Grant grant, long epoch, TtsSynthesisWork work)
    {
        RuntimePrincipal principal = work.principal();
        String businessId = work.turnId() + ":" + work.ordinal();
        long turn = Long.parseLong(work.turnId());
        try
        {
            RuntimeAuthorization.Grant fresh=access.verify(grant.id());
            if (!fresh.tokenId().equals(grant.tokenId()) || !epochs.current(principal,epoch))
                throw new RuntimeProblem(org.springframework.http.HttpStatus.CONFLICT,"CONNECTION_REPLACED","Connection was replaced.");
        }
        catch (RuntimeException error)
        {
            runtime.onAudioFailed(principal,work,error instanceof RuntimeProblem problem?problem.code():"TTS_AUTH_UNAVAILABLE");
            return;
        }
        try
        {
            // Claim before reserve HTTP: a lost response can be reconciled by the same business key.
            if (lifecycle.prepare(principal.accountId(), principal.sessionId(), turn, work.ordinal(), work.segmentId(),
                epoch, owner, Instant.now().plus(properties.getOfficial().getQueueTimeout())
                    .plus(properties.getOfficial().getTimeout()).plusSeconds(30)) != 1) return;
            if ("BUSINESS_KEY".equals(grant.source()) && principal.voice().providerKind() != TtsProviderKind.OFFICIAL)
                throw new RuntimeProblem(org.springframework.http.HttpStatus.FORBIDDEN, "OFFICIAL_VOICE_REQUIRED", "Official Voice is required.");
            long reservationId = system.reserveTts(principal.accountId(), principal.applicationId(), businessId,
                work.text().codePointCount(0, work.text().length()));
            lifecycle.reserved(turn, work.ordinal(), reservationId);
            store.beginTts(principal, work, epoch, reservationId == 0 ? null : reservationId);
            voices.submit(grant, epoch, work, new TtsCompletionSink()
            {
                @Override public void beforeExternal(TtsSynthesisWork item)
                {
                    RuntimeAuthorization.Grant current = access.verify(grant.id());
                    if (!current.tokenId().equals(grant.tokenId()) || !epochs.current(principal, epoch))
                        throw new RuntimeProblem(org.springframework.http.HttpStatus.CONFLICT, "CONNECTION_REPLACED", "Connection was replaced.");
                    if (lifecycle.dispatch(turn, work.ordinal(), owner, epoch) != 1)
                        throw new RuntimeProblem(org.springframework.http.HttpStatus.CONFLICT, "VOICE_CANCELLED", "Speech is no longer active.");
                }
                @Override public AudioReadyResult onAudioReady(RuntimePrincipal ignored, AudioReadyInput input)
                {
                    // Persist success before playback or remote billing; duplicates cannot downgrade it.
                    // The unified arbiter committed task adoption and operation settlement together.
                    AudioReadyResult result = runtime.onAudioReady(principal, input);
                    if (!result.accepted()) store.markLateAudioSucceeded(principal, work, input.durationMs());
                    return result;
                }
                @Override public void onAudioFailed(RuntimePrincipal ignored, TtsSynthesisWork failed, String code)
                {
                    // Attempt outcome and billing intent were committed by the arbiter.
                    notifyFailure(principal, failed, code);
                }
            });
        }
        catch (RuntimeException error)
        {
            LOG.warn("TTS submission failed turnId={} ordinal={}", work.turnId(), work.ordinal(), error);
            // Explicit pre-admission rejection has no reservation; transport failures remain unconfirmed.
            if (error instanceof RuntimeProblem problem && "BUSINESS_AUTH_REJECTED".equals(problem.code())
                && java.util.Set.of(400,401,403,404,429).contains(problem.status().value()))
            {
                try { lifecycle.reserved(turn, work.ordinal(), 0); }
                catch (RuntimeException cleanupError)
                { LOG.warn("TTS reservation lookup remains pending turnId={} ordinal={}", work.turnId(), work.ordinal(), cleanupError); }
            }
            try { lifecycle.finish(turn, work.ordinal(), owner, false); }
            catch (RuntimeException cleanupError)
            { LOG.warn("TTS finalization remains pending turnId={} ordinal={}", work.turnId(), work.ordinal(), cleanupError); }
            notifyFailure(principal, work, error instanceof RuntimeProblem problem ? problem.code() : "TTS_SUBMIT_FAILED");
        }
    }

    private void notifyFailure(RuntimePrincipal principal, TtsSynthesisWork work, String code)
    {
        try { store.cancelNotSubmitted(principal, work); }
        catch (RuntimeException error)
        { LOG.warn("TTS cancellation remains pending turnId={} ordinal={}", work.turnId(), work.ordinal(), error); }
        finally { runtime.onAudioFailed(principal, work, code); }
    }
}
