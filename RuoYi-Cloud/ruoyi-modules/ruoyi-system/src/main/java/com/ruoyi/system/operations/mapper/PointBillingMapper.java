package com.ruoyi.system.operations.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PointBillingMapper
{
    record TtsPrice(long rateVersionId, long unitPriceCent, Long applicationId) { }
    TtsPrice ttsPrice(@Param("accountId") long accountId, @Param("requestKey") String requestKey);
    Long ttsReservationId(@Param("accountId") long accountId, @Param("businessId") String businessId);
    Map<String,Object> currentRate();
    Map<String,Object> latestRateForUpdate();
    List<Map<String,Object>> rates();
    int insertRate(@Param("id") long id,@Param("versionNo") long versionNo,
        @Param("generationActionCent") long generationActionCent,@Param("ttsCharacterCent") long ttsCharacterCent,
        @Param("storageByteCent") long storageByteCent,@Param("effectiveAt") LocalDateTime effectiveAt,
        @Param("operatorId") long operatorId);
    Map<String,Object> balanceForUpdate(@Param("accountId") long accountId);
    int insertBalance(@Param("accountId") long accountId,@Param("grantedCent") long grantedCent);
    int reserveBalance(@Param("accountId") long accountId,@Param("amountCent") long amountCent);
    int finishBalance(@Param("accountId") long accountId,@Param("reservedCent") long reservedCent,
        @Param("settledCent") long settledCent);
    int addGranted(@Param("accountId") long accountId,@Param("amountCent") long amountCent);
    Long activeAccountForUpdate(@Param("accountId") long accountId);
    Map<String,Object> reservation(@Param("accountId") long accountId,@Param("businessType") String businessType,
        @Param("businessId") String businessId,@Param("reservationNo") int reservationNo);
    Map<String,Object> latestReservationForUpdate(@Param("accountId") long accountId,
        @Param("businessType") String businessType,@Param("businessId") String businessId);
    Map<String,Object> reservationById(@Param("id") long id);
    Map<String,Object> reservationByIdForUpdate(@Param("id") long id);
    int insertReservation(@Param("id") long id,@Param("accountId") long accountId,
        @Param("businessType") String businessType,@Param("businessId") String businessId,
        @Param("reservationNo") int reservationNo,@Param("billingItem") String billingItem,
        @Param("measuredUnits") long measuredUnits,@Param("unitPriceCent") long unitPriceCent,
        @Param("rateVersionId") long rateVersionId,@Param("reservedCent") long reservedCent,
        @Param("requestKey") String requestKey,@Param("applicationId") Long applicationId);
    int finishReservation(@Param("id") long id,@Param("fromState") String fromState,@Param("state") String state,
        @Param("settledCent") long settledCent);
    int reviewReservation(@Param("id") long id);
    Map<String,Object> grantByKey(@Param("accountId") long accountId,@Param("eventKey") String eventKey);
    int insertEntry(@Param("id") long id,@Param("accountId") long accountId,@Param("reservationId") Long reservationId,
        @Param("eventKey") String eventKey,@Param("entryType") String entryType,
        @Param("deltaGrantedCent") long deltaGrantedCent,@Param("deltaUsedCent") long deltaUsedCent,
        @Param("deltaReservedCent") long deltaReservedCent,@Param("operatorId") Long operatorId,
        @Param("reason") String reason);
    Long nextId();
}
