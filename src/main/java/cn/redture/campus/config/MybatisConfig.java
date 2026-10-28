package cn.redture.campus.config;

import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("cn.redture.campus.mapper")
public class MybatisConfig {

    @Bean
    public ConfigurationCustomizer preserveNullMapColumns() {
        // 保留Map中值为null的列，使查询结果包含完整字段。
        return configuration -> {
            configuration.setCallSettersOnNulls(true);
            // 全部列为空的真实结果行仍应保留，例如尚未绑定查询的过期请求。
            configuration.setReturnInstanceForEmptyRow(true);
        };
    }
}
