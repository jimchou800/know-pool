package cn.knowpool.infrastructure;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public final class TaskEvents {
    private final ApplicationData data;
    private final JsonMapper json;

    public TaskEvents(ApplicationData data,JsonMapper json){this.data=data;this.json=json;}

    public JsonNode snapshot(String taskId){
        String id;
        try {
            id=java.util.UUID.fromString(taskId).toString();
            if(!id.equals(taskId.toLowerCase(java.util.Locale.ROOT)))throw new IllegalArgumentException();
        } catch(IllegalArgumentException failure){throw ApiFailure.invalid("taskId","任务标识格式无效。");}
        String validId=id;
        return data.write(jdbc->{JsonNode task=json.readTree(jdbc.sql("SELECT payload FROM tasks WHERE id=?").param(validId).query(String.class).optional()
                .orElseThrow(()->new ApiFailure(404,"NOT_FOUND","任务不存在。",null)));
            record(jdbc,json,task);
            return json.readTree(jdbc.sql("SELECT payload FROM task_events WHERE task_id=?").param(validId).query(String.class).single()).path("event");
        });
    }

    public static void record(JdbcClient jdbc,JsonMapper json,JsonNode task){
        String id=task.path("id").asText();
        List<String> previous=jdbc.sql("SELECT payload FROM task_events WHERE task_id=?").param(id).query(String.class).list();
        if(!previous.isEmpty()&&json.readTree(previous.getFirst()).path("task").equals(task))return;
        long sequence=previous.isEmpty()?1:json.readTree(previous.getFirst()).at("/event/sequence").asLong()+1;
        if(sequence>9007199254740991L)throw new IllegalStateException("任务事件序号超过安全整数范围。");
        ObjectNode event=json.createObjectNode().put("taskId",id).put("sequence",sequence).put("state",task.path("state").asText()).put("phase",task.path("phase").asText()).put("occurredAt",Instant.now().toString());
        ObjectNode stored=json.createObjectNode();stored.set("task",task);stored.set("event",event);
        jdbc.sql("INSERT INTO task_events(task_id,sequence,payload) VALUES(?,?,?) ON CONFLICT(task_id) DO UPDATE SET sequence=excluded.sequence,payload=excluded.payload")
                .params(id,sequence,json.writeValueAsString(stored)).update();
    }
}
