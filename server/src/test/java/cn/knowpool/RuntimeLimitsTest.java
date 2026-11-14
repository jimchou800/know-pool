package cn.knowpool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.knowpool.infrastructure.AdjustableCapacity;
import cn.knowpool.infrastructure.ApiFailure;
import cn.knowpool.infrastructure.ApplicationData;
import cn.knowpool.infrastructure.ModelLimits;
import cn.knowpool.infrastructure.RuntimeSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class RuntimeLimitsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private ApplicationData data;
    private RuntimeSettings settings;

    @BeforeEach
    void openSettings() throws Exception {
        Path root = Files.createDirectories(Path.of("target", "test-data").toAbsolutePath());
        data = new ApplicationData(Files.createTempDirectory(root, "runtime-limits-").toString());
        settings = new RuntimeSettings(data, JSON, new MockEnvironment());
    }

    @AfterEach
    void closeSettings() throws Exception {
        try {
            if (settings != null) settings.close();
        } finally {
            if (data != null) data.close();
        }
    }

    @Test
    @DisplayName("全量参数拒绝数值字符串、缺项及跨字段冲突，保留原值且不触发重建")
    void invalidReplacementPreservesSavedSettings() {
        ObjectNode previous = settings.get();
        ObjectNode wrongType = previous.deepCopy();
        wrongType.put("indexConcurrency", "2");
        ObjectNode missing = previous.deepCopy();
        missing.remove("queueCapacity");
        ObjectNode conflict = previous.deepCopy();
        conflict.put("maxUploadBytes", previous.path("maxFileBytes").asLong() - 1);
        AtomicInteger rebuilds = new AtomicInteger();

        for (ObjectNode invalid : java.util.List.of(wrongType, missing, conflict)) {
            assertThatThrownBy(() -> settings.update(invalid, rebuilds::incrementAndGet))
                    .isInstanceOfSatisfying(ApiFailure.class, failure -> {
                        assertThat(failure.status()).isEqualTo(400);
                        assertThat(failure.code()).isEqualTo("INVALID_REQUEST");
                    });
            assertThat(settings.get()).isEqualTo(previous);
            String stored = data.read(jdbc -> jdbc.sql(
                            "SELECT value FROM application_metadata WHERE name='runtime_settings'")
                    .query(String.class)
                    .single());
            assertThat(JSON.readTree(stored)).isEqualTo(previous);
        }
        assertThat(rebuilds.get()).isZero();
    }

    @Test
    @DisplayName("同端点模型的生成与嵌入共享并发额度，释放后可继续领取")
    void normalizedEndpointAndModelShareCapacity() {
        ObjectNode next = settings.get();
        ObjectNode rate = JSON.createObjectNode();
        rate.put("endpoint", "http://localhost:80/v1/");
        rate.put("model", "shared-model");
        rate.put("maxConcurrent", 1);
        rate.put("requestsPerMinute", 10);
        rate.put("tokensPerMinute", 1000);
        next.set("modelRateLimits", JSON.createArrayNode().add(rate));
        settings.update(next, () -> {});
        ModelLimits limits = new ModelLimits(settings);

        try (ModelLimits.Lease active = limits.acquire("http://LOCALHOST/v1", "shared-model", 10, false)) {
            assertThatThrownBy(() -> limits.acquire("http://localhost/v1/", "shared-model", 10, true))
                    .isInstanceOfSatisfying(ApiFailure.class, failure -> {
                        assertThat(failure.status()).isEqualTo(429);
                        assertThat(failure.code()).isEqualTo("QUEUE_FULL");
                    });
        }
        try (ModelLimits.Lease released = limits.acquire("http://localhost/v1", "shared-model", 10, true)) {
            assertThat(released).isNotNull();
        }
    }

    @Test
    @DisplayName("未配置端点模型限流时拒绝领取外发额度")
    void missingModelLimitRejectsLease() {
        ModelLimits limits = new ModelLimits(settings);
        assertThatThrownBy(() -> limits.acquire("http://localhost/v1", "unconfigured-model", 10, false))
                .isInstanceOfSatisfying(ApiFailure.class, failure -> {
                    assertThat(failure.status()).isEqualTo(422);
                    assertThat(failure.code()).isEqualTo("INVALID_STATE");
                });
    }

    @Test
    @DisplayName("调低容量保留两个在途占用，直到释放到新上限以下才允许新领取")
    void capacityReductionKeepsActiveReservations() {
        AtomicInteger maximum = new AtomicInteger(2);
        AdjustableCapacity capacity = new AdjustableCapacity(maximum::get);
        assertThat(capacity.tryAcquire()).isTrue();
        assertThat(capacity.tryAcquire()).isTrue();
        maximum.set(1);
        assertThat(capacity.tryAcquire()).isFalse();
        capacity.release();
        assertThat(capacity.tryAcquire()).isFalse();
        capacity.release();
        assertThat(capacity.tryAcquire()).isTrue();
        capacity.release();
    }
}
