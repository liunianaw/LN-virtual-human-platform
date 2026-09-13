package com.ruoyi.session.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * In-memory orchestration for a fixed SPEAK_ONLY DEBUG Session. Persistent
 * Session/grant/operation records and actual provider calls are deliberately
 * outside this M2 foundation; all late audio is still rejected and queued for
 * cleanup before it can reach a client publisher.
 */
@Service
public class SpeakOnlyRuntimeService implements TtsCompletionSink
{
    private final VoiceRuntimeProperties properties;
    private final TemporaryAudioCleanupQueue cleanupQueue;
    private final PersistentRuntimeStore persistentStore;
    private final Map<String, TurnState> turns = new ConcurrentHashMap<>();
    private final Map<Long, String> activeTurnBySession = new ConcurrentHashMap<>();

    public SpeakOnlyRuntimeService(VoiceRuntimeProperties properties, TemporaryAudioCleanupQueue cleanupQueue,
            PersistentRuntimeStore persistentStore)
    {
        this.properties = properties;
        this.cleanupQueue = cleanupQueue;
        this.persistentStore = persistentStore;
    }

    public SpeechStarted start(RuntimePrincipal principal, String requestId, String text)
    {
        principal.requireSpeakScope();
        validateRequest(requestId, text);
        List<String> chunks = split(text, properties.getMaxCodePointsPerSegment());
        String priorTurnId = activeTurnBySession.get(principal.sessionId());
        if (priorTurnId != null)
        {
            stop(principal, priorTurnId);
        }
        long persistentTurnId = persistentStore.createSpeakTurn(principal, requestId, chunks);
        TurnState state = new TurnState(principal, persistentTurnId, chunks);
        turns.put(state.turnId, state);
        activeTurnBySession.put(principal.sessionId(), state.turnId);
        synchronized (state)
        {
            return new SpeechStarted(state.turnId, state.generation, state.segments.size(), state.dispatchAvailable());
        }
    }

    @Override
    public AudioReadyResult onAudioReady(RuntimePrincipal principal, AudioReadyInput input)
    {
        TurnState state = turns.get(input.turnId());
        if (state == null || !state.belongsTo(principal))
        {
            cleanupQueue.schedule(input.temporaryAudio());
            return AudioReadyResult.ignored();
        }
        synchronized (state)
        {
            if (state.stopped || input.generation() != state.generation
                    || !state.acceptAudio(input, properties.getMaxAudioBytes(), properties.getTemporaryAudioTtl()))
            {
                cleanupQueue.schedule(input.temporaryAudio());
                return AudioReadyResult.ignored();
            }
            persistentStore.markAudioReady(Long.parseLong(state.turnId), input.ordinal(), principal, input.temporaryAudio(), input.bytes());
            return new AudioReadyResult(true, state.drainOrderedEvents(), List.of());
        }
    }

    public PlaybackUpdated reportPlayback(RuntimePrincipal principal, String turnId, String segmentId, PlaybackState state)
    {
        TurnState turn = turns.get(turnId);
        if (turn == null || !turn.belongsTo(principal))
        {
            return PlaybackUpdated.ignored();
        }
        synchronized (turn)
        {
            if (turn.stopped || !turn.updatePlayback(segmentId, state, cleanupQueue))
            {
                return PlaybackUpdated.ignored();
            }
            persistentStore.playback(Long.parseLong(turn.turnId), turn.ordinalOf(segmentId), state);
            if (state == PlaybackState.FAILED || state == PlaybackState.SKIPPED)
            {
                finishStopped(turn);
                return new PlaybackUpdated(true, List.of());
            }
            if (turn.isCompleted())
            {
                finishCompleted(turn);
                return new PlaybackUpdated(true, List.of());
            }
            return new PlaybackUpdated(true, turn.dispatchAvailable());
        }
    }

    /** Stop is idempotent and deliberately needs only a valid token for the same Session. */
    public StopResult stop(RuntimePrincipal principal, String turnId)
    {
        TurnState turn = turns.get(turnId);
        if (turn == null || !turn.belongsTo(principal))
        {
            return new StopResult(turnId, true);
        }
        synchronized (turn)
        {
            if (turn.stopped)
            {
                return new StopResult(turnId, true);
            }
            turn.stop(cleanupQueue);
            finishStopped(turn, "USER_STOP");
            return new StopResult(turnId, false);
        }
    }

