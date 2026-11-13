package cn.knowpool.system;

import cn.knowpool.infrastructure.ApiFailure;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.LogLevel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.stereotype.Component;

@Component
public final class Embeddings {
    private final java.util.concurrent.Semaphore capacity = new java.util.concurrent.Semaphore(1, true);

    public float[] embed(ModelSettingsService.EmbeddingSnapshot snapshot, String text, int dimensions) {
        if (!"text-embedding-v4".equals(snapshot.model()))
            throw new ApiFailure(422, "MODEL_CONFIG_INVALID", "当前模型没有已验证的输入计量能力。", null);
        int upper;
        try {
            upper = StandardCharsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(text))
                    .remaining();
        } catch (java.nio.charset.CharacterCodingException failure) {
            throw new ApiFailure(422, "MODEL_CONFIG_INVALID", "嵌入输入包含无效字符，已停止外发。", null);
        }
        if (upper == 0 || upper > 8192) throw new ApiFailure(422, "MODEL_CONFIG_INVALID", "完整嵌入输入超过模型安全预算或为空。", null);
        boolean acquired = false;
        try {
            capacity.acquire();
            acquired = true;
            try (var transport = new ConnectionProbe.Transport()) {
                var client = new OpenAIClientImpl(ClientOptions.builder()
                        .httpClient(transport)
                        .baseUrl(snapshot.endpoint())
                        .apiKey(snapshot.secret() == null ? "" : snapshot.secret())
                        .maxRetries(0)
                        .logLevel(LogLevel.OFF)
                        .responseValidation(true)
                        .timeout(Duration.ofSeconds(5))
                        .build());
                var options = OpenAiEmbeddingOptions.builder()
                        .model(snapshot.model())
                        .dimensions(dimensions)
                        .encodingFormat(OpenAiEmbeddingOptions.EncodingFormat.FLOAT)
                        .build();
                var response = OpenAiEmbeddingModel.builder()
                        .openAiClient(client)
                        .options(options)
                        .build()
                        .call(new EmbeddingRequest(List.of(text), options));
                var usage = response.getMetadata().getUsage();
                if (response.getResults().size() != 1
                        || usage == null
                        || usage.getPromptTokens() == null
                        || usage.getPromptTokens() < 0
                        || usage.getPromptTokens() > upper)
                    throw new ApiFailure(500, "INTEGRITY_FAILED", "嵌入数量或实际用量核验失败。", null);
                var value = response.getResults().getFirst();
                float[] vector = value.getOutput();
                if (value.getIndex() != 0 || vector.length != dimensions)
                    throw new ApiFailure(500, "INTEGRITY_FAILED", "嵌入维度或位置核验失败。", null);
                for (float component : vector)
                    if (!Float.isFinite(component)) throw new ApiFailure(500, "INTEGRITY_FAILED", "嵌入包含无效数值。", null);
                return vector;
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ApiFailure(503, "MODEL_UNAVAILABLE", "嵌入执行已中断。", null);
        } catch (ApiFailure failure) {
            throw failure;
        } catch (com.openai.errors.OpenAIServiceException failure) {
            int status = failure.statusCode();
            if (status != 408 && status != 429 && status < 500)
                throw new ApiFailure(422, "MODEL_CONFIG_INVALID", "嵌入服务拒绝配置或凭据，请修正后手动重试。", null);
            throw new ApiFailure(503, "MODEL_UNAVAILABLE", "嵌入服务暂时不可用，请稍后重试。", null);
        } catch (RuntimeException failure) {
            throw new ApiFailure(503, "MODEL_UNAVAILABLE", "嵌入服务调用失败，请检查连接及配置。", null);
        } finally {
            if (acquired) capacity.release();
        }
    }
}
