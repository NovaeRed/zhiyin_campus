package cn.redture.campus.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ReliableRagService 的数据库访问接口。
 */
@Mapper
public interface ReliableRagMapper {

    List<Map<String, Object>> claimRequest(
        @Param("id") UUID id,
        @Param("userId") Long userId,
        @Param("requestKey") String requestKey,
        @Param("requestHash") String requestHash,
        @Param("instanceId") UUID instanceId);

    Map<String, Object> lockRequest(
        @Param("userId") Long userId, @Param("requestKey") String requestKey);

    List<Map<String, Object>> lockVisibleSession(
        @Param("sessionId") Long sessionId, @Param("userId") Long userId);

    Boolean lockSession(@Param("sessionId") Long sessionId);

    Boolean hasRunningRequest(@Param("sessionId") Long sessionId);

    int attachQuery(
        @Param("sessionId") Long sessionId,
        @Param("queryId") Long queryId,
        @Param("id") UUID id);

    int completeRequest(@Param("response") String response, @Param("id") UUID id);

    int failRequest(@Param("errorCode") String errorCode, @Param("id") UUID id);

    int failQuery(
        @Param("requestId") UUID requestId,
        @Param("errorCode") String errorCode,
        @Param("queryId") Long queryId);

    String selectScope(@Param("sessionId") Long sessionId, @Param("userId") Long userId);

    List<Map<String, Object>> selectProfile(@Param("userId") Long userId);

    List<Map<String, Object>> selectRecentTurns(
        @Param("sessionId") Long sessionId, @Param("userId") Long userId);

    int updateScope(@Param("scope") String scope, @Param("sessionId") Long sessionId);

    List<Map<String, Object>> selectStatus(
        @Param("userId") Long userId, @Param("requestKey") String requestKey);

    List<Map<String, Object>> cancelRequest(
        @Param("userId") Long userId, @Param("requestKey") String requestKey);

    int cancelQuery(@Param("queryId") Long queryId);

    int timeoutRequest(@Param("id") UUID id);

    int timeoutQuery(@Param("requestId") UUID requestId);

    int renewLease(@Param("id") UUID id, @Param("instanceId") UUID instanceId);

    List<Map<String, Object>> expireRequests();

    int interruptQuery(@Param("queryId") Long queryId);
}
