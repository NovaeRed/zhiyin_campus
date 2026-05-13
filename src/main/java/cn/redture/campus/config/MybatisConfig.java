package cn.redture.campus.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("cn.redture.campus.mapper")
public class MybatisConfig {
}
