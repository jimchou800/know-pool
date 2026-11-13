package cn.knowpool.search;

import cn.knowpool.infrastructure.ApiFailure;
import cn.knowpool.infrastructure.ApplicationData;
import cn.knowpool.knowledgebases.Indexes;
import cn.knowpool.knowledgebases.KnowledgeBases;
import cn.knowpool.system.ModelSettingsService;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public final class SearchRequests {
    private final ApplicationData data;
    private final KnowledgeBases bases;
    private final Indexes indexes;
    private final JsonMapper json;
    private final java.util.Map<String, ObjectNode> requests = new java.util.HashMap<>();
    private final java.util.Map<String, String> keys = new java.util.HashMap<>();
    private final java.util.Map<String, String> inputs = new java.util.HashMap<>();
    private final java.util.concurrent.Semaphore capacity = new java.util.concurrent.Semaphore(17);
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), r -> {
                Thread t = new Thread(r, "management-search");
                t.setDaemon(true);
                return t;
            });
    private boolean stopping;
    private final java.util.Map<String,ObjectNode> instances = new java.util.HashMap<>();
    private final java.util.Map<String,List<String>> selected = new java.util.HashMap<>();
    private final java.util.Map<String,ObjectNode> scopeProgress = new java.util.HashMap<>();
    private final java.util.Map<String,Thread> running = new java.util.HashMap<>();
    @org.springframework.beans.factory.annotation.Value("${knowpool.search.candidate-budget:1000}")
    private int candidateBudget;
    @org.springframework.beans.factory.annotation.Value("${knowpool.search.evidence-limit:100}")
    private int evidenceLimit;
    @org.springframework.beans.factory.annotation.Value("${knowpool.search.fusion-offset:60}")
    private int fusionOffset;

    public SearchRequests(ApplicationData data, KnowledgeBases bases, Indexes indexes, JsonMapper json) {
        this.data = data;
        this.bases = bases;
        this.indexes = indexes;
        this.json = json;
        data.write(jdbc -> {
            for (String payload : jdbc.sql(
                            "SELECT payload FROM tasks WHERE kind='search' AND state IN ('queued','running')")
                    .query(String.class)
                    .list()) {
                ObjectNode task = (ObjectNode) json.readTree(payload);
                task.put("state", "interrupted")
                        .put("phase", "completed")
                        .put("finishedAt", Instant.now().toString())
                        .putNull("resultResourceId");
                task.set(
                        "failure",
                        json.createObjectNode()
                                .put("code", "TASK_INTERRUPTED")
                                .put("message", "检索因重启中断，可能已产生费用，请手动重新检索。")
                                .put("retryable", false));
                cn.knowpool.infrastructure.TaskEvents.record(jdbc, json, task);
                jdbc.sql("UPDATE tasks SET state='interrupted',payload=? WHERE id=?")
                        .params(json.writeValueAsString(task), task.path("id").asText())
                        .update();
            }
            for (String payload:jdbc.sql("SELECT payload FROM search_instances ORDER BY id").query(String.class).list()) {
                ObjectNode instance=(ObjectNode)json.readTree(payload);
                instance.putNull("currentRequestId").put("resultState",instance.path("lastSubmittedQuery").isNull()?"never_submitted":"rerun_required");
                instances.put(instance.path("id").asText(),instance);
            }
            return null;
        });
    }

    public synchronized JsonNode createInstance() {
        String now=Instant.now().toString(), id=UUID.randomUUID().toString();
        ObjectNode instance=json.createObjectNode().put("id",id).put("name","新检索").put("manuallyNamed",false)
                .putNull("lastSubmittedQuery").put("createdAt",now).put("updatedAt",now).putNull("currentRequestId").put("resultState","never_submitted");
        instance.set("knowledgeBaseIds",json.createArrayNode());
        data.write(jdbc -> { saveInstance(jdbc,instance); return null; });
        instances.put(id,instance);
        return instance.deepCopy();
    }

    public synchronized JsonNode instance(String id) {
        String valid=ModelSettingsService.identifier(id,"instanceId");
        boolean exists=data.read(jdbc -> jdbc.sql("SELECT COUNT(*) FROM search_instances WHERE id=?").param(valid).query(Integer.class).single()>0);
        if (!exists || !instances.containsKey(valid)) throw new ApiFailure(404,"NOT_FOUND","检索实例不存在。",null);
        return instances.get(valid).deepCopy();
    }

    public synchronized KnowledgeBases.Page listInstances(int limit,String cursor) {
        String after=cursor==null?"":ModelSettingsService.identifier(cursor,"cursor");
        List<String> ids=data.read(jdbc -> jdbc.sql("SELECT id FROM search_instances WHERE id>? ORDER BY id LIMIT ?").params(after,limit+1).query(String.class).list());
        return new KnowledgeBases.Page(ids.stream().limit(limit).map(id -> (JsonNode)instances.get(id).deepCopy()).toList(),ids.size()>limit?ids.get(limit-1):null);
    }

    public synchronized JsonNode renameInstance(String id,String name) {
        ObjectNode updated=(ObjectNode)instance(id);
        updated.put("name",name).put("manuallyNamed",true).put("updatedAt",Instant.now().toString());
        data.write(jdbc -> { saveInstance(jdbc,updated); return null; });
        instances.put(updated.path("id").asText(),updated);
        return updated.deepCopy();
    }

    public synchronized JsonNode updateScope(String id,JsonNode scope) {
        ObjectNode updated=(ObjectNode)instance(id);
        updated.set("knowledgeBaseIds",scope.deepCopy());updated.put("updatedAt",Instant.now().toString());
        data.write(jdbc -> { saveInstance(jdbc,updated); return null; });
        instances.put(updated.path("id").asText(),updated);
        return updated.deepCopy();
    }

    private void saveInstance(org.springframework.jdbc.core.simple.JdbcClient jdbc,ObjectNode instance) {
        ObjectNode metadata=instance.deepCopy();metadata.remove("currentRequestId");metadata.remove("resultState");
        jdbc.sql("INSERT INTO search_instances(id,payload) VALUES(?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload")
                .params(instance.path("id").asText(),json.writeValueAsString(metadata)).update();
    }

    public synchronized JsonNode deleteInstance(String id,String session,String key) {
        String valid=ModelSettingsService.identifier(id,"instanceId"),validKey=ModelSettingsService.identifier(key,"Idempotency-Key"),operation="deleteSearchInstance:"+valid;
        JsonNode replay=data.read(jdbc -> jdbc.sql("SELECT payload FROM tasks WHERE session_id=? AND operation=? AND idempotency_key=?")
                .params(session,operation,validKey).query(String.class).optional().map(json::readTree).orElse(null));
        if (replay!=null) return json.createObjectNode().set("task",replay);
        instance(valid);
        List<String> owned=requests.entrySet().stream().filter(entry -> entry.getValue().path("instanceId").asText().equals(valid)).map(java.util.Map.Entry::getKey).toList();
        var cancelled=new java.util.LinkedHashMap<String,ObjectNode>();
        for (String request:owned) if (active(requests.get(request))) cancelled.put(request,cancelled((ObjectNode)requests.get(request).path("task")));
        String taskId=UUID.randomUUID().toString(),now=Instant.now().toString();
        ObjectNode task=json.createObjectNode().put("id",taskId).put("kind","cleanup").put("state","succeeded").put("phase","completed")
                .put("createdAt",now).put("startedAt",now).put("finishedAt",now).put("attempt",1).put("progressCompleted",1).put("progressTotal",1);
        for (String field:List.of("knowledgeBaseId","fileId","targetContentVersionId","targetConfigVersionId","retryOfTaskId","failure","resultResourceId")) task.putNull(field);
        task.set("warnings",json.createArrayNode());
        data.write(jdbc -> {
            for (ObjectNode value:cancelled.values()) persist(jdbc,value);
            jdbc.sql("DELETE FROM search_instances WHERE id=?").param(valid).update();
            jdbc.sql("INSERT INTO tasks(id,kind,state,payload,session_id,operation,idempotency_key,request_fingerprint) VALUES(?,'cleanup','succeeded',?,?,?,?,?)")
                    .params(taskId,json.writeValueAsString(task),session,operation,validKey,"{\"confirmed\":true}").update();
            cn.knowpool.infrastructure.TaskEvents.record(jdbc,json,task);
            return null;
        });
        for (var entry:cancelled.entrySet()) { requests.get(entry.getKey()).set("task",entry.getValue());interrupt(entry.getValue().path("id").asText()); }
        instances.remove(valid);
        for (String request:owned) { requests.remove(request);inputs.remove(request);selected.remove(request);scopeProgress.remove(request); }
        keys.entrySet().removeIf(entry -> owned.contains(entry.getValue()));
        return json.createObjectNode().set("task",task);
    }

    public synchronized JsonNode cancelRequest(String id) {
        JsonNode request=get(id);cancel(request.at("/task/id").asText());return get(id);
    }

    public synchronized JsonNode submitInstance(String session,String instance,String key,JsonNode input) {
        String valid=ModelSettingsService.identifier(instance,"instanceId");
        if (!instances.containsKey(valid)) throw new ApiFailure(404,"NOT_FOUND","检索实例不存在。",null);
        return enqueue(session,null,valid,key,input);
    }

    public synchronized JsonNode submit(String session, String kb, String key, JsonNode input) {
        bases.get(kb);
        return enqueue(session,kb,null,key,input);
    }

    private JsonNode enqueue(String session,String kb,String instance,String key,JsonNode input) {
        ModelSettingsService.identifier(key,"Idempotency-Key");
        String operation=session+":"+(instance==null?"management:"+kb:"instance:"+instance)+":"+key;
        String fingerprint=json.writeValueAsString(input);
        if (keys.containsKey(operation)) {
            String id=keys.get(operation);
            if (!inputs.get(id).equals(fingerprint)) throw new ApiFailure(409,"CONFLICT","同一幂等键不能提交不同内容。",null);
            return requests.get(id).deepCopy();
        }
        if (!capacity.tryAcquire()) throw new ApiFailure(429,"QUEUE_FULL","检索队列已满，请稍后重试。",null);
        String id=UUID.randomUUID().toString(),taskId=UUID.randomUUID().toString();
        ObjectNode task=json.createObjectNode().put("id",taskId).put("kind","search").put("state","queued").put("phase","queued")
                .put("createdAt",Instant.now().toString()).put("attempt",0).put("progressCompleted",0);
        if (kb==null) task.putNull("knowledgeBaseId"); else task.put("knowledgeBaseId",kb);
        for (String field:List.of("startedAt","finishedAt","fileId","targetContentVersionId","targetConfigVersionId","retryOfTaskId","progressTotal","failure","resultResourceId")) task.putNull(field);
        task.set("warnings",json.createArrayNode());
        ObjectNode request=json.createObjectNode().put("id",id).putNull("result").putNull("scope");
        if (instance==null) request.putNull("instanceId"); else request.put("instanceId",instance);
        request.set("task",task);
        ObjectNode previous=instance==null?null:requests.get(instances.get(instance).path("currentRequestId").asText());
        ObjectNode cancelled=previous!=null && active(previous)?cancelled((ObjectNode)previous.path("task")):null;
        ObjectNode owner=instance==null?null:instances.get(instance).deepCopy();
        if (owner!=null) {
            boolean first=owner.path("lastSubmittedQuery").isNull();
            owner.put("currentRequestId",id).put("lastSubmittedQuery",input.path("query").asText()).put("updatedAt",Instant.now().toString()).put("resultState","running");
            owner.set("knowledgeBaseIds",input.path("knowledgeBaseIds").deepCopy());
            if (!owner.path("manuallyNamed").asBoolean() && first) {
                String query=input.path("query").asText();
                owner.put("name",query.substring(0,query.offsetByCodePoints(0,Math.min(30,query.codePointCount(0,query.length())))));
            }
        }
        boolean submitted=false;
        try {
            data.write(jdbc -> {
                jdbc.sql("INSERT INTO search_identities VALUES(?)").param(id).update();
                cn.knowpool.infrastructure.TaskEvents.record(jdbc,json,task);
                jdbc.sql("INSERT INTO tasks(id,kind,state,payload,session_id,operation,idempotency_key,request_fingerprint) VALUES(?,'search','queued',?,'runtime','search',?,'')")
                        .params(taskId,json.writeValueAsString(task),taskId).update();
                if (cancelled!=null) persist(jdbc,cancelled);
                if (owner!=null) saveInstance(jdbc,owner);
                return null;
            });
            if (cancelled!=null) { previous.set("task",cancelled); previous.putNull("result"); preserveScope(previous); interrupt(cancelled.path("id").asText()); }
            requests.put(id,request); keys.put(operation,id); inputs.put(id,fingerprint);
            if (instance!=null) {
                selected.put(id,input.path("knowledgeBaseIds").valueStream().map(JsonNode::asText).toList());
                instances.put(instance,owner);
            }
            try {
                worker.execute(() -> { try { run(id,kb,input.path("query").asText()); } finally {
                    synchronized (this) { running.remove(id); }
                    Thread.interrupted(); capacity.release();
                } });
            } catch (java.util.concurrent.RejectedExecutionException failure) {
                task.put("state","interrupted").put("phase","completed").put("finishedAt",Instant.now().toString());
                task.set("failure",json.createObjectNode().put("code","TASK_INTERRUPTED").put("message","检索执行器已停止，可能已产生费用，请手动重试。").put("retryable",false));
                save(task); updateInstance(request,"failed");
                return request.deepCopy();
            }
            submitted=true;
            return request.deepCopy();
        } finally { if (!submitted) capacity.release(); }
    }

    private static boolean active(JsonNode request) { return List.of("queued","running").contains(request.at("/task/state").asText()); }

    private synchronized boolean cancelledRequest(String id) {
        return stopping || !requests.containsKey(id) || !active(requests.get(id)) || !current(requests.get(id));
    }

    private boolean current(ObjectNode request) {
        String instance=request.path("instanceId").asText();
        return request.path("instanceId").isNull() || instances.containsKey(instance) && instances.get(instance).path("currentRequestId").asText().equals(request.path("id").asText());
    }

    private void updateInstance(ObjectNode request,String state) {
        if (request.path("instanceId").isNull() || !current(request)) return;
        instances.get(request.path("instanceId").asText()).put("resultState",state).put("updatedAt",Instant.now().toString());
    }

    private void run(String id,String kb,String query) {
        synchronized (this) {
            if (cancelledRequest(id)) return;
            running.put(id,Thread.currentThread());
            ObjectNode task=(ObjectNode)requests.get(id).path("task");
            task.put("state","running").put("phase","retrieving").put("startedAt",Instant.now().toString()).put("attempt",1);
            save(task);
        }
        try {
            Retrieval retrieval=kb==null?retrieveMany(id,query):new Retrieval(indexes.retrieve(kb,query),scope(kb,null));
            synchronized (this) {
                if (cancelledRequest(id)) return;
                ObjectNode request=requests.get(id),task=(ObjectNode)request.path("task");
                request.set("scope",retrieval.scope());
                if (retrieval.scope().path("usedKnowledgeBaseIds").isEmpty()) throw new ApiFailure(422,"SCOPE_UNAVAILABLE","所选知识库均不可用，请检查发布状态和依赖后手动重试。",null);
                ObjectNode result=json.createObjectNode().put("requestId",id).put("taskId",task.path("id").asText()).put("query",query)
                        .put("outcome",retrieval.evidence().isEmpty()?"no_matches":"matches").put("preprocessingState","not_used");
                result.set("instanceId",request.path("instanceId")); result.set("scope",retrieval.scope()); result.set("evidence",retrieval.evidence());
                result.set("additionalQueries",json.createArrayNode());
                var warnings=json.createArrayNode();
                if (retrieval.scope().path("incomplete").asBoolean()) warnings.add("部分知识库未参与检索，结果范围不完整。");
                result.set("warnings",warnings);
                ObjectNode completed=task.deepCopy();
                completed.set("warnings",warnings.deepCopy());
                completed.put("state","succeeded").put("phase","completed").put("finishedAt",Instant.now().toString()).put("resultResourceId",id);
                data.write(jdbc -> {
                    for (JsonNode item:retrieval.evidence()) jdbc.sql("INSERT INTO search_evidence_identities VALUES(?,?)").params(item.path("id").asText(),id).update();
                    persist(jdbc,completed); return null;
                });
                request.set("result",result);request.set("task",completed);updateInstance(request,"available");
            }
        } catch (RuntimeException failure) {
            synchronized (this) {
                if (cancelledRequest(id)) return;
                ObjectNode request=requests.get(id),task=(ObjectNode)request.path("task");
                String code=failure instanceof ApiFailure api?api.code():"INTERNAL_ERROR";
                if (kb!=null) request.set("scope",scope(kb,code.equals("NO_EFFECTIVE_INDEX")?"no_effective_index":"unavailable"));
                else preserveScope(request);
                task.put("state","failed").put("phase","completed").put("finishedAt",Instant.now().toString());
                task.set("failure",json.createObjectNode().put("code",code).put("message",failure instanceof ApiFailure api?api.getMessage():"检索执行失败。").put("retryable",false));
                save(task);updateInstance(request,"failed");
            }
        }
    }

    private Retrieval retrieveMany(String id,String query) {
        List<String> all=selected.get(id);
        ObjectNode scope=json.createObjectNode().put("incomplete",false);
        scope.set("selectedKnowledgeBaseIds",json.valueToTree(all));
        var used=json.createArrayNode();var omitted=json.createArrayNode();
        scope.set("usedKnowledgeBaseIds",used);scope.set("omitted",omitted);
        List<String> eligible=data.write(jdbc -> {
            var result=new java.util.ArrayList<String>();
            for (String kb:all) {
                try {
                    JsonNode base=bases.get(kb);
                    if (!base.path("publicationState").asText().equals("published")) omit(omitted,kb,"not_published","知识库未发布或已下线。");
                    else result.add(kb);
                } catch (ApiFailure failure) {
                    if (failure.status()!=404) throw failure;
                    omit(omitted,kb,"deleted","知识库不存在或已被删除。");
                }
            }
            return result;
        });
        scope.put("incomplete",!omitted.isEmpty());
        progress(id,scope);
        var lists=new java.util.LinkedHashMap<String,JsonNode>();
        try (var cache=new Indexes.QueryVectors()) {
        int quota=Math.max(1,Math.max(100,Math.min(10000,candidateBudget))/Math.max(1,all.size()));
        for (String kb:eligible) {
            if (cancelledRequest(id)) return new Retrieval(json.createArrayNode(),scope);
            try {
                JsonNode evidence=indexes.retrieve(kb,query,cache,quota,() -> cancelledRequest(id));
                lists.put(kb,evidence);used.add(kb);
            } catch (ApiFailure failure) {
                if (cancelledRequest(id)) return new Retrieval(json.createArrayNode(),scope);
                omit(omitted,kb,failure.code().equals("NO_EFFECTIVE_INDEX")?"no_effective_index":"unavailable",
                        failure.code().equals("NO_EFFECTIVE_INDEX")?"知识库没有生效索引。":"知识库依赖或有效范围不可用。");
            }
            scope.put("incomplete",!omitted.isEmpty());
            progress(id,scope);
        }
        for (var entry:new java.util.ArrayList<>(lists.entrySet())) {
            if (entry.getValue().valueStream().anyMatch(item -> !indexes.remainsEligible(item))) {
                lists.remove(entry.getKey());
                omit(omitted,entry.getKey(),"unavailable","知识库资料的有效范围已变化，请重新检索。");
            }
        }
        used.removeAll();lists.keySet().forEach(used::add);
        scope.put("incomplete",!omitted.isEmpty());
        progress(id,scope);
        return new Retrieval(fuse(lists),scope);
        }
    }

    private void omit(tools.jackson.databind.node.ArrayNode omitted,String kb,String reason,String message) {
        omitted.add(json.createObjectNode().put("knowledgeBaseId",kb).put("reason",reason).put("message",message));
    }

    private synchronized void progress(String id,ObjectNode scope) { if (requests.containsKey(id)) scopeProgress.put(id,scope.deepCopy()); }

    private void preserveScope(ObjectNode request) {
        ObjectNode progress=scopeProgress.get(request.path("id").asText());
        if (progress!=null && request.path("scope").isNull()) request.set("scope",progress.deepCopy());
    }

    private JsonNode fuse(java.util.Map<String,JsonNode> lists) {
        var values=new java.util.LinkedHashMap<String,JsonNode>();var scores=new java.util.HashMap<String,Double>();
        for (JsonNode evidence:lists.values()) {
            var seen=new java.util.HashSet<String>();int rank=0;
            for (JsonNode item:evidence) {
                rank++;
                String identity=item.path("fileId").asText()+":"+item.path("contentVersionId").asText()+":"+item.path("location").toString()+":"+item.path("text").asText();
                if (!seen.add(identity)) continue;
                values.putIfAbsent(identity,item);
                scores.merge(identity,1.0/(Math.max(1,fusionOffset)+rank),Double::sum);
            }
        }
        var ordered=new java.util.ArrayList<>(values.keySet());
        ordered.sort(java.util.Comparator.comparingDouble((String key) -> scores.get(key)).reversed());
        var result=json.createArrayNode();
        ordered.stream().limit(Math.max(1,Math.min(1000,evidenceLimit))).forEach(key -> result.add(values.get(key)));
        return result;
    }

    private record Retrieval(JsonNode evidence,ObjectNode scope) {}

    public void reserveQuestion() {
        if (!capacity.tryAcquire()) throw new ApiFailure(429,"QUEUE_FULL","知识服务队列已满，请稍后重试。",null);
    }

    public void releaseQuestion() { capacity.release(); }

    public void executeQuestion(Runnable action) {
        worker.execute(() -> { try { action.run(); } finally { Thread.interrupted();capacity.release(); } });
    }

    public Grounding beginGrounding(List<String> selectedIds) {
        var cache=new Indexes.QueryVectors();
        try {
            return data.write(jdbc -> {
                var eligible=new java.util.ArrayList<String>();var omitted=json.createArrayNode();
                for (String kb:selectedIds) {
                    try {
                        if (bases.get(kb).path("publicationState").asText().equals("published")) eligible.add(kb);
                        else omit(omitted,kb,"not_published","知识库未发布或已下线。");
                    } catch (ApiFailure failure) { if (failure.status()!=404) throw failure;omit(omitted,kb,"deleted","知识库不存在或已被删除。"); }
                }
                for (String kb:new java.util.ArrayList<>(eligible)) {
                    try { indexes.captureAll(List.of(kb),cache); }
                    catch (ApiFailure failure) { eligible.remove(kb);omit(omitted,kb,"unavailable","知识库配置或生效索引不可用。"); }
                }
                return new Grounding(List.copyOf(selectedIds),eligible,omitted,cache);
            });
        } catch (RuntimeException failure) { cache.close();throw failure; }
    }

    public final class Grounding implements AutoCloseable {
        private final List<String> selectedIds;
        private final List<String> eligible;
        private final tools.jackson.databind.node.ArrayNode initialOmitted;
        private final Indexes.QueryVectors cache;
        private Grounding(List<String> selectedIds,List<String> eligible,tools.jackson.databind.node.ArrayNode omitted,Indexes.QueryVectors cache) {
            this.selectedIds=selectedIds;this.eligible=eligible;this.initialOmitted=omitted;this.cache=cache;
        }
        public JsonNode retrieve(List<String> queries,java.util.function.BooleanSupplier cancelled,java.util.function.Consumer<JsonNode> progress) {
            var omitted=initialOmitted.deepCopy();var used=json.createArrayNode();
            ObjectNode scope=json.createObjectNode();scope.set("selectedKnowledgeBaseIds",json.valueToTree(selectedIds));scope.set("usedKnowledgeBaseIds",used);scope.set("omitted",omitted);scope.put("incomplete",!omitted.isEmpty());
            progress.accept(scope.deepCopy());
            var routes=new java.util.LinkedHashMap<String,JsonNode>();
            int quota=Math.max(1,Math.max(400,Math.min(10000,candidateBudget))/(Math.max(1,selectedIds.size())*queries.size()));
            for (String kb:eligible) {
                if (cancelled.getAsBoolean()) return json.createObjectNode().set("scope",scope);
                var local=new java.util.LinkedHashMap<String,JsonNode>();
                try {
                    for (int n=0;n<queries.size();n++) {
                        if (cancelled.getAsBoolean()) return json.createObjectNode().set("scope",scope);
                        local.put(kb+":"+n,indexes.retrieve(kb,queries.get(n),cache,quota,cancelled));
                    }
                    used.add(kb);routes.putAll(local);
                } catch (ApiFailure failure) {
                    if (cancelled.getAsBoolean()) return json.createObjectNode().set("scope",scope);
                    omit(omitted,kb,failure.code().equals("NO_EFFECTIVE_INDEX")?"no_effective_index":"unavailable","知识库生效索引或依赖不可用。");
                }
                scope.put("incomplete",!omitted.isEmpty());progress.accept(scope.deepCopy());
            }
            ObjectNode result=json.createObjectNode();result.set("scope",scope);result.set("evidence",fuse(routes));return result;
        }
        public boolean remainsEligible(JsonNode evidence) { return indexes.remainsEligible(evidence); }
        @Override public void close() { cache.close(); }
    }

    private ObjectNode scope(String kb, String reason) {
        ObjectNode scope = json.createObjectNode().put("incomplete", reason != null);
        scope.set("selectedKnowledgeBaseIds", json.createArrayNode().add(kb));
        scope.set(
                "usedKnowledgeBaseIds", reason == null ? json.createArrayNode().add(kb) : json.createArrayNode());
        var omitted = json.createArrayNode();
        if (reason != null)
            omitted.add(json.createObjectNode()
                    .put("knowledgeBaseId", kb)
                    .put("reason", reason)
                    .put("message", reason.equals("no_effective_index") ? "知识库没有生效索引。" : "知识库依赖不可用。"));
        scope.set("omitted", omitted);
        return scope;
    }

    private void save(ObjectNode task) {
        data.write(jdbc -> { persist(jdbc,task); return null; });
    }

    private void persist(org.springframework.jdbc.core.simple.JdbcClient jdbc,ObjectNode task) {
            cn.knowpool.infrastructure.TaskEvents.record(jdbc, json, task);
            jdbc.sql("UPDATE tasks SET state=?,payload=? WHERE id=?")
                    .params(
                            task.path("state").asText(),
                            json.writeValueAsString(task),
                            task.path("id").asText())
                    .update();
    }

    public synchronized JsonNode get(String id) {
        String valid = ModelSettingsService.identifier(id, "requestId");
        if (requests.containsKey(valid)) {
            JsonNode value=requests.get(valid).deepCopy();for (JsonNode item:value.at("/result/evidence")) indexes.decorateEvidence(item);return value;
        }
        boolean known = data.read(jdbc -> jdbc.sql("SELECT COUNT(*) FROM search_identities WHERE id=?")
                        .param(valid)
                        .query(Integer.class)
                        .single()
                > 0);
        throw new ApiFailure(
                known ? 410 : 404,
                known ? "RESULT_EXPIRED" : "NOT_FOUND",
                known ? "检索结果已失效，请手动重新检索。" : "检索请求不存在。",
                null);
    }

    public synchronized JsonNode cancel(String taskId) {
        String id = ModelSettingsService.identifier(taskId,"taskId");
        ObjectNode task = data.read(jdbc -> jdbc.sql("SELECT payload FROM tasks WHERE id=? AND kind='search'").param(id).query(String.class).optional()
                .map(value -> (ObjectNode)json.readTree(value)).orElseThrow(() -> new ApiFailure(404,"NOT_FOUND","检索任务不存在。",null)));
        if (!List.of("queued","running").contains(task.path("state").asText())) return task;
        task=cancelled(task);
        save(task);
        for (ObjectNode request:requests.values()) if (request.at("/task/id").asText().equals(id)) {
            request.set("task",task);request.putNull("result");preserveScope(request);updateInstance(request,"cancelled");
        }
        interrupt(id);
        return task;
    }

    private ObjectNode cancelled(ObjectNode task) {
        ObjectNode value=task.deepCopy();
        value.put("state","cancelled").put("phase","completed").put("finishedAt",Instant.now().toString()).putNull("failure").putNull("resultResourceId");
        return value;
    }

    private void interrupt(String task) {
        for (var entry:running.entrySet()) {
            JsonNode request=requests.get(entry.getKey());
            if (request!=null && request.at("/task/id").asText().equals(task)) entry.getValue().interrupt();
        }
    }

    public synchronized JsonNode evidence(String id, String evidenceId) {
        ModelSettingsService.identifier(evidenceId, "evidenceId");
        JsonNode request = get(id);
        for (JsonNode item : request.at("/result/evidence"))
            if (item.path("id").asText().equals(evidenceId)) return item;
        throw new ApiFailure(404, "NOT_FOUND", "本次检索证据不存在。", null);
    }

    @PreDestroy
    public void close() {
        synchronized (this) {
            stopping = true;
        }
        worker.shutdownNow();
        try {
            worker.awaitTermination(6, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }
}
