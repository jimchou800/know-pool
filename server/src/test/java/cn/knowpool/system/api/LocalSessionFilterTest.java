package cn.knowpool.system.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class LocalSessionFilterTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void 会话过期重新初始化且容量有界() throws Exception {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-12T00:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        LocalSessionFilter filter = new LocalSessionFilter("", JSON, clock);
        MockHttpServletResponse first = initialize(filter, null, true);
        assertThat(first.getHeader("Set-Cookie")).contains("Secure");
        Cookie cookie = new Cookie(
                "KNOW_POOL_SESSION",
                first.getHeader("Set-Cookie").split(";", 2)[0].split("=", 2)[1]);
        now.set(Instant.parse("2026-11-13T00:00:00Z"));
        MockHttpServletResponse renewed = initialize(filter, cookie, false);
        assertThat(renewed.getHeader("Set-Cookie")).doesNotContain(cookie.getValue());
        for (int i = 0; i < 127; i++) {
            assertThat(initialize(filter, null, false).getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse full = initialize(filter, null, false);
        assertThat(full.getStatus()).isEqualTo(429);
        assertThat(JSON.readTree(full.getContentAsString()).get("code").asText())
                .isEqualTo("QUEUE_FULL");
        now.set(Instant.parse("2026-11-14T00:00:00Z"));
        assertThat(initialize(filter, null, false).getStatus()).isEqualTo(200);
    }

    @Test
    void 恶意或缺失主机和伪造来源不进入业务链() throws Exception {
        LocalSessionFilter filter = new LocalSessionFilter("", JSON);
        for (String host : new String[] {null, "[", "example.com", "127.0.0.1:1"}) {
            MockHttpServletRequest request = request(false);
            request.removeHeader("Host");
            if (host != null) {
                request.addHeader("Host", host);
            }
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> {
                throw new AssertionError("拒绝的请求不得进入业务链");
            });
            assertThat(response.getStatus()).isEqualTo(403);
        }
    }

    private static MockHttpServletResponse initialize(LocalSessionFilter filter, Cookie cookie, boolean secure)
            throws Exception {
        MockHttpServletRequest request = request(secure);
        if (cookie != null) {
            request.setCookies(cookie);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> assertThat(req.getAttribute("localSession"))
                .isInstanceOf(LocalSessionFilter.Session.class));
        return response;
    }

    private static MockHttpServletRequest request(boolean secure) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/session");
        request.setSecure(secure);
        request.setScheme(secure ? "https" : "http");
        request.setLocalPort(8080);
        request.addHeader("Host", "127.0.0.1:8080");
        return request;
    }
}
