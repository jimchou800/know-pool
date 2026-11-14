package cn.knowpool.knowledgebases;

import java.io.IOException;
import java.time.Duration;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class VectorCollections {
    private final JsonMapper json;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(Duration.ofSeconds(3))
            .callTimeout(Duration.ofSeconds(5))
            .build();

    public VectorCollections(JsonMapper json) {
        this.json = json;
    }

    JsonNode request(String endpoint, String secret, String collection, String method, String body) throws IOException {
        Request.Builder builder =
                new Request.Builder().url(endpoint.replaceAll("/+$", "") + (collection==null?"/collections":"/collections/"+collection));
        if (secret != null) builder.header("api-key", secret);
        builder.method(method, body == null ? null : RequestBody.create(body, MediaType.get("application/json")));
        try (Response response = http.newCall(builder.build()).execute()) {
            if (method.equals("GET") && response.code() == 404) return null;
            var responseBody = response.body();
            if (response.code() != 200 || responseBody == null)
                throw new Failure(
                        "向量服务拒绝操作。", response.code() == 408 || response.code() == 429 || response.code() >= 500);
            String contentType = response.header("Content-Type");
            if (contentType == null || !contentType.split(";", 2)[0].equalsIgnoreCase("application/json"))
                throw new Failure("向量服务响应格式错误。", false);
            byte[] bytes = responseBody.byteStream().readNBytes(262145);
            if (bytes.length > 262144) throw new Failure("向量服务响应超过限制。", false);
            JsonNode value = json.readTree(bytes);
            if (!value.path("status").asText().equals("ok")) throw new Failure("向量服务响应无效。", false);
            return value;
        }
    }

    static final class Failure extends IOException {
        private final boolean retryable;

        Failure(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        boolean retryable() {
            return retryable;
        }
    }

    static String distance(String value) {
        return switch (value) {
            case "cosine" -> "Cosine";
            case "dot" -> "Dot";
            default -> "Euclid";
        };
    }
}
