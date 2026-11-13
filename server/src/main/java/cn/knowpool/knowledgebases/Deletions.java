package cn.knowpool.knowledgebases;

import cn.knowpool.datasets.FileService;
import cn.knowpool.infrastructure.ApiFailure;
import cn.knowpool.infrastructure.ApplicationData;
import cn.knowpool.infrastructure.TaskEvents;
import cn.knowpool.system.ModelSettingsService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public final class Deletions {
    private final ApplicationData data;
    private final KnowledgeBases bases;
    private final FileService files;
    private final Indexes indexes;
    private final VectorCollections vectors;
    private final ModelSettingsService settings;
    private final JsonMapper json;
    private final java.util.concurrent.ScheduledExecutorService worker=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> { Thread thread=new Thread(r,"deletion-cleanup");thread.setDaemon(true);return thread; });
    private volatile boolean stopping;

    public Deletions(ApplicationData data,KnowledgeBases bases,FileService files,Indexes indexes,VectorCollections vectors,ModelSettingsService settings,JsonMapper json) {
        this.data=data;this.bases=bases;this.files=files;this.indexes=indexes;this.vectors=vectors;this.settings=settings;this.json=json;
        worker.scheduleWithFixedDelay(this::next,100,100,java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public JsonNode impact(String kind,String raw) {
        String id=ModelSettingsService.identifier(raw,"resourceId");
        return data.read(jdbc -> {
            List<String> selected=kind.equals("knowledge_base")?bases.includedFiles(id):files.deletionFiles(kind,id);
            List<String> affected=bases.deletionBases(kind,id,selected);
            ObjectNode value=json.createObjectNode().put("resourceId",id).put("fileCount",selected.size()).put("activeTaskCount",affectedTasks(jdbc,kind,id,selected).size()).put("historySnapshotsRetained",true);
            value.set("knowledgeBaseIds",json.valueToTree(affected));value.set("warnings",json.createArrayNode().add(kind.equals("knowledge_base")?"删除知识库不删除源资料，历史引用保留；正在读取的请求完成后再清理专属资源。":"删除后资料立即不可用，关联知识库保留并按剩余资料决定下线；历史引用快照保留，完整副本在安全读取结束后清理。"));return value;
        });
    }

    public JsonNode delete(String kind,String raw,String session,String key) {
        String id=ModelSettingsService.identifier(raw,"resourceId"),validKey=ModelSettingsService.identifier(key,"Idempotency-Key"),operation="delete:"+kind+":"+id;
        return data.write(jdbc -> {
            JsonNode previous=replay(jdbc,session,operation,validKey);if (previous!=null) return accepted(previous);
            List<String> selected=kind.equals("knowledge_base")?bases.includedFiles(id):files.deletionFiles(kind,id);
            List<String> affected=bases.deletionBases(kind,id,selected);
            var targets=(tools.jackson.databind.node.ArrayNode)bases.deletionTargets(kind,id,selected,affected);
            if (!kind.equals("knowledge_base")) for (JsonNode copy:files.deletionCopies(selected)) targets.add(copy);
            String now=Instant.now().toString();
            for (JsonNode active:affectedTasks(jdbc,kind,id,selected)) {
                ObjectNode task=(ObjectNode)active;task.put("state","cancelled").put("phase","completed").put("finishedAt",now).putNull("failure").putNull("resultResourceId");persist(jdbc,task);
            }
            bases.deleteLinks(jdbc,kind,id,selected,affected);
            if (!kind.equals("knowledge_base")) files.markDeleted(jdbc,kind,id,selected);
            ObjectNode task=cleanup(kind.equals("knowledge_base")?id:null,kind.equals("file")?id:null,null,targets.size());
            insert(jdbc,task,session,operation,validKey,targets,settings.vectorConnection().endpoint());return accepted(task);
        });
    }

    private List<JsonNode> affectedTasks(org.springframework.jdbc.core.simple.JdbcClient jdbc,String kind,String id,List<String> selected) {
        return jdbc.sql("SELECT payload FROM tasks WHERE state IN ('queued','running') AND kind IN ('index_file','rebuild_knowledge_base','model_test')")
                .query(String.class).list().stream().map(json::readTree).filter(task -> kind.equals("knowledge_base")?task.path("knowledgeBaseId").asText().equals(id):task.path("kind").asText().equals("index_file") && selected.contains(task.path("fileId").asText())).toList();
    }

    public JsonNode retry(String raw,String session,String key) {
        String id=ModelSettingsService.identifier(raw,"taskId"),validKey=ModelSettingsService.identifier(key,"Idempotency-Key"),operation="retryCleanupTask:"+id;
        return data.write(jdbc -> {
            JsonNode previous=replay(jdbc,session,operation,validKey);if (previous!=null) return accepted(previous);
            JsonNode old=task(jdbc,id);
            if (!old.path("kind").asText().equals("cleanup") || !old.path("state").asText().equals("failed")) throw new ApiFailure(409,"CONFLICT","仅失败的清理任务可以显式重试。",null);
            var plan=jdbc.sql("SELECT targets,endpoint FROM deletion_cleanup WHERE task_id=? UNION ALL SELECT targets,endpoint FROM index_cleanup WHERE task_id=?")
                    .params(id,id).query((row,n) -> new String[]{row.getString(1),row.getString(2)}).list();
            if (plan.isEmpty()) throw new ApiFailure(409,"CONFLICT","清理任务缺少明确归属目标，不能自动重试。",null);
            JsonNode targets=json.readTree(plan.getFirst()[0]);
            for (JsonNode target:targets) { ObjectNode item=(ObjectNode)target;if (!item.has("type")) item.put("type","index");item.put("attempts",0); }
            ObjectNode replacement=cleanup(old.path("knowledgeBaseId").isNull()?null:old.path("knowledgeBaseId").asText(),old.path("fileId").isNull()?null:old.path("fileId").asText(),id,targets.size());
            insert(jdbc,replacement,session,operation,validKey,targets,plan.getFirst()[1]);return accepted(replacement);
        });
    }

    public Page tasks(int limit,String cursor,String kind,String state) {
        String after=cursor==null?"":ModelSettingsService.identifier(cursor,"cursor");
        return data.read(jdbc -> {
            List<JsonNode> rows=jdbc.sql("SELECT payload FROM tasks WHERE id>? AND (? IS NULL OR kind=?) AND (? IS NULL OR state=?) ORDER BY id LIMIT ?")
                    .params(after,kind,kind,state,state,limit+1).query(String.class).list().stream().map(json::readTree).toList();
            return new Page(rows.subList(0,Math.min(limit,rows.size())),rows.size()>limit?rows.get(limit-1).path("id").asText():null);
        });
    }

    private ObjectNode cleanup(String kb,String file,String retry,int total) {
        ObjectNode task=json.createObjectNode().put("id",UUID.randomUUID().toString()).put("kind","cleanup").put("state","queued").put("phase","queued").put("createdAt",Instant.now().toString()).put("attempt",0).put("progressCompleted",0).put("progressTotal",total);
        for (String field:List.of("startedAt","finishedAt","targetContentVersionId","targetConfigVersionId","failure","resultResourceId")) task.putNull(field);
        if (kb==null) task.putNull("knowledgeBaseId");else task.put("knowledgeBaseId",kb);
        if (file==null) task.putNull("fileId");else task.put("fileId",file);
        if (retry==null) task.putNull("retryOfTaskId");else task.put("retryOfTaskId",retry);
        task.set("warnings",json.createArrayNode());return task;
    }

    private JsonNode replay(org.springframework.jdbc.core.simple.JdbcClient jdbc,String session,String operation,String key) {
        return jdbc.sql("SELECT payload FROM tasks WHERE session_id=? AND operation=? AND idempotency_key=?").params(session,operation,key).query(String.class).optional().map(json::readTree).orElse(null);
    }
    private JsonNode task(org.springframework.jdbc.core.simple.JdbcClient jdbc,String id) {
        return jdbc.sql("SELECT payload FROM tasks WHERE id=?").param(id).query(String.class).optional().map(json::readTree).orElseThrow(() -> new ApiFailure(404,"NOT_FOUND","任务不存在。",null));
    }
    private JsonNode accepted(JsonNode task) { return json.createObjectNode().set("task",task); }
    private void insert(org.springframework.jdbc.core.simple.JdbcClient jdbc,ObjectNode task,String session,String operation,String key,JsonNode targets,String endpoint) {
        jdbc.sql("INSERT INTO tasks(id,kind,state,payload,session_id,operation,idempotency_key,request_fingerprint) VALUES(?,'cleanup','queued',?,?,?,?,?)")
                .params(task.path("id").asText(),json.writeValueAsString(task),session,operation,key,"{\"confirmed\":true}").update();
        jdbc.sql("INSERT INTO deletion_cleanup VALUES(?,?,?)").params(task.path("id").asText(),json.writeValueAsString(targets),endpoint).update();TaskEvents.record(jdbc,json,task);
    }
    private void persist(org.springframework.jdbc.core.simple.JdbcClient jdbc,ObjectNode task) {
        jdbc.sql("UPDATE tasks SET state=?,payload=? WHERE id=?").params(task.path("state").asText(),json.writeValueAsString(task),task.path("id").asText()).update();TaskEvents.record(jdbc,json,task);
    }

    private void next() {
        if (stopping) return;
        try {
            var pending=data.read(jdbc -> jdbc.sql("SELECT d.task_id,d.targets,d.endpoint FROM deletion_cleanup d JOIN tasks t ON t.id=d.task_id WHERE t.state IN ('queued','running') ORDER BY d.task_id LIMIT 1")
                    .query((row,n) -> new String[]{row.getString(1),row.getString(2),row.getString(3)}).list());
            if (pending.isEmpty()) return;
            String[] plan=pending.getFirst();JsonNode targets=json.readTree(plan[1]);List<String> protectedIndexes=targets.valueStream().filter(item -> item.has("indexId")).map(item -> item.path("indexId").asText()).toList();
            indexes.cleanWhenIdle(protectedIndexes,() -> run(plan[0],targets,plan[2]));
        } catch (RuntimeException ignored) { }
    }

    private void run(String id,JsonNode targets,String endpoint) {
        try {
            update(id,targets,"running",null);
            for (JsonNode target:targets) {
                if (target.path("completed").asBoolean()) continue;
                int previous=target.path("attempts").asInt();if (previous>=3) throw new ApiFailure(503,"CLEANUP_FAILED","清理尝试已达到上限。",null);
                for (int attempt=previous+1;attempt<=3;attempt++) {
                    ((ObjectNode)target).put("attempts",attempt);update(id,targets,"running",null);
                    try { clean(target,endpoint);((ObjectNode)target).put("completed",true);update(id,targets,"running",null);break; }
                    catch (java.io.IOException failure) { if (attempt==3 || failure instanceof VectorCollections.Failure rejected && !rejected.retryable()) throw failure; }
                }
            }
            update(id,targets,"succeeded",null);
        } catch (java.io.IOException | RuntimeException failure) {
            if (!stopping) update(id,targets,"failed","明确归属资源清理失败，资源仍保持删除，可从全局任务显式重试。");
        }
    }

    private void clean(JsonNode target,String endpoint) throws java.io.IOException {
        String type=target.path("type").asText();
        if (type.equals("copy")) { files.deleteCopy(target.path("versionId").asText(),target.path("path").asText());return; }
        var connection=settings.vectorConnection();
        if (endpoint==null || !endpoint.equals(connection.endpoint()) || type.equals("collection") && !target.path("endpoint").asText().equals(endpoint)) throw new ApiFailure(503,"CLEANUP_FAILED","向量连接与明确归属目标不一致，未清理远端资源。",null);
        String collection=target.path("collection").asText();JsonNode exists=vectors.request(endpoint,connection.secret(),collection,"GET",null);
        if (exists!=null) {
            if (type.equals("unknown_collection")) throw new ApiFailure(503,"CLEANUP_FAILED","历史集合缺少明确归属记录，已保留待核查，未按名称删除。",null);
            if (type.equals("collection")) {
                JsonNode result=vectors.request(endpoint,connection.secret(),collection,"DELETE",null);
                if (!result.path("result").isBoolean() || !result.path("result").asBoolean() || vectors.request(endpoint,connection.secret(),collection,"GET",null)!=null) throw new java.io.IOException("集合删除未核验。");
            } else {
                String body=json.writeValueAsString(java.util.Map.of("filter",java.util.Map.of("must",List.of(java.util.Map.of("key","index_id","match",java.util.Map.of("value",target.path("indexId").asText()))))));
                JsonNode result=vectors.request(endpoint,connection.secret(),collection+"/points/delete?wait=true","POST",body);
                if (result==null || !result.at("/result/status").asText().equals("completed")) throw new java.io.IOException("向量删除未确认完成。");
            }
        }
        if (type.equals("index")) data.write(jdbc -> { jdbc.sql("DELETE FROM index_chunks WHERE index_id=?").param(target.path("indexId").asText()).update();return null; });
    }

    private void update(String id,JsonNode targets,String state,String message) {
        data.write(jdbc -> {
            ObjectNode task=(ObjectNode)task(jdbc,id);task.put("state",state).put("phase",state.equals("running")?"cleaning":"completed");if (task.path("startedAt").isNull()) task.put("startedAt",Instant.now().toString());
            task.put("attempt",Math.max(1,targets.valueStream().mapToInt(item -> item.path("attempts").asInt()).max().orElse(0))).put("progressCompleted",targets.valueStream().filter(item -> item.path("completed").asBoolean()).count());
            if (!state.equals("running")) task.put("finishedAt",Instant.now().toString());
            if (message==null) task.putNull("failure");else task.set("failure",json.createObjectNode().put("code","CLEANUP_FAILED").put("message",message).put("retryable",false));
            jdbc.sql("UPDATE deletion_cleanup SET targets=? WHERE task_id=?").params(json.writeValueAsString(targets),id).update();persist(jdbc,task);return null;
        });
    }

    public record Page(List<JsonNode> items,String nextCursor) { public Page { items=List.copyOf(items); } }
    @jakarta.annotation.PreDestroy public void close() { stopping=true;worker.shutdownNow();try { worker.awaitTermination(6,java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); } }
}
