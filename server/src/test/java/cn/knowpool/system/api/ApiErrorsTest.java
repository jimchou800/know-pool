package cn.knowpool.system.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiErrorsTest {
    @Test
    @DisplayName("未提交的异常仍返回脱敏的统一错误")
    void uncommittedFailureKeepsSanitizedJson() {
        var response = new MockHttpServletResponse();
        var actual = new ApiErrors().unexpected(new IllegalStateException("固定凭据和资料正文不得外泄"), response);
        assertThat(actual).isNotNull();
        assertThat(actual.getStatusCode().value()).isEqualTo(500);
        assertThat(actual.getHeaders().getContentType()).isEqualTo(org.springframework.http.MediaType.APPLICATION_JSON);
        assertThat(actual.getBody()).containsEntry("code", "INTERNAL_ERROR");
        assertThat(actual.getBody().toString()).doesNotContain("固定凭据", "资料正文");
    }

    @Test
    @DisplayName("已提交的事件流异常不再写入统一错误正文")
    void committedStreamIsNotRewritten() throws Exception {
        var response = new MockHttpServletResponse();
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("data:固定阶段通知\n\n");
        response.flushBuffer();
        assertThat(new ApiErrors().unexpected(new IllegalStateException("固定断流异常"), response))
                .isNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("data:固定阶段通知\n\n");
        assertThat(response.getContentType()).startsWith("text/event-stream");
    }
}
