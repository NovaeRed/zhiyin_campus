package cn.redture.campus.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * WorkflowManagementService 的数据库访问接口。
 */
@Mapper
public interface WorkflowManagementMapper {

    List<Map<String, Object>> selectUsers(@Param("limit") int limit, @Param("offset") int offset);

    Boolean lockAdministratorChanges();

    List<Map<String, Object>> lockUser(@Param("userId") Long userId);

    int updateUser(
        @Param("role") String role,
        @Param("active") Boolean active,
        @Param("userId") Long userId);

    List<Map<String, Object>> selectProfile(@Param("userId") Long userId);

    int upsertProfile(
        @Param("userId") Long userId,
        @Param("studentNo") String studentNo,
        @Param("major") String major,
        @Param("grade") Integer grade,
        @Param("cohort") String cohort);

    List<Map<String, Object>> lockSession(
        @Param("sessionId") Long sessionId, @Param("userId") Long userId);

    Boolean hasRunningRequest(@Param("sessionId") Long sessionId);

    int updateSession(
        @Param("title") String title,
        @Param("archived") Boolean archived,
        @Param("sessionId") Long sessionId,
        @Param("userId") Long userId);

    int updateScope(@Param("scope") String scope, @Param("sessionId") Long sessionId);

    List<Map<String, Object>> lockSessions(
        @Param("userId") Long userId, @Param("ids") Collection<Long> ids);

    Boolean hasRunningRequests(@Param("ids") Collection<Long> ids);

    int deleteSessions(@Param("userId") Long userId, @Param("ids") Collection<Long> ids);

    List<Map<String, Object>> selectArchived(
        @Param("userId") Long userId, @Param("limit") int limit, @Param("offset") int offset);

    List<Map<String, Object>> selectScope(
        @Param("sessionId") Long sessionId, @Param("userId") Long userId);

    Boolean ownsCompletedQuery(@Param("queryId") Long queryId, @Param("userId") Long userId);

    int upsertFeedback(
        @Param("queryId") Long queryId,
        @Param("userId") Long userId,
        @Param("rating") int rating,
        @Param("comment") String comment);

    List<Map<String, Object>> selectAudits(@Param("limit") int limit, @Param("offset") int offset);

    List<Map<String, Object>> lockDocument(@Param("docId") Long docId);

    int updateDocument(
        @Param("title") String title,
        @Param("version") String version,
        @Param("docId") Long docId);

    int updateDocumentMetadata(@Param("metadata") String metadata, @Param("docId") Long docId);

    int updateChunkMetadata(
        @Param("major") String major,
        @Param("grade") Integer grade,
        @Param("metadata") String metadata,
        @Param("docId") Long docId);
}
