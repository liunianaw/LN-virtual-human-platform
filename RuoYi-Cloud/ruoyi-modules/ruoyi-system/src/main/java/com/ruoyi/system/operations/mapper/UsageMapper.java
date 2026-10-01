package com.ruoyi.system.operations.mapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UsageMapper
{
    List<Map<String,Object>> daily(@Param("accountId") long accountId, @Param("applicationId") Long applicationId,
        @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("capability") String capability,
        @Param("limit") int limit, @Param("offset") int offset);
    long dailyCount(@Param("accountId") long accountId, @Param("applicationId") Long applicationId,
        @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("capability") String capability);
    List<Map<String,Object>> calls(@Param("accountId") long accountId, @Param("applicationId") Long applicationId,
        @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("capability") String capability,
        @Param("status") String status, @Param("limit") int limit, @Param("offset") int offset);
    long callCount(@Param("accountId") long accountId, @Param("applicationId") Long applicationId,
        @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("capability") String capability,
        @Param("status") String status);
    Map<String,Object> limits(@Param("accountId") long accountId);
    List<Map<String,Object>> balances(@Param("accountId") long accountId);
    List<Map<String,Object>> reservations(@Param("accountId") long accountId, @Param("state") String state,
        @Param("limit") int limit, @Param("offset") int offset);
    long reservationCount(@Param("accountId") long accountId, @Param("state") String state);
    Map<String,Object> platformOverview(@Param("from") LocalDate from, @Param("to") LocalDate to);
    List<Map<String,Object>> platformCapabilityUsage(@Param("from") LocalDate from, @Param("to") LocalDate to);
    List<Map<String,Object>> adminAccounts(@Param("keyword") String keyword, @Param("from") LocalDate from,
        @Param("to") LocalDate to, @Param("limit") int limit, @Param("offset") int offset);
    long adminAccountCount(@Param("keyword") String keyword);
    Map<String,Object> adminAccount(@Param("accountId") long accountId);
    Map<String,Object> adminAccountSummary(@Param("accountId") long accountId,
        @Param("from") LocalDate from, @Param("to") LocalDate to);
    List<Map<String,Object>> adminAccountCapabilityUsage(@Param("accountId") long accountId,
        @Param("from") LocalDate from, @Param("to") LocalDate to);
    Long lockAccount(@Param("accountId") long accountId);
    int deleteDaily(@Param("accountId") long accountId, @Param("date") LocalDate date);
    int rebuildDaily(@Param("accountId") long accountId, @Param("date") LocalDate date);
    Map<String,Object> reservation(@Param("id") long id);
    Map<String,Object> reservationForUpdate(@Param("id") long id);
    Map<String,Object> balanceForUpdate(@Param("accountId") long accountId, @Param("quotaType") String quotaType);
    int finishReservation(@Param("id") long id, @Param("state") String state,
        @Param("settledUnits") long settledUnits);
    int finishBalance(@Param("accountId") long accountId, @Param("quotaType") String quotaType,
        @Param("reservedUnits") long reservedUnits, @Param("settledUnits") long settledUnits);
    int insertReviewEntry(@Param("accountId") long accountId, @Param("quotaType") String quotaType,
        @Param("reservationId") long reservationId, @Param("entryType") String entryType,
        @Param("reservedUnits") long reservedUnits, @Param("settledUnits") long settledUnits,
        @Param("operatorId") long operatorId, @Param("reason") String reason);
    Long activeAccount(@Param("accountId") long accountId);
    Long lockActiveAccount(@Param("accountId") long accountId);
    int insertLimits(@Param("accountId") long accountId, @Param("maxFileBytes") long maxFileBytes,
        @Param("maxSessions") int maxSessions, @Param("maxGenerationTasks") int maxGenerationTasks,
        @Param("maxTurns") int maxTurns);
    int updateLimits(@Param("accountId") long accountId, @Param("revision") long revision,
        @Param("maxFileBytes") long maxFileBytes, @Param("maxSessions") int maxSessions,
        @Param("maxGenerationTasks") int maxGenerationTasks, @Param("maxTurns") int maxTurns);
    Map<String,Object> grantByKey(@Param("accountId") long accountId, @Param("eventKey") String eventKey);
    Map<String,Object> quotaBalanceForUpdate(@Param("accountId") long accountId, @Param("quotaType") String quotaType);
    int insertQuotaBalance(@Param("accountId") long accountId, @Param("quotaType") String quotaType,
        @Param("units") long units);
    int addGrantedUnits(@Param("accountId") long accountId, @Param("quotaType") String quotaType,
        @Param("units") long units);
    int insertGrantEntry(@Param("accountId") long accountId, @Param("quotaType") String quotaType,
        @Param("eventKey") String eventKey, @Param("units") long units,
        @Param("operatorId") long operatorId, @Param("reason") String reason);
}
