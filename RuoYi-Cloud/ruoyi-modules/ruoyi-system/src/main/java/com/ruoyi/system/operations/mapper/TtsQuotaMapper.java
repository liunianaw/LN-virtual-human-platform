package com.ruoyi.system.operations.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TtsQuotaMapper
{
    String applicationPurpose(@Param("accountId") long accountId, @Param("applicationId") long applicationId);
    record Reservation(long id, long reservedUnits, String state) { }
    Long balanceForUpdate(@Param("accountId") long accountId);
    Reservation reservation(@Param("accountId") long accountId, @Param("businessId") String businessId);
    Reservation reservationForUpdate(@Param("accountId") long accountId, @Param("businessId") String businessId);
    int reserveBalance(@Param("accountId") long accountId, @Param("units") long units);
    int finishBalance(@Param("accountId") long accountId, @Param("units") long units, @Param("usedUnits") long usedUnits);
    void insertReservation(@Param("id") long id, @Param("accountId") long accountId,
        @Param("businessId") String businessId, @Param("units") long units);
    int finishReservation(@Param("id") long id, @Param("state") String state, @Param("settledUnits") long settledUnits);
    int reviewReservation(@Param("id") long id);
    void insertEntry(@Param("id") long id, @Param("accountId") long accountId,
        @Param("reservationId") long reservationId, @Param("eventKey") String eventKey,
        @Param("entryType") String entryType, @Param("usedUnits") long usedUnits,
        @Param("reservedUnits") long reservedUnits);
    Long nextId();
}
