package com.example.reservation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 日期序列化契约：全站（含缓存）必须是 ISO 字符串，且旧缓存数组条目仍可读回 */
class JacksonDateSerializationTest {
    // 与 Spring Boot JacksonAutoConfiguration 行为对齐：裸 builder 默认不会禁用 WRITE_DATES_AS_TIMESTAMPS，
    // 生产环境真正生效的是 application.yml 的 spring.jackson.serialization.write-dates-as-timestamps: false
    private final ObjectMapper mapper = new Jackson2ObjectMapperBuilder()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

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
