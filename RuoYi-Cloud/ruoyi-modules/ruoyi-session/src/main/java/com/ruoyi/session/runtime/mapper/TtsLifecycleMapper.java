package com.ruoyi.session.runtime.mapper;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Persisted dispatch and billing intent; never stores speech text or provider secrets. */
public interface TtsLifecycleMapper
{
    int prepare(@Param("account") long account, @Param("session") long session,
        @Param("turn") long turn, @Param("ordinal") int ordinal, @Param("segment") String segment,
        @Param("epoch") long epoch, @Param("owner") String owner, @Param("deadline") Instant deadline);
    int dispatch(@Param("turn") long turn, @Param("ordinal") int ordinal,
        @Param("owner") String owner, @Param("epoch") long epoch);
    int reserved(@Param("turn") long turn, @Param("ordinal") int ordinal, @Param("reservation") long reservation);
    int finish(@Param("turn") long turn, @Param("ordinal") int ordinal,
        @Param("owner") String owner, @Param("success") boolean success);
    List<Finalization> pending(@Param("limit") int limit);
    int recover(@Param("owner") String owner, @Param("limit") int limit);
    List<Long> factsToRecover(@Param("owner") String owner, @Param("limit") int limit);
    int done(@Param("id") long id, @Param("outcome") String outcome);
    int retry(@Param("id") long id, @Param("outcome") String outcome, @Param("code") String code);
    record Finalization(long id, long accountId, long applicationId, long turnId, int ordinal,
        long inputChars, Long reservationId, boolean reservationKnown, String outcome, int attempts) {
        public String businessId() { return turnId + ":" + ordinal; }
    }
}