    /** Called by trusted logout/revocation/expiry handling; it never trusts browser input. */
    public void revokeSession(long sessionId)
    {
        String turnId = activeTurnBySession.get(sessionId);
        TurnState turn = turnId == null ? null : turns.get(turnId);
        if (turn == null)
        {
            return;
        }
        synchronized (turn)
        {
            finishStopped(turn, "REVOKED");
        }
    }

    private void finishStopped(TurnState turn)
    {
        finishStopped(turn, "USER_STOP");
    }

    private void finishStopped(TurnState turn, String reason)
    {
        turn.stop(cleanupQueue);
        persistentStore.stop(Long.parseLong(turn.turnId), reason);
        turns.remove(turn.turnId, turn);
        activeTurnBySession.remove(turn.principal.sessionId(), turn.turnId);
    }

    private void finishCompleted(TurnState turn)
    {
        persistentStore.complete(Long.parseLong(turn.turnId));
        turns.remove(turn.turnId, turn);
        activeTurnBySession.remove(turn.principal.sessionId(), turn.turnId);
    }

    private void validateRequest(String requestId, String text)
    {
        if (requestId == null || requestId.isBlank() || requestId.length() > 64 || text == null || text.isBlank())
        {
            throw new RuntimeProblem(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Speech requires a non-empty request ID and text.");
        }
        int codePoints = text.codePointCount(0, text.length());
        if (codePoints > 8000)
        {
            throw new RuntimeProblem(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Speech text exceeds 8000 Unicode code points.");
        }
        if (properties.getMaxCodePointsPerSegment() <= 0 || properties.getMaxBufferedSegments() <= 0
                || properties.getMaxAudioBytes() <= 0 || properties.getTemporaryAudioTtl().isNegative())
        {
            throw new IllegalStateException("Invalid non-secret voice runtime limits");
        }
    }

    private static List<String> split(String text, int maxCodePoints)
    {
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < text.length())
        {
            int remaining = text.codePointCount(start, text.length());
            int end = text.offsetByCodePoints(start, Math.min(remaining, maxCodePoints));
            if (end < text.length())
            {
                int sentenceEnd = findSentenceEnd(text, start, end);
                if (sentenceEnd > start)
                {
                    end = sentenceEnd;
                }
            }
            result.add(text.substring(start, end));
            start = end;
        }
        return List.copyOf(result);
    }

    private static int findSentenceEnd(String text, int start, int end)
    {
        for (int index = end; index > start;)
        {
            int codePoint = text.codePointBefore(index);
            index -= Character.charCount(codePoint);
            if ("。！？；!?;\n".indexOf(codePoint) >= 0)
            {
                return index + Character.charCount(codePoint);
            }
        }
        return start;
    }

    public record SpeechStarted(String turnId, long generation, int segmentCount, List<TtsSynthesisWork> initialWork)
    {
    }

    public record StopResult(String turnId, boolean alreadyStopped)
    {
    }

    public record PlaybackUpdated(boolean accepted, List<TtsSynthesisWork> nextWork)
    {
        static PlaybackUpdated ignored()
        {
            return new PlaybackUpdated(false, List.of());
        }
    }

    private final class TurnState
    {
        private final RuntimePrincipal principal;
        private final String turnId;
        private final long generation = 1L;
        private final List<SegmentState> segments;
        private final Map<String, SegmentState> byId;
        private int nextDispatch;
        private int nextDelivery;
        private boolean stopped;

        private TurnState(RuntimePrincipal principal, long turnId, List<String> chunks)
        {
            this.principal = principal;
            this.turnId = Long.toString(turnId);
            this.segments = new ArrayList<>();
            this.byId = new HashMap<>();
            for (int ordinal = 0; ordinal < chunks.size(); ordinal++)
            {
                SegmentState segment = new SegmentState(UUID.randomUUID().toString(), ordinal, chunks.get(ordinal));
                segments.add(segment);
                byId.put(segment.segmentId, segment);
            }
        }

        private boolean belongsTo(RuntimePrincipal candidate)
        {
            return principal.accountId() == candidate.accountId() && principal.applicationId() == candidate.applicationId()
                    && principal.sessionId() == candidate.sessionId() && principal.configVersionId() == candidate.configVersionId();
        }

        private List<TtsSynthesisWork> dispatchAvailable()
        {
            int occupied = (int) segments.stream().filter(SegmentState::occupiesBuffer).count();
            List<TtsSynthesisWork> work = new ArrayList<>();
            while (!stopped && occupied < properties.getMaxBufferedSegments() && nextDispatch < segments.size())
            {
                SegmentState segment = segments.get(nextDispatch++);
                segment.status = SegmentStatus.DISPATCHED;
                work.add(new TtsSynthesisWork(turnId, generation, segment.segmentId, segment.ordinal, segment.text, principal.voice()));
                occupied++;
            }
            return List.copyOf(work);
        }

        private boolean acceptAudio(AudioReadyInput input, long maxAudioBytes, java.time.Duration maximumTtl)
        {
            SegmentState segment = byId.get(input.segmentId());
            if (segment == null || segment.ordinal != input.ordinal() || segment.status != SegmentStatus.DISPATCHED
                    || input.bytes() < 0 || input.bytes() > maxAudioBytes || input.durationMs() < 0
                    || !"audio/wav".equals(input.mimeType()) || input.temporaryAudio().expiresAt().isBefore(Instant.now())
                    || input.temporaryAudio().expiresAt().isAfter(Instant.now().plus(maximumTtl)))
            {
                return false;
            }
            segment.audio = input.temporaryAudio();
            segment.durationMs = input.durationMs();
            segment.status = SegmentStatus.READY;
            return true;
        }

        private List<AudioSegmentEvent> drainOrderedEvents()
        {
            List<AudioSegmentEvent> events = new ArrayList<>();
            while (nextDelivery < segments.size())
            {
                SegmentState segment = segments.get(nextDelivery);
                if (segment.status != SegmentStatus.READY)
                {
                    break;
                }
                segment.status = SegmentStatus.DELIVERED;
                events.add(new AudioSegmentEvent(turnId, segment.segmentId, segment.ordinal, segment.audio.mediaId(), "audio/wav",
                        segment.durationMs, segment.audio.expiresAt()));
                nextDelivery++;
            }
            return List.copyOf(events);
        }

        private boolean updatePlayback(String segmentId, PlaybackState playback, TemporaryAudioCleanupQueue queue)
        {
            SegmentState segment = byId.get(segmentId);
            if (segment == null)
            {
                return false;
            }
            if (playback == PlaybackState.STARTED && segment.status == SegmentStatus.DELIVERED)
            {
                segment.status = SegmentStatus.PLAYING;
                return true;
            }
            if ((playback == PlaybackState.ENDED || playback == PlaybackState.FAILED || playback == PlaybackState.SKIPPED)
                    && (segment.status == SegmentStatus.DELIVERED || segment.status == SegmentStatus.PLAYING))
            {
                segment.status = playback == PlaybackState.ENDED ? SegmentStatus.ENDED : SegmentStatus.FAILED;
                queue.schedule(segment.audio);
                return true;
            }
            return false;
        }

        private int ordinalOf(String segmentId)
        {
            SegmentState segment = byId.get(segmentId);
            if (segment == null)
            {
                throw new IllegalArgumentException("Unknown segment");
            }
            return segment.ordinal;
        }

        private boolean isCompleted()
        {
            return segments.stream().allMatch(segment -> segment.status == SegmentStatus.ENDED);
        }

        private void stop(TemporaryAudioCleanupQueue queue)
        {
            if (stopped)
            {
                return;
            }
            stopped = true;
            for (SegmentState segment : segments)
            {
                if (segment.audio != null && segment.status != SegmentStatus.ENDED && segment.status != SegmentStatus.FAILED)
                {
                    queue.schedule(segment.audio);
                }
                if (!segment.isTerminal())
                {
                    segment.status = SegmentStatus.STOPPED;
                }
            }
        }
    }

    private static final class SegmentState
    {
        private final String segmentId;
        private final int ordinal;
        private final String text;
        private SegmentStatus status = SegmentStatus.NEW;
        private TemporaryAudioReference audio;
        private long durationMs;

        private SegmentState(String segmentId, int ordinal, String text)
        {
            this.segmentId = segmentId;
            this.ordinal = ordinal;
            this.text = text;
        }

        private boolean occupiesBuffer()
        {
            return status == SegmentStatus.DISPATCHED || status == SegmentStatus.READY || status == SegmentStatus.DELIVERED
                    || status == SegmentStatus.PLAYING;
        }

        private boolean isTerminal()
        {
            return status == SegmentStatus.ENDED || status == SegmentStatus.FAILED || status == SegmentStatus.STOPPED;
        }
    }

    private enum SegmentStatus
    {
        NEW,
        DISPATCHED,
        READY,
        DELIVERED,
        PLAYING,
        ENDED,
        FAILED,
        STOPPED
    }
}
