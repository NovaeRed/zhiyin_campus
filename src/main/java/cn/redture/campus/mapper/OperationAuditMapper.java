package cn.redture.campus.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * OperationAuditService 的数据库访问接口。
 */
@Mapper
public interface OperationAuditMapper {

    int insertEvent(
        @Param("actorId") Long actorId,
        @Param("action") String action,
        @Param("objectType") String objectType,
        @Param("objectId") String objectId,
        @Param("detail") String detail);
}
