package com.ming.campustrade;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 应用启动上下文加载测试
 *
 * <p>验证 Spring 容器能否正常启动、所有 Bean 能否正确注入。
 * 如果项目依赖（如 Redis、MySQL）未就绪，此测试会失败，但不会影响其他 Service 层单元测试
 * （Service 测试使用 Mock 方式，不依赖真实容器和中间件）。</p>
 */
@SpringBootTest
class CampusTradeApplicationTests {

    @Test
    void contextLoads() {
        // 仅验证应用上下文加载成功
    }
}
