package com.hewei.hzyjy.xunzhi.actuator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = ActuatorHealthEndpointTest.TestApplication.class,
        properties = {
                "management.endpoints.web.exposure.include=health",
                "management.endpoint.health.probes.enabled=true",
                "management.health.db.enabled=false",
                "management.health.mongo.enabled=false",
                "management.health.redis.enabled=false",
                "spring.profiles.include=",
                "spring.autoconfigure.exclude=cn.dev33.satoken.dao.SaTokenDaoRedisJackson,org.redisson.spring.starter.RedissonAutoConfigurationV2,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,com.yomahub.liteflow.springboot.config.LiteflowMainAutoConfiguration,com.yomahub.liteflow.springboot.config.LiteflowPropertyAutoConfiguration",
                "spring.data.redis.password=",
                "spring.data.redis.host=127.0.0.1",
                "spring.data.redis.port=6379"
        }
)
@AutoConfigureMockMvc
class ActuatorHealthEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpointShouldBeExposed() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @SpringBootApplication
    static class TestApplication {
    }
}
