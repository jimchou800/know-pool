package cn.knowpool.system.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class LocalSessionFilter extends OncePerRequestFilter {
    private static final String COOKIE = "KNOW_POOL_SESSION";
    private static final String SESSION_PATH = "/api/v1/session";
    private static final Duration SESSION_DURATION = Duration.ofHours(8);
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Set<String> allowedOrigins;
    private final JsonMapper json;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public LocalSessionFilter(@Value("${know-pool.allowed-origins:}") String origins, JsonMapper json) {
        this(origins, json, Clock.systemUTC());
    }

    LocalSessionFilter(String origins, JsonMapper json, Clock clock) {
        this.allowedOrigins = origins.isBlank() ? Set.of() : Set.copyOf(Arrays.asList(origins.split(",")));
        this.json = json;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        response.setHeader("X-Request-Id", requestId);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader(
                "Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'");
        if (!validSource(request)) {
            error(response, 403, "ORIGIN_REJECTED", "请求来源不受允许。", requestId);
            return;
        }
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        boolean initialize = path.equals(SESSION_PATH) && request.getMethod().equals("GET");
        Session session = findSession(request);
        if (initialize) {
            if (request.getQueryString() != null) {
                error(response, 400, "INVALID_REQUEST", "该操作不接受查询参数。", requestId);
                return;
            }
            if (session == null) {
                session = newSession();
                if (session == null) {
                    error(response, 429, "QUEUE_FULL", "本机会话数量已达上限，请稍后重试。", requestId);
                    return;
                }
            }
            response.setHeader(
                    "Set-Cookie",
                    ResponseCookie.from(COOKIE, session.sessionId())
                            .httpOnly(true)
                            .sameSite("Strict")
                            .path("/")
                            .secure(request.isSecure())
                            .maxAge(SESSION_DURATION)
                            .build()
                            .toString());
            request.setAttribute("localSession", session);
            chain.doFilter(request, response);
            return;
        }
        if (session == null) {
            error(response, 401, "SESSION_EXPIRED", "本机会话已失效，请重新连接。", requestId);
            return;
        }
        if (!READ_METHODS.contains(request.getMethod())) {
            String token = request.getHeader("X-CSRF-Token");
            if (token == null
                    || !MessageDigest.isEqual(
                            token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            session.csrfToken().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                error(response, 403, "CSRF_REJECTED", "请求保护信息缺失或已失效。", requestId);
                return;
            }
        }
        if (path.equals("/api/v1/system/status") && request.getMethod().equals("GET")) {
            if (request.getQueryString() != null) {
                error(response, 400, "INVALID_REQUEST", "该操作不接受查询参数。", requestId);
                return;
            }
            try {
                chain.doFilter(request, response);
            } catch (RuntimeException | ServletException failure) {
                if (response.isCommitted()) {
                    throw failure;
                }
                response.resetBuffer();
                error(response, 500, "INTERNAL_ERROR", "读取运行状态失败，请稍后重试。", requestId);
            }
            return;
        }
        boolean collection =
                path.equals("/api/v1/datasets") && Set.of("GET", "POST").contains(request.getMethod());
        boolean resource =
                path.matches("/api/v1/datasets/[^/]+") && Set.of("GET", "PATCH").contains(request.getMethod());
        boolean fileOperation = path.matches("/api/v1/datasets/[^/]+/files:upload")
                        && request.getMethod().equals("POST")
                || path.matches("/api/v1/files/[^/]+(?:/latest/(?:preview|download))?")
                        && request.getMethod().equals("GET")
                || path.matches("/api/v1/datasets/[^/]+/files")
                        && request.getMethod().equals("GET")
                || path.matches("/api/v1/files/[^/]+/name")
                        && request.getMethod().equals("PATCH")
                || path.matches("/api/v1/files/[^/]+/versions")
                        && request.getMethod().equals("GET");
        boolean settings = path.matches("/api/v1/settings/models/(?:generation|embedding)")
                        && Set.of("GET", "PUT").contains(request.getMethod())
                || path.equals("/api/v1/settings/vector-store")
                        && Set.of("GET", "PUT").contains(request.getMethod())
                || path.matches("/api/v1/settings/models/embedding/versions(?:/[^/]+)?")
                        && request.getMethod().equals("GET")
                || path.matches("/api/v1/settings/models/embedding/versions/[^/]+/credential")
                        && request.getMethod().equals("PUT")
                || path.matches("/api/v1/settings/models/(?:generation|embedding)/test")
                        && request.getMethod().equals("POST")
                || path.equals("/api/v1/settings/vector-store/test")
                        && request.getMethod().equals("POST")
                || path.matches("/api/v1/tasks/[^/]+(?:/connection-test|/events)?")
                        && request.getMethod().equals("GET");
        boolean knowledgeBases =
                path.equals("/api/v1/knowledge-bases") && Set.of("GET", "POST").contains(request.getMethod())
                        || path.matches("/api/v1/knowledge-bases/[^/]+")
                                && Set.of("GET", "PATCH").contains(request.getMethod())
                        || path.matches("/api/v1/knowledge-bases/[^/]+/(?:processing-config|sources|files|tasks)")
                                && request.getMethod().equals("GET")
                        || path.matches("/api/v1/knowledge-bases/[^/]+/sources")
                                && request.getMethod().equals("POST");
        boolean search = path.matches("/api/v1/knowledge-bases/[^/]+/search-test")
                        && request.getMethod().equals("POST")
                || path.matches("/api/v1/search-requests/[^/]+(?:/evidence/[^/]+)?")
                        && request.getMethod().equals("GET");
        if (collection || resource || fileOperation || settings || knowledgeBases || search) {
            request.setAttribute("localSession", session);
            chain.doFilter(request, response);
            return;
        }
        error(response, 404, "NOT_FOUND", "请求的操作不存在或尚未实现。", requestId);
    }

    private boolean validSource(HttpServletRequest request) {
        String host = request.getHeader("Host");
        if (host == null || host.contains(",") || host.contains("@")) {
            return false;
        }
        String sameOrigin = request.getScheme() + "://" + host;
        try {
            URI address = URI.create(sameOrigin);
            String hostname = address.getHost();
            boolean loopback = "127.0.0.1".equals(hostname) || "localhost".equals(hostname) || "[::1]".equals(hostname);
            int port = address.getPort() == -1 ? (request.isSecure() ? 443 : 80) : address.getPort();
            if (!loopback || (port != request.getLocalPort() && !allowedOrigins.contains(sameOrigin))) {
                return false;
            }
            String origin = request.getHeader("Origin");
            return origin == null || origin.equals(sameOrigin) || allowedOrigins.contains(origin);
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private Session findSession(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE.equals(cookie.getName())) {
                Session session = sessions.get(cookie.getValue());
                if (session != null && Instant.parse(session.expiresAt()).isAfter(clock.instant())) {
                    return session;
                }
            }
        }
        return null;
    }

    private synchronized Session newSession() {
        sessions.values()
                .removeIf(session -> !Instant.parse(session.expiresAt()).isAfter(clock.instant()));
        if (sessions.size() >= 128) {
            return null;
        }
        byte[] token = new byte[32];
        random.nextBytes(token);
        Session session = new Session(
                UUID.randomUUID().toString(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(token),
                clock.instant().plus(SESSION_DURATION).toString());
        sessions.put(session.sessionId(), session);
        return session;
    }

    private void error(HttpServletResponse response, int status, String code, String message, String requestId)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        json.writeValue(
                response.getOutputStream(),
                Map.of(
                        "requestId",
                        requestId,
                        "code",
                        code,
                        "message",
                        message,
                        "fieldErrors",
                        List.of(),
                        "retryable",
                        status == 429 || status >= 500));
    }

    public record Session(String sessionId, String csrfToken, String expiresAt) {}
}
