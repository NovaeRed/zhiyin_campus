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

    /**
     * 按用户和请求键唯一插入运行态请求，并返回本次取得的执行声明；调用方需与会话占用检查放在同一事务中。
     *
     * @param id          本次尝试声明的新请求标识
     * @param userId      发起请求的用户标识
     * @param requestKey  用户级幂等键
     * @param requestHash 原始请求字段的指纹
     * @param instanceId  当前执行实例标识
     * @return 新插入请求的标识行；唯一键已存在时返回空列表
     */
    List<Map<String, Object>> claimRequest(
        @Param("id") UUID id,
        @Param("userId") Long userId,
        @Param("requestKey") String requestKey,
        @Param("requestHash") String requestHash,
        @Param("instanceId") UUID instanceId);

    /**
     * 锁定同一用户和请求键对应的现有请求，供指纹冲突检查及完成结果重放使用；必须在调用方事务内执行。
     *
     * @param userId     请求所属用户标识
     * @param requestKey 需要检查的幂等键
     * @return 锁定的请求状态及结果行，记录不存在时为 {@code null}
     */
    Map<String, Object> lockRequest(
        @Param("userId") Long userId, @Param("requestKey") String requestKey);

    List<Map<String, Object>> lockVisibleSession(
        @Param("sessionId") Long sessionId, @Param("userId") Long userId);

    /**
     * 获取会话级PostgreSQL事务咨询锁，串行化同一会话的检查与请求声明；事务结束后自动释放。
     *
     * @param sessionId 用作咨询锁键的会话标识
     * @return 锁表达式的执行结果
     */
    Boolean lockSession(@Param("sessionId") Long sessionId);

    Boolean hasRunningRequest(@Param("sessionId") Long sessionId);

    int attachQuery(
        @Param("sessionId") Long sessionId,
        @Param("queryId") Long queryId,
        @Param("id") UUID id);

    /**
     * 仅将仍处于运行态的请求写为完成并保存JSON回答，作为取消与发布竞态的最终条件更新。
     *
     * @param response 准备发布的完整回答JSON
     * @param id       本次执行的请求标识
     * @return 实际更新行数，为一表示本次完成状态写入成功
     */
    int completeRequest(@Param("response") String response, @Param("id") UUID id);

    int failRequest(@Param("errorCode") String errorCode, @Param("id") UUID id);

    int failQuery(
        @Param("requestId") UUID requestId,
        @Param("errorCode") String errorCode,
        @Param("queryId") Long queryId);

    String selectScope(@Param("sessionId") Long sessionId, @Param("userId") Long userId);

    List<Map<String, Object>> selectProfile(@Param("userId") Long userId);

    /**
     * 仅从当前用户所属会话读取最近三条已完成问答及生成文本，供服务层截断和指代理解。
     *
     * @param sessionId 需要读取历史的会话标识
     * @param userId    用于校验会话归属的用户标识
     * @return 当前用户会话中按查询时间倒序排列的已完成问答
     */
    List<Map<String, Object>> selectRecentTurns(
        @Param("sessionId") Long sessionId, @Param("userId") Long userId);

    int updateScope(@Param("scope") String scope, @Param("sessionId") Long sessionId);

    List<Map<String, Object>> selectStatus(
        @Param("userId") Long userId, @Param("requestKey") String requestKey);

    /**
     * 取消当前用户仍处于运行态的请求，并返回其关联查询标识；这是带返回行的状态写入查询。
     *
     * @param userId     请求所属用户标识
     * @param requestKey 需要取消的幂等键
     * @return 被取消行的关联查询标识列表；查询尚未关联时字段可为空，未命中时列表为空
     */
    List<Map<String, Object>> cancelRequest(
        @Param("userId") Long userId, @Param("requestKey") String requestKey);

    int cancelQuery(@Param("queryId") Long queryId);

    int timeoutRequest(@Param("id") UUID id);

    int timeoutQuery(@Param("requestId") UUID requestId);

    int renewLease(@Param("id") UUID id, @Param("instanceId") UUID instanceId);

    /**
     * 将租约已过期的运行请求标为失败，并返回关联查询以同步查询终态。
     *
     * @return 已失败请求的关联查询标识列表，尚未关联的查询字段可为空
     */
    List<Map<String, Object>> expireRequests();

    int interruptQuery(@Param("queryId") Long queryId);
}
