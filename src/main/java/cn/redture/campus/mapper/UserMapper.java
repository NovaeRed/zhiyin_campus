package cn.redture.campus.mapper;

import cn.redture.campus.pojo.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserMapper extends BaseMapper<User> {

	User selectByStudentNo(@Param("studentNo") String studentNo);

    List<Long> selectIdsByStudentNos(@Param("studentNos") List<String> studentNos);
}

