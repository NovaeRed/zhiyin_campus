package cn.redture.campus.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * DocumentFileService 的数据库访问接口。
 */
@Mapper
public interface DocumentFileMapper {

    String selectFilePath(@Param("docId") Long docId);
}
