package cn.knowpool.system;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.LogLevel;
import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class ConnectionProbe {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.knowpool.infrastructure.RuntimeSettings runtime;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.knowpool.infrastructure.ModelLimits modelLimits;

    private static final int RESPONSE_LIMIT = 262144;
    private final JsonMapper json;

    public ConnectionProbe(JsonMapper json) {
        this.json = json;
    }

    boolean test(ConnectionTests.ProbeSnapshot snapshot) {
        try (var modelLease = snapshot.kind().equals("vector") || modelLimits == null
                        ? null
                        : modelLimits.acquire(
                                snapshot.endpoint(),
                                snapshot.model(),
                                snapshot.kind().equals("generation") ? 1024 : 64,
                                snapshot.kind().equals("embedding"));
                Transport transport = new Transport(runtime == null ? 5 : runtime.number("modelTimeoutSeconds"))) {
            if (snapshot.kind().equals("vector")) {
                Request.Builder request = new Request.Builder()
                        .url(base(snapshot.endpoint()) + "/collections")
                        .get();
                if (snapshot.secret() != null) request.header("api-key", snapshot.secret());
                try (Response response = transport.http.newCall(request.build()).execute()) {
                    byte[] body = bounded(response);
                    if (response.code() != 200) return false;
                    JsonNode payload = json.readTree(body);
                    return payload.isObject()
                            && payload.path("result").isObject()
                            && payload.path("result").path("collections").isArray()
                            && payload.path("status").asText().equals("ok");
                }
            }
            ClientOptions options = ClientOptions.builder()
                    .httpClient(transport)
                    .baseUrl(base(snapshot.endpoint()))
                    .apiKey(snapshot.secret())
                    .maxRetries(0)
                    .logLevel(LogLevel.OFF)
                    .responseValidation(true)
                    .timeout(Duration.ofSeconds(runtime == null ? 5 : runtime.number("modelTimeoutSeconds")))
                    .build();
            OpenAIClient client = new OpenAIClientImpl(options);
            if (snapshot.kind().equals("generation")) {
                OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
                builder.model(snapshot.model());
                builder.maxTokens((int) Math.min(16, snapshot.maxOutputTokens()));
                builder.extraBody(java.util.Map.of("stream", false));
                OpenAiChatOptions chatOptions = builder.build();
                var response = OpenAiChatModel.builder()
                        .openAiClient(client)
                        .openAiClientAsync(client.async())
                        .options(chatOptions)
                        .build()
                        .call(new Prompt("请回复连接正常。"));
                return response.getResults() != null
                        && !response.getResults().isEmpty()
                        && response.getResults().stream()
                                .anyMatch(result -> result.getOutput() != null
                                        && result.getOutput().getText() != null
                                        && !result.getOutput().getText().isBlank());
            }
            OpenAiEmbeddingOptions embeddingOptions = OpenAiEmbeddingOptions.builder()
                    .model(snapshot.model())
                    .encodingFormat(OpenAiEmbeddingOptions.EncodingFormat.FLOAT)
                    .build();
            var response = OpenAiEmbeddingModel.builder()
                    .openAiClient(client)
                    .options(embeddingOptions)
                    .build()
                    .call(new EmbeddingRequest(List.of("连接测试"), embeddingOptions));
            if (response.getResults().size() != 1) return false;
            Embedding embedding = response.getResults().getFirst();
            if (embedding.getIndex() != 0 || embedding.getOutput() == null || embedding.getOutput().length == 0)
                return false;
            for (float value : embedding.getOutput()) if (!Float.isFinite(value)) return false;
            return true;
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private static String base(String endpoint) {
        return endpoint.replaceAll("/+$", "");
    }

    private static byte[] bounded(Response response) throws IOException {
        okhttp3.ResponseBody responseBody = response.body();
        if (responseBody == null) throw new IOException("连接响应正文缺失。");
        if (responseBody.contentLength() > RESPONSE_LIMIT) throw new IOException("连接响应超出大小限制。");
        try (InputStream stream = responseBody.byteStream()) {
            byte[] body = stream.readNBytes(RESPONSE_LIMIT + 1);
            if (body.length > RESPONSE_LIMIT) throw new IOException("连接响应超出大小限制。");
            return body;
        }
    }

    static final class Transport implements com.openai.core.http.HttpClient {
        private final java.util.function.Consumer<byte[]> validateResponse;

        Transport() {
            this(body -> {}, 5);
        }

        Transport(long seconds) {
            this(body -> {}, seconds);
        }

        Transport(java.util.function.Consumer<byte[]> validateResponse) {
            this(validateResponse, 5);
        }

        Transport(java.util.function.Consumer<byte[]> validateResponse, long seconds) {
            this.validateResponse = validateResponse;
            long bounded = Math.min(Integer.MAX_VALUE / 1000, seconds);
            this.http = new OkHttpClient.Builder()
                    .retryOnConnectionFailure(false)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .connectTimeout(Duration.ofSeconds(bounded))
                    .readTimeout(Duration.ofSeconds(bounded))
                    .writeTimeout(Duration.ofSeconds(bounded))
                    .callTimeout(Duration.ofSeconds(bounded))
                    .build();
        }

        private final OkHttpClient http;

        @Override
        public HttpResponse execute(HttpRequest request, RequestOptions options) {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                com.openai.core.http.HttpRequestBody requestBody = request.body();
                if (requestBody == null) throw new IllegalStateException("模型连接请求正文缺失。");
                requestBody.writeTo(bytes);
                Request.Builder outgoing = new Request.Builder().url(request.url());
                for (String header : request.headers().names()) {
                    for (String value : request.headers().values(header)) outgoing.addHeader(header, value);
                }
                outgoing.method(
                        request.method().toString(),
                        RequestBody.create(bytes.toByteArray(), MediaType.get("application/json")));
                try (Response response = http.newCall(outgoing.build()).execute()) {
                    byte[] body = bounded(response);
                    if (response.isSuccessful()) validateResponse.accept(body);
                    Headers headers = Headers.builder()
                            .putAll(response.headers().toMultimap())
                            .build();
                    int status = response.code();
                    return new HttpResponse() {
                        @Override
                        public int statusCode() {
                            return status;
                        }

                        @Override
                        public Headers headers() {
                            return headers;
                        }

                        @Override
                        public InputStream body() {
                            return new ByteArrayInputStream(body);
                        }

                        @Override
                        public void close() {}
                    };
                }
            } catch (IOException failure) {
                throw new IllegalStateException("远端连接失败。");
            }
        }

        @Override
        public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options) {
            try {
                return CompletableFuture.completedFuture(execute(request, options));
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        @Override
        public void close() {
            http.dispatcher().cancelAll();
            http.connectionPool().evictAll();
            http.dispatcher().executorService().shutdownNow();
        }
    }
}
