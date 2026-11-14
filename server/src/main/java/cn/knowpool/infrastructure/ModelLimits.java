package cn.knowpool.infrastructure;

import java.util.*;
import org.springframework.stereotype.Component;

/** 同端点模型共享执行数与分钟窗口，不按知识库或实例重复创建额度。 */
@Component
public final class ModelLimits {
    private final RuntimeSettings settings;
    private final Map<String,Window> windows=new HashMap<>();
    private final ThreadLocal<long[]> calls=new ThreadLocal<>();
    public ModelLimits(RuntimeSettings settings) { this.settings=settings; }
    public Lease request() { long[] previous=calls.get();calls.set(new long[2]);return () -> { if (previous==null) calls.remove();else calls.set(previous); }; }
    public synchronized Lease acquire(String endpoint,String model,long tokens,boolean embedding) {
        long[] count=calls.get();int type=embedding?1:0;
        if (count!=null && ++count[type]>settings.number(embedding?"requestEmbeddingCallBudget":"requestModelCallBudget")) throw new ApiFailure(429,"RESOURCE_LIMIT","本次请求模型调用预算已用尽。",null);
        String key=RuntimeSettings.endpoint(endpoint)+"\n"+model;
        var rate=settings.get().path("modelRateLimits").valueStream().filter(item -> (RuntimeSettings.endpoint(item.path("endpoint").asText())+"\n"+item.path("model").asText()).equals(key)).findFirst().orElseThrow(() -> new ApiFailure(422,"INVALID_STATE","当前端点模型尚未配置共享限流，请先设置运行参数。",null));
        Window window=windows.computeIfAbsent(key,ignored -> new Window());long now=System.nanoTime();
        if (now-window.started>=60_000_000_000L) { window.started=now;window.requests=0;window.tokens=0; }
        if (window.active>=rate.path("maxConcurrent").asLong() || window.requests>=rate.path("requestsPerMinute").asLong() || tokens>rate.path("tokensPerMinute").asLong()-window.tokens) throw new ApiFailure(429,"QUEUE_FULL","模型共享额度已满，请稍后重试。",null);
        window.active++;window.requests++;window.tokens+=tokens;
        return () -> { synchronized(ModelLimits.this) { window.active--; } };
    }
    private static final class Window { long started=System.nanoTime(),requests,tokens,active; }
    public interface Lease extends AutoCloseable { @Override void close(); }
}
