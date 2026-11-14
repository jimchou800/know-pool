package cn.knowpool.system;

import cn.knowpool.infrastructure.ApiFailure;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class Generations {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.knowpool.infrastructure.RuntimeSettings runtime;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.knowpool.infrastructure.ModelLimits modelLimits;
    private final JsonMapper json;
    private final java.util.concurrent.Semaphore capacity=new java.util.concurrent.Semaphore(1,true);
    public Generations(JsonMapper json) { this.json=json; }

    public JsonNode call(ModelSettingsService.GenerationSnapshot model,String instruction,JsonNode input,int outputLimit) {
        String payload=json.writeValueAsString(input);
        long reserved=Math.min(model.maxOutputTokens(),outputLimit);
        long upper=instruction.getBytes(StandardCharsets.UTF_8).length+payload.getBytes(StandardCharsets.UTF_8).length+512L;
        if (reserved<1 || upper+reserved>model.contextWindowTokens()) throw new ApiFailure(422,"MODEL_CONFIG_INVALID","完整模型输入与预留输出超过上下文安全预算。",null);
        boolean acquired=false;
        try {
            if (modelLimits==null) { capacity.acquire();acquired=true; }
            try (var modelLease=modelLimits==null?null:modelLimits.acquire(model.endpoint(),model.model(),upper+reserved,false);var transport=new ConnectionProbe.Transport(runtime==null?5:runtime.number("modelTimeoutSeconds"))) {
                var client=new com.openai.client.OpenAIClientImpl(com.openai.core.ClientOptions.builder().httpClient(transport)
                        .baseUrl(model.endpoint()).apiKey(model.secret()).maxRetries(0).logLevel(com.openai.core.LogLevel.OFF)
                        .responseValidation(true).timeout(Duration.ofSeconds(runtime==null?5:runtime.number("modelTimeoutSeconds"))).build());
                var options=org.springframework.ai.openai.OpenAiChatOptions.builder().model(model.model()).maxTokens((int)reserved)
                        .extraBody(java.util.Map.of("stream",false)).build();
                var response=org.springframework.ai.openai.OpenAiChatModel.builder().openAiClient(client).openAiClientAsync(client.async()).options(options).build()
                        .call(new org.springframework.ai.chat.prompt.Prompt(java.util.List.of(new org.springframework.ai.chat.messages.SystemMessage(instruction),new org.springframework.ai.chat.messages.UserMessage(payload))));
                if (response.getResults()==null || response.getResults().size()!=1 || response.getResult().getOutput()==null)
                    throw new ApiFailure(500,"INTEGRITY_FAILED","生成模型没有完整有效输出。",null);
                if (!"stop".equalsIgnoreCase(response.getResult().getMetadata().getFinishReason()))
                    throw new ApiFailure(500,"INTEGRITY_FAILED","生成模型输出未完整结束。",null);
                var usage=response.getMetadata().getUsage();
                if (usage==null || usage.getPromptTokens()==null || usage.getCompletionTokens()==null || usage.getPromptTokens()<0
                        || usage.getCompletionTokens()<0 || usage.getPromptTokens()>upper || usage.getCompletionTokens()>reserved)
                    throw new ApiFailure(500,"INTEGRITY_FAILED","生成模型实际用量超出安全预算或缺失。",null);
                String text=response.getResult().getOutput().getText();
                if (text==null || text.getBytes(StandardCharsets.UTF_8).length>65536) throw new ApiFailure(500,"INTEGRITY_FAILED","生成模型输出缺失或超过限制。",null);
                JsonNode value=json.readTree(text);
                if (!value.isObject()) throw new ApiFailure(500,"INTEGRITY_FAILED","生成模型须返回合法对象。",null);
                return value;
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();throw new ApiFailure(503,"MODEL_UNAVAILABLE","模型执行已中断。",null);
        } catch (ApiFailure failure) { throw failure; }
        catch (RuntimeException failure) { throw new ApiFailure(503,"MODEL_UNAVAILABLE","生成模型调用失败或输出无效，请检查连接并手动重试。",null); }
        finally { if (acquired) capacity.release(); }
    }
}
