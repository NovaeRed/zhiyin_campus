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

    /**
     * 仅将执行次数未到上限的失败任务重新排队；模型费用确认由服务层在调用前完成。
     *
     * @param id 待重试的失败任务标识
     * @return 实际更新行数，状态或次数不允许重试时为零
     */
    int retryJob(@Param("id") UUID id);

    int cancelJob(@Param("id") UUID id);

    /**
     * 按创建顺序领取一个未被其他事务锁定的排队任务；调用方须在同一短事务内标记开始执行。
     *
     * @return 至多一个已锁定任务，无可领取任务时为空列表
     */
    List<Map<String, Object>> lockNextQueuedJob();

    int startJob(@Param("instanceId") UUID instanceId, @Param("id") UUID id);

    /**
     * 仅更新仍在执行态任务的阶段和百分比，供工作线程在进度回调中感知取消或失效。
     *
     * @param phase    当前解析或持久化阶段标识
     * @param progress 当前整体进度百分比
     * @param id       正在执行的任务标识
     * @return 实际更新行数，为零时工作线程应停止继续执行
     */
    int updateProgress(
        @Param("phase") String phase, @Param("progress") int progress, @Param("id") UUID id);

    /**
     * 锁定任务行并读取当前状态，供发布事务复核取消与执行状态。
     *
     * @param id 需要锁定的入库任务标识
     * @return 持有行锁时的任务状态，记录不存在时为 {@code null}
     */
    String lockState(@Param("id") UUID id);

    /**
     * 写入完成结果和百分之百进度；调用方必须先锁定并复核任务状态，再与文档版本切换一同提交。
     *
     * @param result 包含新文档标识及切片信息的结果JSON
     * @param id     准备完成的入库任务标识
     * @return 实际更新的任务行数
     */
    int completeJob(@Param("result") String result, @Param("id") UUID id);

    int failJob(@Param("errorCode") String errorCode, @Param("id") UUID id);

    int renewLease(@Param("id") UUID id, @Param("instanceId") UUID instanceId);

    int expireJobs();
}
