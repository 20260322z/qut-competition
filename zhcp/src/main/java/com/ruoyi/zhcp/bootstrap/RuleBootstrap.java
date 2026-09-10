package com.ruoyi.zhcp.bootstrap;

import com.ruoyi.zhcp.store.Store;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class RuleBootstrap implements CommandLineRunner {
    private final Store store;

    public RuleBootstrap(Store store) {
        this.store = store;
    }

    @Override
    public void run(String... args) throws Exception {
        String json = new String(new ClassPathResource("rules/xinkong-2024.json").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        store.saveRuleIfAbsent("xinkong-2024", "信息与控制工程学院",
                "信息与控制工程学院2024级学生综合素质评价办法实施细则", json);
    }
}
