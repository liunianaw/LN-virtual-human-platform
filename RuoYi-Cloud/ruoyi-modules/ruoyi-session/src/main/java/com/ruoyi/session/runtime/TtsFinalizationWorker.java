package com.ruoyi.session.runtime;

import com.ruoyi.session.business.BusinessSystemClient;
import com.ruoyi.session.runtime.mapper.TtsLifecycleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Single Session instance: recovers billing only, never replays provider synthesis. */
@Component
public class TtsFinalizationWorker
{
    private static final Logger LOG = LoggerFactory.getLogger(TtsFinalizationWorker.class);
    private final TtsLifecycleMapper mapper;
    private final BusinessSystemClient system;
    private final TtsSubmissionService submissions;
    private final PersistentRuntimeStore store;

    public TtsFinalizationWorker(TtsLifecycleMapper mapper, BusinessSystemClient system, TtsSubmissionService submissions,
        PersistentRuntimeStore store)
    { this.mapper = mapper; this.system = system; this.submissions = submissions; this.store = store; }

    @Scheduled(fixedDelayString = "${LN_SESSION_TTS_FINALIZE_SWEEP_MS:5000}")
    public void sweep()
    {
        mapper.recover(submissions.owner(), 32);
        for (long id : mapper.factsToRecover(submissions.owner(),32)) store.reconcileTtsFact(id);
        for (var item : mapper.pending(8))
        {
            try
            {
                // Resolve a lost response read-only; never create a fresh reserve during recovery.
                long reservation = !item.reservationKnown()
                    ? system.lookupTts(item.accountId(), item.businessId())
                    : item.reservationId() == null ? 0 : item.reservationId();
                if (!item.reservationKnown()) mapper.reserved(item.turnId(), item.ordinal(), reservation);
                if (reservation > 0) system.finishTts(item.accountId(), item.businessId(), item.outcome());
                mapper.done(item.id(), item.outcome());
            }
            catch (RuntimeException error)
            {
                String code = error instanceof RuntimeProblem problem ? problem.code() : "TTS_FINALIZATION_UNAVAILABLE";
                mapper.retry(item.id(), item.outcome(), code);
                LOG.warn("TTS finalization pending operationId={} outcome={} attempt={} code={}",
                    item.id(), item.outcome(), item.attempts() + 1, code);
            }
        }
    }
}
