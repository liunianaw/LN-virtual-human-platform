package com.ruoyi.session.runtime;

import com.ruoyi.session.business.BusinessSystemClient;
import org.springframework.stereotype.Service;

/** Rechecks authority and reserves official quota before each new provider side effect. */
@Service
public class TtsSubmissionService
{
    private final RuntimeAuthorization access;
    private final RuntimeConnectionEpochs epochs;
    private final PersistentRuntimeStore store;
    private final BusinessSystemClient system;
    private final TtsRuntimeAdapterRegistry adapters;
    private final SpeakOnlyRuntimeService runtime;

    public TtsSubmissionService(RuntimeAuthorization access, RuntimeConnectionEpochs epochs,
        PersistentRuntimeStore store, BusinessSystemClient system, TtsRuntimeAdapterRegistry adapters,
        SpeakOnlyRuntimeService runtime)
    { this.access = access; this.epochs = epochs; this.store = store; this.system = system; this.adapters = adapters; this.runtime = runtime; }

    public void submit(RuntimeAuthorization.Grant grant, long epoch, Iterable<TtsSynthesisWork> work)
    {
        for (TtsSynthesisWork item : work) submitOne(grant, epoch, item);
    }

    private void submitOne(RuntimeAuthorization.Grant grant, long epoch, TtsSynthesisWork work)
    {
        RuntimePrincipal principal = work.principal();
        String businessId = work.turnId() + ":" + work.ordinal();
        long reservationId = 0;
        boolean started = false;
        final java.util.concurrent.atomic.AtomicBoolean submitted = new java.util.concurrent.atomic.AtomicBoolean();
        try
        {
            RuntimeAuthorization.Grant fresh = access.verify(grant.id());
            if (!fresh.tokenId().equals(grant.tokenId()) || !epochs.current(principal, epoch))
                throw new RuntimeProblem(org.springframework.http.HttpStatus.CONFLICT, "CONNECTION_REPLACED", "Connection was replaced.");
            if ("BUSINESS_KEY".equals(grant.source()) && principal.voice().providerKind() != TtsProviderKind.OFFICIAL)
                throw new RuntimeProblem(org.springframework.http.HttpStatus.FORBIDDEN, "OFFICIAL_VOICE_REQUIRED", "Official Voice is required.");
            reservationId = system.reserveTts(principal.accountId(), principal.applicationId(), businessId,
                work.text().codePointCount(0, work.text().length()));
            store.beginTts(principal, work, epoch, reservationId == 0 ? null : reservationId);
            started = true;
            final long reserved = reservationId;
            adapters.requireAdapter(principal.voice()).submit(work, new TtsCompletionSink()
            {
                @Override public void beforeExternal(TtsSynthesisWork item)
                {
                    RuntimeAuthorization.Grant current = access.verify(grant.id());
                    if (!current.tokenId().equals(grant.tokenId()) || !epochs.current(principal, epoch))
                        throw new RuntimeProblem(org.springframework.http.HttpStatus.CONFLICT, "CONNECTION_REPLACED", "Connection was replaced.");
                    submitted.set(true);
                }
                @Override public AudioReadyResult onAudioReady(RuntimePrincipal ignored, AudioReadyInput input)
                {
                    if (reserved > 0) finishQuota(principal.accountId(), businessId, "SETTLE");
                    AudioReadyResult result = runtime.onAudioReady(principal, input);
                    if (!result.accepted()) store.markLateAudioSucceeded(principal, work, input.durationMs());
                    return result;
                }
                @Override public void onAudioFailed(RuntimePrincipal ignored, TtsSynthesisWork failed, String code)
                {
                    if (reserved > 0) finishQuota(principal.accountId(), businessId, submitted.get() ? "REVIEW" : "RELEASE");
                    if (!submitted.get()) store.cancelNotSubmitted(principal, failed);
                    runtime.onAudioFailed(principal, failed, submitted.get() ? code : "TTS_NOT_SUBMITTED");
                }
            });
        }
        catch (RuntimeException error)
        {
            if (reservationId != 0) finishQuota(principal.accountId(), businessId, submitted.get() ? "REVIEW" : "RELEASE");
            if (!submitted.get()) store.cancelNotSubmitted(principal, work);
            runtime.onAudioFailed(principal, work, started && !submitted.get() ? "TTS_NOT_SUBMITTED"
                : error instanceof RuntimeProblem problem ? problem.code() : "TTS_SUBMIT_FAILED");
        }
    }

    private void finishQuota(long accountId, String businessId, String outcome)
    {
        try { system.finishTts(accountId, businessId, outcome); }
        catch (RuntimeException ignored) { /* Reservation remains held for reconciliation. */ }
    }
}
