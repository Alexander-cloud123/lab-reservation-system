package com.example.reservation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 日期序列化契约：断言运行期真实 mapper（Boot 自动配置），而非测试自建的 mapper；
 *  ISO 默认由 Spring Boot JacksonAutoConfiguration 提供，测试直接注入运行期实例 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)   // 不绑端口，与在跑的 8080 不冲突
class JacksonDateSerializationTest {

    @Autowired
    private ObjectMapper mapper;

    // 以下三条断言文本保持不变
    @Test
    void localDateShouldSerializeAsIsoString() throws Exception {
        String json = mapper.writeValueAsString(Map.of("reserveDate", LocalDate.of(2026, 9, 18)));
        assertThat(json).contains("\"2026-09-18\"").doesNotContain("[2026");
    }

    @Test
    void localDateTimeShouldSerializeAsIsoString() throws Exception {
        String json = mapper.writeValueAsString(Map.of("t", LocalDateTime.of(2026, 9, 17, 16, 32, 59)));
        assertThat(json).contains("\"2026-09-17T16:32:59\"");
    }

    @Test
    void legacyArrayFormInCacheShouldStillDeserialize() throws Exception {
        assertThat(mapper.readValue("[2026,9,18]", LocalDate.class)).isEqualTo(LocalDate.of(2026, 9, 18));
    }
}
