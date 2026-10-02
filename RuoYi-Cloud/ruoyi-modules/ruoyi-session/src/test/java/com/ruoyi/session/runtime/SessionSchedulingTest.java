package com.ruoyi.session.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.session.SessionSchedulingConfiguration;
import com.ruoyi.session.business.BusinessCredentialStore;
import com.ruoyi.session.business.BusinessSessionService;
import com.ruoyi.session.business.BusinessSessionStore;
import com.ruoyi.session.business.BusinessSystemClient;
import com.ruoyi.session.runtime.mapper.TtsLifecycleMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class SessionSchedulingTest
{
    @Test
    void maintenanceWorkersRunAutomaticallyWhileFinalizationWaits() throws Exception
    {
        CountDownLatch finalizationStarted = new CountDownLatch(1);
        CountDownLatch releaseFinalization = new CountDownLatch(1);
        TtsLifecycleMapper lifecycle = mock(TtsLifecycleMapper.class);
        BusinessSystemClient system = mock(BusinessSystemClient.class);
        TtsSubmissionService submissions = mock(TtsSubmissionService.class);
        PersistentRuntimeStore runtimeStore = mock(PersistentRuntimeStore.class);
        TemporaryWavStorage storage = mock(TemporaryWavStorage.class);
        BusinessSessionStore sessionStore = mock(BusinessSessionStore.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        TtsLifecycleMapper.Finalization item = new TtsLifecycleMapper.Finalization(21, 7, 8, 11, 0, 10, 51L, true, "SETTLE", 0);
        when(submissions.owner()).thenReturn("boot");
        when(lifecycle.pending(8)).thenReturn(List.of(item), List.of());
        when(sessionStore.cleanupCandidates()).thenReturn(List.of());
        when(transactions.execute(any())).thenReturn(null);
        doAnswer(invocation -> {
            finalizationStarted.countDown();
            assertTrue(releaseFinalization.await(2, TimeUnit.SECONDS));
            return null;
        }).when(system).finishTts(7, "11:0", "SETTLE");

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-delays", Map.of(
            "LN_SESSION_TTS_FINALIZE_SWEEP_MS", "20",
            "LN_SESSION_RUNTIME_CLEANUP_SWEEP_MS", "20",
            "platform.runtime.call-fact-publisher-delay-ms", "20",
            "LN_BUSINESS_SESSION_RECOVERY_MS", "20")));
        context.register(SessionSchedulingConfiguration.class);
        context.registerBean(TtsFinalizationWorker.class,
            () -> new TtsFinalizationWorker(lifecycle, system, submissions, runtimeStore));
        context.registerBean(LocalTemporaryAudioCleanupWorker.class,
            () -> new LocalTemporaryAudioCleanupWorker(runtimeStore, storage));
        context.registerBean(SessionCallFactOutboxPublisher.class,
            () -> new SessionCallFactOutboxPublisher(mock(JdbcTemplate.class), transactions, new ObjectMapper()));
        context.registerBean(BusinessSessionService.class, () -> new BusinessSessionService(system, sessionStore,
            mock(RuntimeTokenCodec.class), mock(SpeakOnlyRuntimeService.class), mock(BusinessCredentialStore.class),
            mock(RuntimeEventPublisher.class), mock(ApplicationEventPublisher.class), new ObjectMapper()));

        try
        {
            context.refresh();
            assertTrue(finalizationStarted.await(2, TimeUnit.SECONDS));
            verify(storage, timeout(1000)).cleanupExpiredFiles();
            verify(sessionStore, timeout(1000)).cleanupCandidates();
            verify(transactions, timeout(1000)).execute(any());
        }
        finally
        {
            releaseFinalization.countDown();
            context.close();
        }
    }
}
