package com.ruoyi.zhcp;

import com.ruoyi.zhcp.config.ZhcpProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ZhcpProperties.class)
public class ZhcpApplication {
    public static void main(String[] args) {
        SpringApplication.run(ZhcpApplication.class, args);
    }
}
