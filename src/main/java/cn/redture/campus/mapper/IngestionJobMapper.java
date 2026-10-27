package cn.redture.campus.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * IngestionJobService 的数据库访问接口。
 */
@Mapper
public interface IngestionJobMapper {

    List<Map<String, Object>> lockDocumentVersion(@Param("docId") Long docId);

    int insertJob(
        @Param("id") UUID id,
        @Param("userId") Long userId,
        @Param("sourcePath") String sourcePath,
        @Param("originalName") String originalName,
        @Param("request") String request,
        @Param("rebuildOf") Long rebuildOf);

    List<Map<String, Object>> selectStatus(
        @Param("id") UUID id, @Param("userId") Long userId, @Param("admin") boolean admin);

    int retryJob(@Param("id") UUID id);

    int cancelJob(@Param("id") UUID id);

    List<Map<String, Object>> lockNextQueuedJob();

    int startJob(@Param("instanceId") UUID instanceId, @Param("id") UUID id);

    int updateProgress(
        @Param("phase") String phase, @Param("progress") int progress, @Param("id") UUID id);

    String lockState(@Param("id") UUID id);

    int completeJob(@Param("result") String result, @Param("id") UUID id);

    int failJob(@Param("errorCode") String errorCode, @Param("id") UUID id);

    int renewLease(@Param("id") UUID id, @Param("instanceId") UUID instanceId);

    int expireJobs();
}
