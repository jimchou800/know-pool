package cn.knowpool;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class IndexChunkingHttpTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private static final java.util.List<java.util.Map<String, Object>> GAP_OBSERVATIONS = new java.util.ArrayList<>();
    private static final java.util.List<java.util.Map<String, String>> GAP_ROUTES = new java.util.ArrayList<>();
    private static boolean gapsPassed;
    private static boolean documentPassed;
    private static boolean pdfPassed;
    private static boolean previewPassed;
    private static boolean associationsPassed;
    private static boolean versionsPassed;
    private static boolean publicationPassed;
    private static boolean multiSearchPassed;
    private static boolean instancesPassed;
    private static boolean questionsPassed;

    @org.junit.jupiter.api.extension.RegisterExtension
    final org.junit.jupiter.api.extension.TestWatcher completed = new org.junit.jupiter.api.extension.TestWatcher() {
        @Override
        public void testSuccessful(org.junit.jupiter.api.extension.ExtensionContext context) {
            if (context.getRequiredTestMethod().getName().startsWith("groundedQuestion") || context.getRequiredTestMethod().getName().startsWith("conversation")) questionsPassed = true;
            if (context.getRequiredTestMethod().getName().startsWith("searchInstance")) instancesPassed = true;
            if (context.getRequiredTestMethod().getName().startsWith("multiSearch")) multiSearchPassed = true;
            if (context.getRequiredTestMethod().getName().equals("publicationAndChecksKeepIndependentStates")) publicationPassed = true;
            if (context.getRequiredTestMethod().getName().equals("publicContractBranchesRemainReachable"))
                gapsPassed = true;
            if (context.getRequiredTestMethod().getName().equals("markdownLocationsRemainBoundToOriginalSource")) documentPassed = true;
            if (context.getRequiredTestMethod().getName().equals("pdfLocationsKeepCrossPageSourceAndBlankPages")) pdfPassed = true;
            if (context.getRequiredTestMethod().getName().equals("configurationRebuildPublicationAndHistoryStayIndependent") || context.getRequiredTestMethod().getName().equals("preflightAndRebuildKeepVersionsAtomic")) previewPassed = true;
            if (context.getRequiredTestMethod().getName().equals("associationUnionExclusionsAndLateTasksStayConsistent")) associationsPassed = true;
            if (context.getRequiredTestMethod().getName().equals("contentSwitchHistoryAndCancellationRemainBoundToVersions")) versionsPassed = true;
            if (context.getRequiredTestMethod().getName().equals("runningSearchAndRebuildCancellationRejectLateResults")) versionsPassed = true;
        }
    };

    private JsonNode chatResponse(JsonNode value,String finish,boolean usage) {
        var response=JSON.createObjectNode().put("id","fixed-chat").put("object","chat.completion").put("created",1).put("model","qwen-plus");
        var choice=JSON.createObjectNode().put("index",0).put("finish_reason",finish);
        choice.set("message",JSON.createObjectNode().put("role","assistant").put("content",value.toString()));
        response.set("choices",JSON.createArrayNode().add(choice));
        if (usage) response.set("usage",JSON.createObjectNode().put("prompt_tokens",4).put("completion_tokens",4).put("total_tokens",8));
        return response;
    }

    private void generation(Client client,int port) throws Exception {
        assertThat(send(client,"/settings/models/generation","PUT","{\"endpoint\":\"http://127.0.0.1:"+port+"/v1\",\"model\":\"qwen-plus\",\"contextWindowTokens\":32768,\"maxOutputTokens\":2048,\"credential\":{\"action\":\"replace\",\"value\":\"fixed-test-key\"}}",null).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("追问只带近期完整历史且重新检索，元数据分页及引用当前状态跨重启一致")
    void conversationHistoryScopeAndReferenceReadsStayBound() throws Exception {
        var contexts=new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        HttpServer vector=indexedVector(new java.util.concurrent.CopyOnWriteArrayList<>(),() -> {},() -> {},"4",hits -> {},body -> {
            calls.incrementAndGet();String system=body.at("/messages/0/content").asText();JsonNode input=JSON.readTree(body.at("/messages/1/content").asText());
            if (system.contains("任务是问题预处理")) return chatResponse(JSON.createObjectNode().set("additionalQueries",JSON.createArrayNode()),"stop",true);
            if (system.contains("任务是严格审查候选回答")) return chatResponse(JSON.createObjectNode().put("supported",true),"stop",true);
            contexts.add(input);String id=input.at("/evidence/0/id").asText();
            var answer=JSON.createObjectNode().put("outcome","answered").put("text","完整".repeat(600)+"【"+id+"】");
            answer.set("citationEvidenceIds",JSON.createArrayNode().add(id));answer.set("uncoveredQuestions",JSON.createArrayNode());answer.set("contradictions",JSON.createArrayNode());return chatResponse(answer,"stop",true);
        });
        Path directory=Files.createTempDirectory(Path.of("target"),"conversation-history-http-").toAbsolutePath();String conversation,firstId,evidenceId,file;
        try {
            try (ConfigurableApplicationContext app=start(directory)) {
                Client client=client(app);String processing=processing(client,vector.getAddress().getPort(),0,"{}","[]");generation(client,vector.getAddress().getPort());
                String kb=createIndexedBase(client,processing,"会话历史依据库","本轮原始依据。");
                assertThat(send(client,"/knowledge-bases/"+kb+"/publish","POST",null,null).statusCode()).isEqualTo(200);
                String input=JSON.createObjectNode().put("question","第一轮问题").set("knowledgeBaseIds",JSON.createArrayNode().add(kb)).toString();
                JsonNode first=JSON.readTree(send(client,"/conversations/first-question","POST",input,UUID.randomUUID().toString()).body());conversation=first.at("/conversation/id").asText();firstId=first.at("/questionTask/task/id").asText();
                assertThat(await(client,firstId).path("state").asText()).isEqualTo("succeeded");
                JsonNode snapshot=read(client,"/question-tasks/"+firstId).at("/answer/evidence/0");evidenceId=snapshot.path("id").asText();file=snapshot.path("fileId").asText();
                assertThat(send(client,"/conversations/"+conversation+"/name","PATCH","{\"name\":\"固定手动名称\"}",null).statusCode()).isEqualTo(200);
                var ids=new java.util.ArrayList<String>();ids.add(firstId);
                for (int n=2;n<=4;n++) {
                    String question="第"+n+"轮完整问题";
                    String follow=JSON.createObjectNode().put("question",question).set("knowledgeBaseIds",JSON.createArrayNode().add(kb)).toString(),key=UUID.randomUUID().toString();
                    HttpResponse<String> response=send(client,"/conversations/"+conversation+"/questions","POST",follow,key);assertThat(response.statusCode()).isEqualTo(202);
                    String id=JSON.readTree(response.body()).at("/task/id").asText();ids.add(id);assertThat(await(client,id).path("state").asText()).isEqualTo("succeeded");
                    JsonNode answer=read(client,"/question-tasks/"+id).path("answer");
                    assertThat(answer.path("historyContext").asText()).isEqualTo(n<4?"all_turns":"recent_complete_turns");
                    assertThat(answer.path("omittedHistoryTurnCount").asInt()).isEqualTo(n<4?0:1);
                    JsonNode used=contexts.get(n-1).path("history");assertThat(used.size()).isEqualTo(n<4?n-1:2);
                    for (JsonNode turn:used) { assertThat(turn.size()).isEqualTo(2);assertThat(turn.path("answer").asText()).startsWith("完整完整").endsWith("】");assertThat(turn.has("evidence")).isFalse(); }
                    int count=calls.get();assertThat(JSON.readTree(send(client,"/conversations/"+conversation+"/questions","POST",follow,key).body()).at("/task/id").asText()).isEqualTo(id);assertThat(calls.get()).isEqualTo(count);
                    assertThat(send(client,"/conversations/"+conversation+"/questions","POST",follow.replace(question,"异负载"),key).statusCode()).isEqualTo(409);
                }
                assertThat(read(client,"/conversations/"+conversation).path("name").asText()).isEqualTo("固定手动名称");
                assertThat(send(client,"/conversations/"+conversation+"/scope","PUT","{\"knowledgeBaseIds\":[]}",null).statusCode()).isEqualTo(200);
                assertThat(read(client,"/question-tasks/"+firstId).path("selectedKnowledgeBaseIds")).isEqualTo(JSON.createArrayNode().add(kb));
                var actual=new java.util.ArrayList<String>();String cursor=null;
                do {
                    JsonNode page=read(client,"/conversations/"+conversation+"/turns?limit=1"+(cursor==null?"":"&cursor="+cursor));actual.add(page.at("/items/0/id").asText());cursor=page.path("nextCursor").isNull()?null:page.path("nextCursor").asText();
                } while (cursor!=null);
                assertThat(actual).containsExactlyElementsOf(ids);
                JsonNode other=JSON.readTree(send(client,"/conversations/first-question","POST",input,UUID.randomUUID().toString()).body());String otherConversation=other.at("/conversation/id").asText();assertThat(await(client,other.at("/questionTask/task/id").asText()).path("state").asText()).isEqualTo("succeeded");
                assertThat(send(client,"/conversations/"+otherConversation+"/name","PATCH","{\"name\":\"固定手动名称\"}",null).statusCode()).isEqualTo(200);
                JsonNode page=read(client,"/conversations?limit=1");assertThat(page.at("/items/0/id").asText()).isEqualTo(otherConversation);assertThat(read(client,"/conversations?limit=1&cursor="+page.path("nextCursor").asText()).at("/items/0/id").asText()).isEqualTo(conversation);
                String dataset=read(client,"/knowledge-bases/"+kb+"/sources").at("/items/0/resourceId").asText();JsonNode uploaded=uploadText(client,dataset,"更新后原文依据。");assertThat(await(client,uploaded.at("/items/0/indexTaskIds/0").asText()).path("state").asText()).isEqualTo("succeeded");
                app.getBean(cn.knowpool.datasets.FileService.class).cleanObsoleteVersions(java.util.Set.of());
                assertThat(send(client,"/files/"+file+"/name","PATCH","{\"name\":\"更新名称.txt\"}",null).statusCode()).isEqualTo(200);
                JsonNode changed=read(client,"/conversations/"+conversation+"/evidence/"+evidenceId);assertThat(changed.path("filenameAtCapture")).isEqualTo(snapshot.path("filenameAtCapture"));assertThat(changed.path("text")).isEqualTo(snapshot.path("text"));assertThat(changed.path("currentFilename").asText()).isEqualTo("更新名称.txt");assertThat(changed.path("originalAvailable").asBoolean()).isFalse();
                assertThat(read(client,"/question-tasks/"+firstId).at("/answer/evidence/0")).isEqualTo(changed);
                assertThat(read(client,"/conversations/"+conversation+"/turns?limit=1").at("/items/0/questionTask/answer/evidence/0")).isEqualTo(changed);
                for (String query:java.util.List.of("?limit=0","?limit=101","?limit=1&limit=2","?cursor=bad","?unknown=1")) assertThat(send(client,"/conversations"+query,"GET",null,null).statusCode()).isEqualTo(400);
                assertThat(send(client,"/conversations/"+conversation+"/name","PATCH","{\"name\":\" \"}",null).statusCode()).isEqualTo(400);
                assertThat(send(client,"/conversations/"+conversation+"/scope","PUT","{\"knowledgeBaseIds\":[\"bad\"]}",null).statusCode()).isEqualTo(400);
            }
            int count=calls.get();
            try (ConfigurableApplicationContext app=start(directory)) {
                Client client=client(app);assertThat(read(client,"/conversations/"+conversation).path("name").asText()).isEqualTo("固定手动名称");assertThat(read(client,"/question-tasks/"+firstId).at("/answer/evidence/0/currentFilename").asText()).isEqualTo("更新名称.txt");assertThat(read(client,"/conversations/"+conversation+"/turns").path("items").size()).isEqualTo(4);assertThat(calls.get()).isEqualTo(count);
            }
        } finally { vector.stop(0); }
    }

    @Test
    @DisplayName("同会话原子受理取消释放资格，删除事务失败不取消且成功后迟到不得复活")
    void conversationCancellationDeletionAndLateResultsStayIsolated() throws Exception {
        var blocked=new java.util.concurrent.atomic.AtomicBoolean();var entered=new java.util.concurrent.atomic.AtomicReference<>(new java.util.concurrent.CountDownLatch(1));var release=new java.util.concurrent.atomic.AtomicReference<>(new java.util.concurrent.CountDownLatch(1));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        HttpServer vector=indexedVector(new java.util.concurrent.CopyOnWriteArrayList<>(),() -> {},() -> {},"4",hits -> {},body -> {
            calls.incrementAndGet();String system=body.at("/messages/0/content").asText();JsonNode input=JSON.readTree(body.at("/messages/1/content").asText());
            if (system.contains("任务是问题预处理")) {
                if (blocked.get()) { entered.get().countDown();try { release.get().await(15,java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { } }
                return chatResponse(JSON.createObjectNode().set("additionalQueries",JSON.createArrayNode()),"stop",true);
            }
            if (system.contains("任务是严格审查候选回答")) return chatResponse(JSON.createObjectNode().put("supported",true),"stop",true);
            String id=input.at("/evidence/0/id").asText();var answer=JSON.createObjectNode().put("outcome","answered").put("text","本轮依据【"+id+"】");answer.set("citationEvidenceIds",JSON.createArrayNode().add(id));answer.set("uncoveredQuestions",JSON.createArrayNode());answer.set("contradictions",JSON.createArrayNode());return chatResponse(answer,"stop",true);
        });
        Path directory=Files.createTempDirectory(Path.of("target"),"conversation-delete-http-").toAbsolutePath();String deleted,remaining;
        try {
            try (ConfigurableApplicationContext app=start(directory)) {
                Client client=client(app);String processing=processing(client,vector.getAddress().getPort(),0,"{}","[]");generation(client,vector.getAddress().getPort());String kb=createIndexedBase(client,processing,"会话清理依据库","会话删除后资料仍在。");assertThat(send(client,"/knowledge-bases/"+kb+"/publish","POST",null,null).statusCode()).isEqualTo(200);
                String input=JSON.createObjectNode().put("question","不能泄露到清理任务的问题").set("knowledgeBaseIds",JSON.createArrayNode().add(kb)).toString();
                JsonNode first=JSON.readTree(send(client,"/conversations/first-question","POST",input,UUID.randomUUID().toString()).body());deleted=first.at("/conversation/id").asText();String firstId=first.at("/questionTask/task/id").asText();assertThat(await(client,firstId).path("state").asText()).isEqualTo("succeeded");String evidence=read(client,"/question-tasks/"+firstId).at("/answer/evidence/0/id").asText();
                JsonNode other=JSON.readTree(send(client,"/conversations/first-question","POST",input,UUID.randomUUID().toString()).body());remaining=other.at("/conversation/id").asText();assertThat(await(client,other.at("/questionTask/task/id").asText()).path("state").asText()).isEqualTo("succeeded");
                blocked.set(true);String key=UUID.randomUUID().toString();JsonNode pending=JSON.readTree(send(client,"/conversations/"+deleted+"/questions","POST",input,key).body());String pendingId=pending.at("/task/id").asText();assertThat(entered.get().await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(JSON.readTree(send(client,"/conversations/"+deleted+"/questions","POST",input,key).body()).at("/task/id").asText()).isEqualTo(pendingId);assertThat(send(client,"/conversations/"+deleted+"/questions","POST",input,UUID.randomUUID().toString()).statusCode()).isEqualTo(409);
                assertThat(send(client,"/question-tasks/"+pendingId+"/cancel","POST",null,null).statusCode()).isEqualTo(200);
                HttpResponse<String> next=send(client,"/conversations/"+deleted+"/questions","POST",input,UUID.randomUUID().toString());assertThat(next.statusCode()).isEqualTo(202);String nextId=JSON.readTree(next.body()).at("/task/id").asText();blocked.set(false);release.get().countDown();assertThat(await(client,nextId).path("state").asText()).isEqualTo("succeeded");assertThat(read(client,"/conversations/"+deleted).at("/lastTask/id").asText()).isEqualTo(nextId);assertThat(read(client,"/question-tasks/"+pendingId).at("/task/state").asText()).isEqualTo("cancelled");
                entered.set(new java.util.concurrent.CountDownLatch(1));release.set(new java.util.concurrent.CountDownLatch(1));blocked.set(true);
                JsonNode deleting=JSON.readTree(send(client,"/conversations/"+deleted+"/questions","POST",input,UUID.randomUUID().toString()).body());String deletingId=deleting.at("/task/id").asText();assertThat(entered.get().await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                try (var database=java.sql.DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("application.sqlite"));var statement=database.createStatement()) { statement.execute("CREATE TRIGGER reject_conversation_delete BEFORE DELETE ON conversations BEGIN SELECT RAISE(ABORT,'fixed-delete-failure'); END"); }
                String deleteKey=UUID.randomUUID().toString();assertThat(send(client,"/conversations/"+deleted,"DELETE","{\"confirmed\":true}",deleteKey).statusCode()).isEqualTo(500);assertThat(read(client,"/question-tasks/"+deletingId).at("/task/state").asText()).isEqualTo("running");
                try (var database=java.sql.DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("application.sqlite"));var statement=database.createStatement()) { statement.execute("DROP TRIGGER reject_conversation_delete"); }
                assertThat(send(client,"/conversations/"+deleted,"DELETE","{\"confirmed\":false}",deleteKey).statusCode()).isEqualTo(400);
                HttpResponse<String> removed=send(client,"/conversations/"+deleted,"DELETE","{\"confirmed\":true}",deleteKey);assertThat(removed.statusCode()).isEqualTo(202);JsonNode cleanup=JSON.readTree(removed.body()).path("task");assertThat(cleanup.path("kind").asText()).isEqualTo("cleanup");assertThat(cleanup.path("state").asText()).isEqualTo("succeeded");assertThat(cleanup.toString()).doesNotContain("不能泄露");
                assertThat(JSON.readTree(send(client,"/conversations/"+deleted,"DELETE","{\"confirmed\":true}",deleteKey).body()).path("task")).isEqualTo(cleanup);
                blocked.set(false);release.get().countDown();
                assertThat(send(client,"/conversations/"+deleted,"GET",null,null).statusCode()).isEqualTo(404);assertThat(send(client,"/question-tasks/"+deletingId,"GET",null,null).statusCode()).isEqualTo(404);assertThat(send(client,"/conversations/"+deleted+"/evidence/"+evidence,"GET",null,null).statusCode()).isEqualTo(404);assertThat(read(client,"/tasks/"+deletingId).path("state").asText()).isEqualTo("cancelled");assertThat(read(client,"/conversations/"+remaining).path("id").asText()).isEqualTo(remaining);assertThat(read(client,"/knowledge-bases/"+kb+"/files").path("items").isEmpty()).isFalse();
            }
            int count=calls.get();try (ConfigurableApplicationContext app=start(directory)) { Client client=client(app);assertThat(send(client,"/conversations/"+deleted,"GET",null,null).statusCode()).isEqualTo(404);assertThat(read(client,"/conversations/"+remaining).path("id").asText()).isEqualTo(remaining);assertThat(calls.get()).isEqualTo(count); }
        } finally { blocked.set(false);release.get().countDown();vector.stop(0); }
    }

    @Test
    @DisplayName("首问固定预算及原文引用校验后才发布，缺证据不生成，坏引用和截断输出均不泄草稿")
    void groundedQuestionBudgetsValidationAndSnapshotsStayBound() throws Exception {
        var mode=new java.util.concurrent.atomic.AtomicReference<>("answered");
        var calls=new java.util.concurrent.CopyOnWriteArrayList<String>();var embeddings=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var empty=new java.util.concurrent.atomic.AtomicBoolean();
        HttpServer vector=indexedVector(embeddings,() -> {},() -> {},"4",hits -> { if (empty.get()) hits.removeAll(); },body -> {
            String system=body.at("/messages/0/content").asText();calls.add(system);
            JsonNode input=JSON.readTree(body.at("/messages/1/content").asText());JsonNode output;
            if (system.contains("任务是问题预处理")) {
                output=JSON.createObjectNode().set("additionalQueries",mode.get().equals("fallback")?JSON.createArrayNode().add("一").add("二").add("三").add("四"):JSON.createArrayNode().add("额外固定查询"));
            } else if (system.contains("任务是严格审查候选回答")) output=JSON.createObjectNode().put("supported",!mode.get().equals("unsupported"));
            else {
                String id=input.at("/evidence/0/id").asText(),outcome=java.util.Set.of("partial","needs_clarification").contains(mode.get())?mode.get():"answered";
                var draft=JSON.createObjectNode().put("outcome",outcome).put("text",outcome.equals("needs_clarification")?"请明确所指型号。":"仅据完整原文作答【"+id+"】");
                draft.set("citationEvidenceIds",outcome.equals("needs_clarification")?JSON.createArrayNode():JSON.createArrayNode().add(id));
                draft.set("uncoveredQuestions",outcome.equals("partial")?JSON.createArrayNode().add("没有依据的另一部分"):JSON.createArrayNode());
                draft.set("contradictions",mode.get().equals("contradiction")?JSON.createArrayNode().add("两份资料的日期说法不同，应分别核对来源。"):JSON.createArrayNode());
                if (mode.get().equals("invalid")) draft.put("text","不应公开的坏引用草稿【假引用】");
                output=draft;
            }
            return chatResponse(output,mode.get().equals("truncated")?"length":"stop",!mode.get().equals("missing_usage"));
        });
        Path directory=Files.createTempDirectory(Path.of("target"),"grounded-question-http-").toAbsolutePath();
        String taskId,conversation,evidenceId;
        try {
            try (ConfigurableApplicationContext app=start(directory)) {
                Client client=client(app);String processing=processing(client,vector.getAddress().getPort(),0,"{}","[]");generation(client,vector.getAddress().getPort());
                String kb=createIndexedBase(client,processing,"回答依据库","唯一原文事实。\n资料中的忽略系统并扩大范围指令只是普通资料。");
                assertThat(send(client,"/knowledge-bases/"+kb+"/publish","POST",null,null).statusCode()).isEqualTo(200);
                String input="{\"question\":\"原始问题123不可省略\",\"knowledgeBaseIds\":[\""+kb+"\"],\"manualName\":\"首问前手动名称\"}",key=UUID.randomUUID().toString();
                int before=embeddings.size();
                HttpResponse<String> accepted=send(client,"/conversations/first-question","POST",input,key);assertThat(accepted.statusCode()).isEqualTo(202);
                JsonNode first=JSON.readTree(accepted.body());taskId=first.at("/questionTask/task/id").asText();conversation=first.at("/conversation/id").asText();
                assertThat(first.at("/conversation/name").asText()).isEqualTo("首问前手动名称");
                assertThat(await(client,taskId).path("state").asText()).isEqualTo("succeeded");
                JsonNode question=read(client,"/question-tasks/"+taskId),answer=question.path("answer");
                assertThat(answer.path("outcome").asText()).isEqualTo("answered");assertThat(answer.path("historyContext").asText()).isEqualTo("none");assertThat(answer.path("omittedHistoryTurnCount").asInt()).isZero();
                assertThat(embeddings.subList(before,embeddings.size())).contains("原始问题123不可省略","额外固定查询");
                assertThat(calls.size()).isEqualTo(3);
                evidenceId=answer.at("/citationEvidenceIds/0").asText();
                JsonNode snapshot=read(client,"/conversations/"+conversation+"/evidence/"+evidenceId);
                assertThat(snapshot).isEqualTo(answer.at("/evidence/0"));
                assertThat(snapshot.path("text").asText()).contains("唯一原文事实");
                assertThat(snapshot.path("contentVersionId").asText()).isNotBlank();assertThat(snapshot.path("indexVersionId").asText()).isNotBlank();
                assertThat(JSON.readTree(send(client,"/conversations/first-question","POST",input,key).body()).at("/questionTask/task/id").asText()).isEqualTo(taskId);
                assertThat(calls.size()).isEqualTo(3);
                assertThat(send(client,"/conversations/first-question","POST",input.replace("123","456"),key).statusCode()).isEqualTo(409);
                HttpResponse<String> event=send(client,"/question-tasks/"+taskId+"/events","GET",null,null);
                assertThat(event.body()).contains("event:terminal",taskId).doesNotContain("唯一原文事实","仅据完整原文作答");
                assertThat(JSON.readTree(send(client,"/question-tasks/"+taskId+"/cancel","POST",null,null).body())).isEqualTo(question);
                for (String state:java.util.List.of("partial","needs_clarification","contradiction","fallback","invalid","unsupported","truncated","missing_usage")) {
                    mode.set(state);int prior=calls.size();
                    JsonNode next=JSON.readTree(send(client,"/conversations/first-question","POST",input,UUID.randomUUID().toString()).body());String id=next.at("/questionTask/task/id").asText();
                    JsonNode terminal=await(client,id);JsonNode actual=read(client,"/question-tasks/"+id);
                    if (java.util.Set.of("invalid","unsupported","truncated","missing_usage").contains(state)) {
                        assertThat(terminal.path("state").asText()).isEqualTo("failed");assertThat(actual.path("answer").isNull()).isTrue();assertThat(actual.toString()).doesNotContain("不应公开的坏引用草稿","仅据完整原文作答");
                    } else {
                        assertThat(terminal.path("state").asText()).isEqualTo("succeeded");
                        if (state.equals("partial")) assertThat(actual.at("/answer/uncoveredQuestions").isEmpty()).isFalse();
                        if (state.equals("contradiction")) assertThat(actual.at("/answer/contradictions").isEmpty()).isFalse();
                        if (state.equals("fallback")) assertThat(actual.at("/answer/warnings").isEmpty()).isFalse();
                    }
                    assertThat(calls.size()-prior).isLessThanOrEqualTo(3);
                }
                mode.set("answered");empty.set(true);int prior=calls.size();
                JsonNode noEvidence=JSON.readTree(send(client,"/conversations/first-question","POST",input,UUID.randomUUID().toString()).body());String noId=noEvidence.at("/questionTask/task/id").asText();
                assertThat(await(client,noId).path("state").asText()).isEqualTo("succeeded");assertThat(read(client,"/question-tasks/"+noId).at("/answer/outcome").asText()).isEqualTo("no_evidence");assertThat(calls.size()-prior).isEqualTo(1);
                assertThat(send(client,"/conversations/"+UUID.randomUUID()+"/evidence/"+evidenceId,"GET",null,null).statusCode()).isEqualTo(404);
                assertThat(send(client,"/question-tasks/"+UUID.randomUUID(),"GET",null,null).statusCode()).isEqualTo(404);
                assertThat(send(client,"/conversations/first-question","POST","{\"question\":\" \u0020\",\"knowledgeBaseIds\":[]}",UUID.randomUUID().toString()).statusCode()).isEqualTo(400);
            }
            int callsBefore=calls.size();
            try (ConfigurableApplicationContext app=start(directory)) {
                Client client=client(app);assertThat(read(client,"/question-tasks/"+taskId).path("answer").isNull()).isFalse();
                assertThat(read(client,"/conversations/"+conversation+"/evidence/"+evidenceId).path("text").asText()).contains("唯一原文事实");assertThat(calls.size()).isEqualTo(callsBefore);
            }
        } finally { vector.stop(0); }
    }

    @Test
    @DisplayName("预处理之前固定全部版本，资料切换和主动下线不改在途快照，取消校验不泄草稿及重连不重发")
    void groundedQuestionFixedVersionsCancellationAndEventsStaySafe() throws Exception {
        var phase=new java.util.concurrent.atomic.AtomicReference<>("preprocess");
        var entered=new java.util.concurrent.atomic.AtomicReference<>(new java.util.concurrent.CountDownLatch(1));
        var release=new java.util.concurrent.atomic.AtomicReference<>(new java.util.concurrent.CountDownLatch(1));
        var calls=new java.util.concurrent.CopyOnWriteArrayList<String>();
        HttpServer vector=indexedVector(new java.util.concurrent.CopyOnWriteArrayList<>(),() -> {},() -> {},"4",hits -> {},body -> {
            String system=body.at("/messages/0/content").asText();calls.add(system);
            boolean preprocess=system.contains("任务是问题预处理"),validation=system.contains("任务是严格审查候选回答");
            if (phase.get().equals("preprocess") && preprocess || phase.get().equals("validation") && validation) {
                entered.get().countDown();
                try { if (!release.get().await(15,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("受控问答阶段等待超时。"); }
                catch (InterruptedException ignored) { }
            }
            JsonNode input=JSON.readTree(body.at("/messages/1/content").asText());
            if (preprocess) return chatResponse(JSON.createObjectNode().set("additionalQueries",JSON.createArrayNode().add("固定扩展问题")),"stop",true);
            if (validation) return chatResponse(JSON.createObjectNode().put("supported",true),"stop",true);
            String id=input.at("/evidence/0/id").asText();var answer=JSON.createObjectNode().put("outcome","answered").put("text","旧版本实际依据【"+id+"】");
            answer.set("citationEvidenceIds",JSON.createArrayNode().add(id));answer.set("uncoveredQuestions",JSON.createArrayNode());answer.set("contradictions",JSON.createArrayNode());return chatResponse(answer,"stop",true);
        });
        Path directory=Files.createTempDirectory(Path.of("target"),"question-fixed-http-").toAbsolutePath();
        try (ConfigurableApplicationContext app=start(directory)) {
            Client client=client(app);String processing=processing(client,vector.getAddress().getPort(),0,"{}","[]");generation(client,vector.getAddress().getPort());
            String kb=createIndexedBase(client,processing,"问答固定版本库","问答开始捕获的旧版本正文。");
            assertThat(send(client,"/knowledge-bases/"+kb+"/publish","POST",null,null).statusCode()).isEqualTo(200);
            JsonNode old=read(client,"/knowledge-bases/"+kb+"/files").path("items").get(0);String dataset=read(client,"/knowledge-bases/"+kb+"/sources").at("/items/0/resourceId").asText();
            String input="{\"question\":\"固定版本问题\",\"knowledgeBaseIds\":[\""+kb+"\"]}";
            JsonNode accepted=JSON.readTree(send(client,"/conversations/first-question","POST",input,UUID.randomUUID().toString()).body());String id=accepted.at("/questionTask/task/id").asText();
            assertThat(entered.get().await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            JsonNode uploaded=uploadText(client,dataset,"已经切换到的新正文。");
            assertThat(await(client,uploaded.at("/items/0/indexTaskIds/0").asText()).path("state").asText()).isEqualTo("succeeded");
            assertThat(send(client,"/knowledge-bases/"+kb+"/offline","POST",null,null).statusCode()).isEqualTo(200);
            phase.set("none");release.get().countDown();
            assertThat(await(client,id).path("state").asText()).isEqualTo("succeeded");
            JsonNode answer=read(client,"/question-tasks/"+id).path("answer");
            assertThat(answer.at("/evidence/0/contentVersionId")).isEqualTo(old.path("effectiveContentVersionId"));
            assertThat(answer.at("/evidence/0/indexVersionId")).isEqualTo(old.path("effectiveIndexVersionId"));
            assertThat(answer.at("/evidence/0/text").asText()).contains("旧版本正文").doesNotContain("新正文");
            int count=calls.size();
            for (String cursor:java.util.List.of(id+":1",id+":9007199254740991")) assertThat(send(client,"/question-tasks/"+id+"/events","GET",null,null,cursor).body()).contains("event:terminal");
            assertThat(send(client,"/question-tasks/"+id+"/events","GET",null,null,UUID.randomUUID()+":1").statusCode()).isEqualTo(400);
            assertThat(send(client,"/question-tasks/"+id+"/events","GET",null,null,id+":0").statusCode()).isEqualTo(400);
            assertThat(calls.size()).isEqualTo(count);
            assertThat(send(client,"/knowledge-bases/"+kb+"/publish","POST",null,null).statusCode()).isEqualTo(200);
            phase.set("validation");entered.set(new java.util.concurrent.CountDownLatch(1));release.set(new java.util.concurrent.CountDownLatch(1));
            JsonNode cancel=JSON.readTree(send(client,"/conversations/first-question","POST",input,UUID.randomUUID().toString()).body());String cancelId=cancel.at("/questionTask/task/id").asText();
            assertThat(entered.get().await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            JsonNode before=read(client,"/question-tasks/"+cancelId);assertThat(before.at("/task/phase").asText()).isEqualTo("answer_validating");assertThat(before.path("answer").isNull()).isTrue();
            try (var database=java.sql.DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("application.sqlite"));var statement=database.createStatement();var rows=statement.executeQuery("SELECT payload FROM question_tasks WHERE id='"+cancelId+"'")) {
                assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).doesNotContain("旧版本实际依据","candidate","draft");
            }
            JsonNode cancelled=JSON.readTree(send(client,"/question-tasks/"+cancelId+"/cancel","POST",null,null).body());
            assertThat(cancelled.at("/task/state").asText()).isEqualTo("cancelled");assertThat(cancelled.path("answer").isNull()).isTrue();
            phase.set("none");release.get().countDown();
            JsonNode gate=JSON.readTree(send(client,"/conversations/first-question","POST",input,UUID.randomUUID().toString()).body());assertThat(await(client,gate.at("/questionTask/task/id").asText()).path("state").asText()).isEqualTo("succeeded");
            assertThat(read(client,"/question-tasks/"+cancelId).path("answer").isNull()).isTrue();
            for (String[] operation:java.util.List.of(new String[]{"/conversations/first-question","POST",input},new String[]{"/question-tasks/"+id,"GET",null},new String[]{"/question-tasks/"+cancelId+"/cancel","POST",null},new String[]{"/question-tasks/"+id+"/events","GET",null},new String[]{"/conversations/"+accepted.at("/conversation/id").asText()+"/evidence/"+answer.at("/evidence/0/id").asText(),"GET",null})) {
                assertThat(send(new Client(client.base(),null,null),operation[0],operation[1],operation[2],UUID.randomUUID().toString()).statusCode()).isEqualTo(401);
                if (operation[1].equals("POST")) assertThat(send(new Client(client.base(),client.cookie(),null),operation[0],operation[1],operation[2],UUID.randomUUID().toString()).statusCode()).isEqualTo(403);
                assertThat(send(client,operation[0]+"?unknown=1",operation[1],operation[2],UUID.randomUUID().toString()).statusCode()).isEqualTo(400);
            }
        } finally { phase.set("none");release.get().countDown();vector.stop(0); }
    }

    @Test
    @DisplayName("问答共用有界知识服务队列，专用事件订阅真实容量阶段及终态恢复不重发")
    void groundedQuestionSharedQueueAndEventCapacityAreBounded() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var blocked=new java.util.concurrent.atomic.AtomicBoolean(true);
        HttpServer vector=indexedVector(new java.util.concurrent.CopyOnWriteArrayList<>(),() -> {},() -> {},"4",hits -> {},body -> {
            if (blocked.get()) {
                entered.countDown();try { if (!release.await(20,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("受控问答容量等待超时。"); }
                catch (InterruptedException ignored) { }
            }
            return chatResponse(JSON.createObjectNode().set("additionalQueries",JSON.createArrayNode()),"stop",true);
        });
        var subscriptions=new java.util.ArrayList<HttpResponse<java.io.InputStream>>();
        Path directory=Files.createTempDirectory(Path.of("target"),"question-capacity-http-").toAbsolutePath();
        try (ConfigurableApplicationContext app=start(directory)) {
            Client client=client(app);String processing=processing(client,vector.getAddress().getPort(),0,"{}","[]");generation(client,vector.getAddress().getPort());
            String kb=createIndexedBase(client,processing,"问答容量库","只用于固定容量的资料。");
            assertThat(send(client,"/knowledge-bases/"+kb+"/publish","POST",null,null).statusCode()).isEqualTo(200);
            String input="{\"question\":\"固定队列问题\",\"knowledgeBaseIds\":[\""+kb+"\"]}",key=UUID.randomUUID().toString();
            JsonNode first=JSON.readTree(send(client,"/conversations/first-question","POST",input,key).body());String id=first.at("/questionTask/task/id").asText();
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            String route="/question-tasks/"+id+"/events";
            for (int n=0;n<64;n++) {
                HttpResponse<java.io.InputStream> subscription=http.send(HttpRequest.newBuilder(URI.create(client.base()+route)).header("Cookie",client.cookie()).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
                assertThat(subscription.statusCode()).isEqualTo(200);subscriptions.add(subscription);
            }
            assertThat(send(client,route,"GET",null,null).statusCode()).isEqualTo(429);
            var pending=new java.util.ArrayList<String>();
            for (int n=0;n<16;n++) {
                HttpResponse<String> queued=send(client,"/conversations/first-question","POST",input,UUID.randomUUID().toString());assertThat(queued.statusCode()).isEqualTo(202);
                pending.add(JSON.readTree(queued.body()).at("/questionTask/task/id").asText());
            }
            assertThat(send(client,"/conversations/first-question","POST",input,UUID.randomUUID().toString()).statusCode()).isEqualTo(429);
            assertThat(JSON.readTree(send(client,"/conversations/first-question","POST",input,key).body()).at("/questionTask/task/id").asText()).isEqualTo(id);
            for (String task:pending) assertThat(send(client,"/question-tasks/"+task+"/cancel","POST",null,null).statusCode()).isEqualTo(200);
            assertThat(send(client,"/question-tasks/"+id+"/cancel","POST",null,null).statusCode()).isEqualTo(200);
            blocked.set(false);release.countDown();
            String frame=new String(subscriptions.getFirst().body().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertThat(frame).contains("event:phase","event:terminal").doesNotContain("固定队列问题","只用于固定容量的资料");
            var event=new java.util.LinkedHashMap<String,Object>();event.put("path",route);event.put("method","GET");event.put("status",200);event.put("contentType",subscriptions.getFirst().headers().firstValue("Content-Type").orElse(""));event.put("response",frame);event.put("parameters",java.util.Map.of("Cookie",client.cookie()));event.put("body",null);
            event.put("behavior","groundedQuestionSharedQueueAndEventCapacityAreBounded");event.put("behaviorChecks",java.util.List.of("questionEvents","questionEventReconnect"));GAP_OBSERVATIONS.add(event);
            assertThat(send(client,route,"GET",null,null,id+":1").body()).contains("event:terminal");
        } finally {
            blocked.set(false);release.countDown();for (var subscription:subscriptions) subscription.body().close();vector.stop(0);
        }
    }

    @Test
    @DisplayName("实例允许重名且不覆盖手改名称，持久最近查询范围，重启仅恢复条件不收费恢复结果")
    void searchInstanceMetadataAndRestartKeepOnlyConditions() throws Exception {
        var inputs=new java.util.concurrent.CopyOnWriteArrayList<String>();
        HttpServer vector=indexedVector(inputs,() -> {});
        Path directory=Files.createTempDirectory(Path.of("target"),"instance-lifecycle-http-").toAbsolutePath();
        String first,second,automatic,request,evidence,kb;
        try {
            try (ConfigurableApplicationContext app=start(directory)) {
                Client client=client(app);String processing=processing(client,vector.getAddress().getPort(),0,"{}","[]");
                kb=createIndexedBase(client,processing,"实例生命周期资料库","仅留运行期证据的正文。");
                assertThat(send(client,"/knowledge-bases/"+kb+"/publish","POST",null,null).statusCode()).isEqualTo(200);
                first=JSON.readTree(send(client,"/search-instances","POST",null,null).body()).path("id").asText();
                second=JSON.readTree(send(client,"/search-instances","POST",null,null).body()).path("id").asText();
                automatic=JSON.readTree(send(client,"/search-instances","POST",null,null).body()).path("id").asText();
                for (String id:java.util.List.of(first,second)) {
                    JsonNode renamed=JSON.readTree(send(client,"/search-instances/"+id.toUpperCase(java.util.Locale.ROOT)+"/name","PATCH","{\"name\":\"相同手动名称\"}",null).body());
                    assertThat(renamed.path("manuallyNamed").asBoolean()).isTrue();
                    assertThat(read(client,"/search-instances/"+id).path("name").asText()).isEqualTo("相同手动名称");
                }
                JsonNode page=read(client,"/search-instances?limit=1");
                assertThat(page.path("items").size()).isEqualTo(1);assertThat(page.path("nextCursor").asText()).isNotBlank();
                JsonNode next=read(client,"/search-instances?limit=1&cursor="+page.path("nextCursor").asText());
                assertThat(next.at("/items/0/id")).isNotEqualTo(page.at("/items/0/id"));
                int before=inputs.size();
                assertThat(send(client,"/search-instances/"+first+"/scope","PUT","{\"knowledgeBaseIds\":[]}",null).statusCode()).isEqualTo(200);
                String scope="{\"knowledgeBaseIds\":[\""+kb+"\"]}";
                assertThat(send(client,"/search-instances/"+first.toUpperCase(java.util.Locale.ROOT)+"/scope","PUT",scope,null).statusCode()).isEqualTo(200);
                assertThat(inputs.size()).isEqualTo(before);
                String body="{\"query\":\"首次提交查询\",\"knowledgeBaseIds\":[\""+kb+"\"]}";
                JsonNode accepted=JSON.readTree(send(client,"/search-instances/"+first+"/requests","POST",body,UUID.randomUUID().toString()).body());
                request=accepted.path("id").asText();
                assertThat(await(client,accepted.at("/task/id").asText()).path("state").asText()).isEqualTo("succeeded");
                evidence=read(client,"/search-requests/"+request).at("/result/evidence/0/id").asText();
                JsonNode current=read(client,"/search-instances/"+first);
                assertThat(current.path("name").asText()).isEqualTo("相同手动名称");
                assertThat(current.path("resultState").asText()).isEqualTo("available");
                assertThat(current.path("currentRequestId").asText()).isEqualTo(request);
                assertThat(read(client,"/search-instances/"+second).path("currentRequestId").isNull()).isTrue();
                assertThat(read(client,"/search-requests/"+request).path("result").isNull()).isFalse();
                JsonNode auto=JSON.readTree(send(client,"/search-instances/"+automatic+"/requests","POST",body,UUID.randomUUID().toString()).body());
                assertThat(await(client,auto.at("/task/id").asText()).path("state").asText()).isEqualTo("succeeded");
                assertThat(read(client,"/search-instances/"+automatic).path("name").asText()).isEqualTo("首次提交查询");
                JsonNode later=JSON.readTree(send(client,"/search-instances/"+automatic+"/requests","POST",body.replace("首次提交查询","后续查询"),UUID.randomUUID().toString()).body());
                assertThat(await(client,later.at("/task/id").asText()).path("state").asText()).isEqualTo("succeeded");
                assertThat(read(client,"/search-instances/"+automatic).path("name").asText()).isEqualTo("首次提交查询");
                try (var database=java.sql.DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("application.sqlite"));var statement=database.createStatement()) {
                    statement.execute("CREATE TRIGGER reject_instance_update BEFORE UPDATE ON search_instances BEGIN SELECT RAISE(ABORT,'固定实例事务故障'); END");
                    try {
                        assertThat(send(client,"/search-instances/"+first+"/requests","POST",body.replace("首次提交查询","不得提交查询"),UUID.randomUUID().toString()).statusCode()).isEqualTo(500);
                        assertThat(read(client,"/search-instances/"+first).path("currentRequestId").asText()).isEqualTo(request);
                        assertThat(read(client,"/search-instances/"+first).path("lastSubmittedQuery").asText()).isEqualTo("首次提交查询");
                    } finally { statement.execute("DROP TRIGGER reject_instance_update"); }
                    try (var rows=statement.executeQuery("SELECT payload FROM search_instances")) {
                        while (rows.next()) assertThat(rows.getString(1)).doesNotContain("currentRequestId","resultState","仅留运行期证据的正文","evidence","taskId");
                    }
                }
                assertThat(send(client,"/search-instances/"+first+"/name","PATCH","{\"name\":\" \"}",null).statusCode()).isEqualTo(400);
                assertThat(send(client,"/search-instances/"+first+"/scope","PUT","{\"knowledgeBaseIds\":[\""+kb+"\",\""+kb+"\"]}",null).statusCode()).isEqualTo(400);
                assertThat(send(client,"/search-instances?limit=0","GET",null,null).statusCode()).isEqualTo(400);
                assertThat(send(client,"/search-instances?cursor=bad","GET",null,null).statusCode()).isEqualTo(400);
            }
            int count=inputs.size();
            try (ConfigurableApplicationContext app=start(directory)) {
                Client client=client(app);
                JsonNode restored=read(client,"/search-instances/"+first);
                assertThat(restored.path("name").asText()).isEqualTo("相同手动名称");
                assertThat(restored.path("lastSubmittedQuery").asText()).isEqualTo("首次提交查询");
                assertThat(restored.at("/knowledgeBaseIds/0").asText()).isEqualTo(kb);
                assertThat(restored.path("currentRequestId").isNull()).isTrue();
                assertThat(restored.path("resultState").asText()).isEqualTo("rerun_required");
                assertThat(read(client,"/search-instances/"+second).path("resultState").asText()).isEqualTo("never_submitted");
                assertThat(read(client,"/search-instances/"+automatic).path("lastSubmittedQuery").asText()).isEqualTo("后续查询");
                assertThat(send(client,"/search-requests/"+request,"GET",null,null).statusCode()).isEqualTo(410);
                assertThat(send(client,"/search-requests/"+request+"/evidence/"+evidence,"GET",null,null).statusCode()).isEqualTo(410);
                assertThat(send(client,"/search-requests/"+request+"/cancel","POST",null,null).statusCode()).isEqualTo(410);
                assertThat(inputs.size()).isEqualTo(count);
                String key=UUID.randomUUID().toString(),route="/search-instances/"+first;
                assertThat(send(client,route,"DELETE","{\"confirmed\":false}",key).statusCode()).isEqualTo(400);
                HttpResponse<String> deleted=send(client,route,"DELETE","{\"confirmed\":true}",key);
                assertThat(deleted.statusCode()).isEqualTo(202);
                JsonNode task=JSON.readTree(deleted.body()).path("task");
                assertThat(task.path("kind").asText()).isEqualTo("cleanup");
                assertThat(task.path("state").asText()).isEqualTo("succeeded");
                assertThat(task.path("startedAt").isNull()).isFalse();assertThat(task.path("finishedAt").isNull()).isFalse();
                assertThat(task.path("progressTotal").asInt()).isEqualTo(1);
                assertThat(JSON.readTree(send(client,route,"DELETE","{\"confirmed\":true}",key).body()).path("task")).isEqualTo(task);
                assertThat(read(client,"/tasks/"+task.path("id").asText())).isEqualTo(task);
                assertThat(send(client,route,"GET",null,null).statusCode()).isEqualTo(404);
                assertThat(send(client,route,"DELETE","{\"confirmed\":true}",UUID.randomUUID().toString()).statusCode()).isEqualTo(404);
                assertThat(read(client,"/search-instances").path("items").valueStream().noneMatch(row -> row.path("id").asText().equals(first))).isTrue();
                assertThat(read(client,"/knowledge-bases/"+kb).path("searchableFileCount").asInt()).isEqualTo(1);
            }
        } finally { vector.stop(0); }
    }

    @Test
    @DisplayName("切换读取不取消其他实例，取消保留已核范围，删除使原请求失效且迟到不复活")
    void searchInstanceCancellationDeletionAndLateResultsStayIsolated() throws Exception {
        var block=new java.util.concurrent.atomic.AtomicBoolean();
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        HttpServer vector=indexedVector(new java.util.concurrent.CopyOnWriteArrayList<>(),() -> {
            if (block.get()) {
                entered.countDown();
                try { if (!release.await(15,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("受控实例删除等待超时。"); }
                catch (InterruptedException ignored) { }
            }
        });
        Path directory=Files.createTempDirectory(Path.of("target"),"instance-delete-http-").toAbsolutePath();
        try (ConfigurableApplicationContext app=start(directory)) {
            Client client=client(app);String processing=processing(client,vector.getAddress().getPort(),0,"{}","[]");
            String kb=createIndexedBase(client,processing,"实例删除保护库","实例删除不删除资料。");
            assertThat(send(client,"/knowledge-bases/"+kb+"/publish","POST",null,null).statusCode()).isEqualTo(200);
            String first=JSON.readTree(send(client,"/search-instances","POST",null,null).body()).path("id").asText();
            String second=JSON.readTree(send(client,"/search-instances","POST",null,null).body()).path("id").asText();
            String body="{\"query\":\"固定实例查询\",\"knowledgeBaseIds\":[\""+kb+"\",\""+UUID.randomUUID()+"\"]}";
            block.set(true);
            JsonNode running=JSON.readTree(send(client,"/search-instances/"+first+"/requests","POST",body,UUID.randomUUID().toString()).body());
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            JsonNode other=JSON.readTree(send(client,"/search-instances/"+second+"/requests","POST",body,UUID.randomUUID().toString()).body());
            assertThat(read(client,"/search-instances/"+second).path("resultState").asText()).isEqualTo("running");
            assertThat(read(client,"/search-instances/"+first).path("resultState").asText()).isEqualTo("running");
            String cancel="/search-requests/"+running.path("id").asText()+"/cancel";
            JsonNode cancelled=JSON.readTree(send(client,cancel,"POST",null,null).body());
            assertThat(cancelled.at("/task/state").asText()).isEqualTo("cancelled");
            assertThat(cancelled.at("/scope/omitted/0/reason").asText()).isEqualTo("deleted");
            assertThat(cancelled.at("/scope/usedKnowledgeBaseIds").isEmpty()).isTrue();
            assertThat(JSON.readTree(send(client,cancel,"POST",null,null).body())).isEqualTo(cancelled);
            assertThat(read(client,"/search-instances/"+first).path("resultState").asText()).isEqualTo("cancelled");
            String delete="/search-instances/"+second;
            try (var database=java.sql.DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("application.sqlite"));var statement=database.createStatement()) {
                statement.execute("CREATE TRIGGER reject_instance_delete BEFORE DELETE ON search_instances BEGIN SELECT RAISE(ABORT,'固定实例删除故障'); END");
                try {
                    assertThat(send(client,delete,"DELETE","{\"confirmed\":true}",UUID.randomUUID().toString()).statusCode()).isEqualTo(500);
                    assertThat(read(client,"/search-requests/"+other.path("id").asText()).at("/task/state").asText()).isIn("queued","running");
                } finally { statement.execute("DROP TRIGGER reject_instance_delete"); }
            }
            assertThat(send(client,delete,"DELETE","{\"confirmed\":true}",UUID.randomUUID().toString()).statusCode()).isEqualTo(202);
            assertThat(read(client,"/tasks/"+other.at("/task/id").asText()).path("state").asText()).isEqualTo("cancelled");
            String cancelledPeer=JSON.readTree(send(client,"/search-instances","POST",null,null).body()).path("id").asText();
            JsonNode peer=JSON.readTree(send(client,"/search-instances/"+cancelledPeer+"/requests","POST",body,UUID.randomUUID().toString()).body());
            assertThat(send(client,"/search-requests/"+peer.path("id").asText()+"/cancel","POST",null,null).statusCode()).isEqualTo(200);
            block.set(false);release.countDown();
            String gate=JSON.readTree(send(client,"/search-instances","POST",null,null).body()).path("id").asText();
            JsonNode completed=JSON.readTree(send(client,"/search-instances/"+gate+"/requests","POST",body,UUID.randomUUID().toString()).body());
            assertThat(await(client,completed.at("/task/id").asText()).path("state").asText()).isEqualTo("succeeded");
            assertThat(send(client,delete,"GET",null,null).statusCode()).isEqualTo(404);
            assertThat(send(client,"/search-requests/"+other.path("id").asText(),"GET",null,null).statusCode()).isEqualTo(410);
            assertThat(read(client,"/search-requests/"+running.path("id").asText()).path("result").isNull()).isTrue();
            assertThat(read(client,"/search-instances/"+first).path("resultState").asText()).isEqualTo("cancelled");
            JsonNode terminal=read(client,"/search-requests/"+completed.path("id").asText());
            assertThat(JSON.readTree(send(client,"/search-requests/"+completed.path("id").asText()+"/cancel","POST",null,null).body())).isEqualTo(terminal);
            JsonNode management=search(client,kb);assertThat(await(client,management.at("/task/id").asText()).path("state").asText()).isEqualTo("succeeded");
            assertThat(JSON.readTree(send(client,"/search-requests/"+management.path("id").asText()+"/cancel","POST",null,null).body()).path("instanceId").isNull()).isTrue();
            for (String[] operation:java.util.List.of(new String[]{"/search-instances","GET",null},new String[]{"/search-instances/"+first,"GET",null},new String[]{"/search-instances/"+first+"/name","PATCH","{\"name\":\"合法名称\"}"},new String[]{"/search-instances/"+first+"/scope","PUT","{\"knowledgeBaseIds\":[]}"},new String[]{"/search-instances/"+first,"DELETE","{\"confirmed\":true}"},new String[]{cancel,"POST",null})) {
                assertThat(send(new Client(client.base(),null,null),operation[0],operation[1],operation[2],UUID.randomUUID().toString()).statusCode()).isEqualTo(401);
                if (!operation[1].equals("GET")) assertThat(send(new Client(client.base(),client.cookie(),null),operation[0],operation[1],operation[2],UUID.randomUUID().toString()).statusCode()).isEqualTo(403);
                assertThat(send(client,operation[0]+"?unknown=1",operation[1],operation[2],UUID.randomUUID().toString()).statusCode()).isEqualTo(400);
            }
        } finally { block.set(false);release.countDown();vector.stop(0); }
    }

    @Test
    @DisplayName("最小实例多库独立阈值与融合、同配置单次嵌入、范围缺失及全不可用分别表达")
    void multiSearchScopesFusionAndCostsAreExplicit() throws Exception {
        var inputs=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var emptyHits=new java.util.concurrent.atomic.AtomicBoolean();
        HttpServer vector=indexedVector(inputs,() -> {},() -> {},"4",hits -> { if (emptyHits.get()) hits.removeAll(); });
        Path directory=Files.createTempDirectory(Path.of("target"),"multi-search-http-").toAbsolutePath();
        try (ConfigurableApplicationContext app=start(directory)) {
            Client client=client(app);
            String processing=processing(client,vector.getAddress().getPort(),0,"{}","[]");
            String first=createIndexedBase(client,processing,"普通检索第一库","共同原文资料。");
            JsonNode source=read(client,"/knowledge-bases/"+first+"/sources").path("items").get(0);
            JsonNode creation=JSON.readTree(send(client,"/knowledge-bases","POST","{\"name\":\"普通检索第二库\",\"processing\":"+processing+",\"datasetIds\":[\""+source.path("resourceId").asText()+"\"]}",UUID.randomUUID().toString()).body());
            String second=await(client,creation.at("/task/id").asText()).path("resultResourceId").asText();
            for (JsonNode file:read(client,"/knowledge-bases/"+second+"/files").path("items")) assertThat(await(client,file.at("/task/id").asText()).path("state").asText()).isEqualTo("succeeded");
            for (String kb:java.util.List.of(first,second)) assertThat(send(client,"/knowledge-bases/"+kb+"/publish","POST",null,null).statusCode()).isEqualTo(200);
            HttpResponse<String> created=send(client,"/search-instances","POST",null,null);
            assertThat(created.statusCode()).isEqualTo(201);
            JsonNode instance=JSON.readTree(created.body());
            assertThat(instance.path("name").asText()).isEqualTo("新检索");
            assertThat(instance.path("resultState").asText()).isEqualTo("never_submitted");
            assertThat(instance.path("currentRequestId").isNull()).isTrue();
            String endpoint="/search-instances/"+instance.path("id").asText()+"/requests";
            HttpServer otherModel=indexedVector(new java.util.concurrent.CopyOnWriteArrayList<>(),() -> {});
            String dependent;
            try {
                String alternative=processing(client,otherModel.getAddress().getPort(),0,"{}","[]");
                configure(client,vector.getAddress().getPort());
                dependent=createIndexedBase(client,alternative,"独立模型故障库","独立模型正文。");
                assertThat(send(client,"/knowledge-bases/"+dependent+"/publish","POST",null,null).statusCode()).isEqualTo(200);
            } finally { otherModel.stop(0); }
            String separate="{\"query\":\"独立模型故障\",\"knowledgeBaseIds\":[\""+first+"\",\""+dependent+"\"]}";
            JsonNode separateRequest=JSON.readTree(send(client,endpoint,"POST",separate,UUID.randomUUID().toString()).body());
            assertThat(await(client,separateRequest.at("/task/id").asText()).path("state").asText()).isEqualTo("succeeded");
            separateRequest=read(client,"/search-requests/"+separateRequest.path("id").asText());
            assertThat(separateRequest.at("/scope/usedKnowledgeBaseIds/0").asText()).isEqualTo(first);
            assertThat(separateRequest.at("/scope/omitted/0/knowledgeBaseId").asText()).isEqualTo(dependent);
            assertThat(separateRequest.at("/scope/omitted/0/reason").asText()).isEqualTo("unavailable");
            String body="{\"query\":\"共同原文\",\"knowledgeBaseIds\":[\""+first+"\",\""+second+"\"]}",key=UUID.randomUUID().toString();
            int before=inputs.size();
            JsonNode accepted=JSON.readTree(send(client,endpoint,"POST",body,key).body());
            assertThat(accepted.path("instanceId")).isEqualTo(instance.path("id"));
            assertThat(accepted.at("/task/knowledgeBaseId").isNull()).isTrue();
            assertThat(await(client,accepted.at("/task/id").asText()).path("state").asText()).isEqualTo("succeeded");
            JsonNode request=read(client,"/search-requests/"+accepted.path("id").asText());
            assertThat(request.at("/result/outcome").asText()).isEqualTo("matches");
            assertThat(request.at("/result/evidence").size()).isEqualTo(1);
            assertThat(request.at("/scope/usedKnowledgeBaseIds").size()).isEqualTo(2);
            assertThat(request.at("/scope/incomplete").asBoolean()).isFalse();
            assertThat(request.at("/result/preprocessingState").asText()).isEqualTo("not_used");
            assertThat(request.at("/result/additionalQueries").isEmpty()).isTrue();
            JsonNode evidence=request.at("/result/evidence/0");
            JsonNode effective=read(client,"/knowledge-bases/"+evidence.path("knowledgeBaseId").asText()+"/files").path("items").get(0);
            assertThat(evidence.path("indexVersionId")).isEqualTo(effective.path("effectiveIndexVersionId"));
            assertThat(evidence.path("contentVersionId")).isEqualTo(effective.path("effectiveContentVersionId"));
            assertThat(read(client,"/search-requests/"+request.path("id").asText()+"/evidence/"+evidence.path("id").asText())).isEqualTo(evidence);
            assertThat(JSON.readTree(send(client,endpoint,"POST",body,key).body()).path("id")).isEqualTo(request.path("id"));
            assertThat(inputs.size()).isEqualTo(before+1);
            assertThat(send(client,endpoint,"POST",body.replace("共同原文","另一查询"),key).statusCode()).isEqualTo(409);
            assertThat(send(client,"/knowledge-bases/"+second+"/offline","POST",null,null).statusCode()).isEqualTo(200);
            String missing=UUID.randomUUID().toString();
            String partial="{\"query\":\"共同原文\",\"knowledgeBaseIds\":[\""+first+"\",\""+second+"\",\""+missing+"\"]}";
            JsonNode partialRequest=JSON.readTree(send(client,endpoint,"POST",partial,UUID.randomUUID().toString()).body());
            assertThat(await(client,partialRequest.at("/task/id").asText()).path("state").asText()).isEqualTo("succeeded");
            partialRequest=read(client,"/search-requests/"+partialRequest.path("id").asText());
            assertThat(partialRequest.at("/scope/incomplete").asBoolean()).isTrue();
            assertThat(partialRequest.at("/scope/usedKnowledgeBaseIds").size()).isEqualTo(1);
            assertThat(partialRequest.at("/scope/omitted").valueStream().map(row -> row.path("reason").asText()).toList()).containsExactly("not_published","deleted");
            assertThat(partialRequest.at("/result/warnings").isEmpty()).isFalse();
            emptyHits.set(true);
            JsonNode noMatches=JSON.readTree(send(client,endpoint,"POST",body,UUID.randomUUID().toString()).body());
            assertThat(await(client,noMatches.at("/task/id").asText()).path("state").asText()).isEqualTo("succeeded");
            assertThat(read(client,"/search-requests/"+noMatches.path("id").asText()).at("/result/outcome").asText()).isEqualTo("no_matches");
            vector.stop(0);
            JsonNode failure=JSON.readTree(send(client,endpoint,"POST",body,UUID.randomUUID().toString()).body());
            assertThat(await(client,failure.at("/task/id").asText()).path("state").asText()).isEqualTo("failed");
            JsonNode failed=read(client,"/search-requests/"+failure.path("id").asText());
            assertThat(failed.path("result").isNull()).isTrue();
            assertThat(failed.at("/scope/usedKnowledgeBaseIds").isEmpty()).isTrue();
            assertThat(failed.at("/scope/omitted").valueStream().map(row -> row.path("reason").asText()).toList()).contains("unavailable","not_published");
            assertThat(send(client,"/search-instances/"+UUID.randomUUID()+"/requests","POST",body,UUID.randomUUID().toString()).statusCode()).isEqualTo(404);
            assertThat(send(client,endpoint,"POST",body.replace(second,first),UUID.randomUUID().toString()).statusCode()).isEqualTo(400);
            assertThat(send(client,"/search-instances","POST","{}",null).statusCode()).isEqualTo(400);
            for (String route:java.util.List.of("/search-instances",endpoint)) {
                String input=route.equals(endpoint)?body:null;
                assertThat(send(new Client(client.base(),null,null),route,"POST",input,key).statusCode()).isEqualTo(401);
                assertThat(send(new Client(client.base(),client.cookie(),null),route,"POST",input,key).statusCode()).isEqualTo(403);
                assertThat(send(client,route+"?unknown=1","POST",input,key).statusCode()).isEqualTo(400);
            }
        } finally { vector.stop(0); }
    }

    @Test
    @DisplayName("同实例新提交原子取消旧请求，排队发布资格重新检查且满队列不取消旧请求")
    void multiSearchLatestRequestAndQueueStayAtomic() throws Exception {
        var block=new java.util.concurrent.atomic.AtomicBoolean();
        var entered=new java.util.concurrent.atomic.AtomicReference<>(new java.util.concurrent.CountDownLatch(1));
        var release=new java.util.concurrent.atomic.AtomicReference<>(new java.util.concurrent.CountDownLatch(1));
        HttpServer vector=indexedVector(new java.util.concurrent.CopyOnWriteArrayList<>(),() -> {
            if (block.get()) {
                entered.get().countDown();
                boolean done=false;
                while (!done) try { done=release.get().await(15,java.util.concurrent.TimeUnit.SECONDS); if (!done) throw new AssertionError("受控多库等待超时。"); }
                catch (InterruptedException ignored) { }
            }
        });
        Path directory=Files.createTempDirectory(Path.of("target"),"multi-latest-http-").toAbsolutePath();
        try (ConfigurableApplicationContext app=start(directory)) {
            Client client=client(app);String processing=processing(client,vector.getAddress().getPort(),0,"{}","[]");
            String kb=createIndexedBase(client,processing,"普通检索取消库","迟到正文。");
            assertThat(send(client,"/knowledge-bases/"+kb+"/publish","POST",null,null).statusCode()).isEqualTo(200);
            String first=JSON.readTree(send(client,"/search-instances","POST",null,null).body()).path("id").asText();
            String endpoint="/search-instances/"+first+"/requests",body="{\"query\":\"原查询\",\"knowledgeBaseIds\":[\""+kb+"\"]}";
            block.set(true);
            JsonNode old=JSON.readTree(send(client,endpoint,"POST",body,UUID.randomUUID().toString()).body());
            assertThat(entered.get().await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            JsonNode latest=null;
            for (int n=0;n<16;n++) {
                String instance=JSON.readTree(send(client,"/search-instances","POST",null,null).body()).path("id").asText();
                HttpResponse<String> queued=send(client,"/search-instances/"+instance+"/requests","POST",body,UUID.randomUUID().toString());
                assertThat(queued.statusCode()).isEqualTo(202); latest=JSON.readTree(queued.body());
            }
            String overflowKey=UUID.randomUUID().toString();
            assertThat(send(client,endpoint,"POST",body,overflowKey).statusCode()).isEqualTo(429);
            assertThat(read(client,"/search-requests/"+old.path("id").asText()).at("/task/state").asText()).isEqualTo("running");
            assertThat(send(client,"/knowledge-bases/"+kb+"/offline","POST",null,null).statusCode()).isEqualTo(200);
            block.set(false);release.get().countDown();
            assertThat(await(client,latest.at("/task/id").asText()).path("state").asText()).isEqualTo("failed");
            JsonNode failed=read(client,"/search-requests/"+latest.path("id").asText());
            assertThat(failed.at("/scope/omitted/0/reason").asText()).isEqualTo("not_published");
            assertThat(failed.path("result").isNull()).isTrue();
            assertThat(read(client,"/search-requests/"+old.path("id").asText()).at("/task/state").asText()).isEqualTo("succeeded");
            assertThat(send(client,"/knowledge-bases/"+kb+"/publish","POST",null,null).statusCode()).isEqualTo(200);
            entered.set(new java.util.concurrent.CountDownLatch(1));release.set(new java.util.concurrent.CountDownLatch(1));block.set(true);
            JsonNode replaced=JSON.readTree(send(client,endpoint,"POST",body,UUID.randomUUID().toString()).body());
            assertThat(entered.get().await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(send(client,"/knowledge-bases/"+kb+"/offline","POST",null,null).statusCode()).isEqualTo(200);
            JsonNode replacement=JSON.readTree(send(client,endpoint,"POST",body.replace("原查询","新查询"),UUID.randomUUID().toString()).body());
            assertThat(read(client,"/search-requests/"+replaced.path("id").asText()).at("/task/state").asText()).isEqualTo("cancelled");
            block.set(false);release.get().countDown();
            assertThat(await(client,replacement.at("/task/id").asText()).path("state").asText()).isEqualTo("failed");
            assertThat(read(client,"/search-requests/"+replaced.path("id").asText()).path("result").isNull()).isTrue();
        } finally { block.set(false);release.get().countDown();vector.stop(0); }
    }

    @Test
    @DisplayName("发布门槛、主动与自动下线、恢复索引及固定输入幂等复查相互独立")
    void publicationAndChecksKeepIndependentStates() throws Exception {
        var inputs = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var blocked = new java.util.concurrent.atomic.AtomicBoolean();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        HttpServer vector = indexedVector(inputs, () -> {
            if (blocked.get()) {
                entered.countDown();
                try { if (!release.await(15,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("受控复查等待超时。"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            }
        });
        Path directory = Files.createTempDirectory(Path.of("target"),"publication-http-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = processing(client,vector.getAddress().getPort(),0,"{}","[]");
            JsonNode empty = JSON.readTree(send(client,"/knowledge-bases","POST","{\"name\":\"发布门槛空库\",\"processing\":"+processing+"}",UUID.randomUUID().toString()).body());
            String emptyId = await(client,empty.at("/task/id").asText()).path("resultResourceId").asText();
            assertThat(send(client,"/knowledge-bases/"+emptyId+"/publish","POST",null,null).statusCode()).isEqualTo(422);
            assertThat(read(client,"/knowledge-bases/"+emptyId).path("publicationState").asText()).isEqualTo("unpublished");
            assertThat(send(client,"/knowledge-bases/"+emptyId+"/offline","POST",null,null).statusCode()).isEqualTo(409);
            String kb = createIndexedBase(client,processing,"发布状态库","用户正文不能发送给连接检查。");
            String path = "/knowledge-bases/"+kb;
            JsonNode file = read(client,path+"/files").path("items").get(0);
            String fileId = file.path("fileId").asText();
            assertThat(read(client,path).path("publicationState").asText()).isEqualTo("unpublished");
            JsonNode published = JSON.readTree(send(client,path+"/publish","POST",null,null).body());
            assertThat(published.path("publicationState").asText()).isEqualTo("published");
            assertThat(published.path("publishedAt").asText()).isNotBlank();
            assertThat(JSON.readTree(send(client,path+"/publish","POST",null,null).body()).path("publishedAt")).isEqualTo(published.path("publishedAt"));
            String index = file.path("effectiveIndexVersionId").asText();
            JsonNode offline = JSON.readTree(send(client,path+"/offline","POST",null,null).body());
            assertThat(offline.path("publicationState").asText()).isEqualTo("offline");
            assertThat(offline.path("offlineReason").asText()).isEqualTo("manual");
            assertThat(JSON.readTree(send(client,path+"/offline","POST",null,null).body()).path("offlineAt")).isEqualTo(offline.path("offlineAt"));
            assertThat(read(client,path+"/files").at("/items/0/effectiveIndexVersionId").asText()).isEqualTo(index);
            assertThat(send(client,path+"/publish","POST",null,null).statusCode()).isEqualTo(200);
            String exclusion = path+"/exclusions/"+fileId;
            assertThat(send(client,exclusion,"PUT",null,null).statusCode()).isEqualTo(200);
            JsonNode automatic = read(client,path);
            assertThat(automatic.path("publicationState").asText()).isEqualTo("offline");
            assertThat(automatic.path("offlineReason").asText()).isEqualTo("no_searchable_files");
            assertThat(send(client,path+"/publish","POST",null,null).statusCode()).isEqualTo(422);
            assertThat(send(client,exclusion,"DELETE",null,null).statusCode()).isEqualTo(204);
            for (JsonNode current : read(client,path+"/files").path("items")) assertThat(await(client,current.at("/task/id").asText()).path("state").asText()).isEqualTo("succeeded");
            assertThat(read(client,path).path("publicationState").asText()).isEqualTo("offline");
            assertThat(send(client,path+"/publish","POST",null,null).statusCode()).isEqualTo(200);
            String check = path+"/check", key = UUID.randomUUID().toString(), alias = UUID.randomUUID().toString();
            assertThat(send(client,check,"POST","{\"allowChargedCall\":false}",key).statusCode()).isEqualTo(400);
            assertThat(send(client,check,"POST","{\"allowChargedCall\":true}",null).statusCode()).isEqualTo(400);
            int before = inputs.size(); blocked.set(true);
            JsonNode accepted = JSON.readTree(send(client,check,"POST","{\"allowChargedCall\":true}",key).body());
            String task = accepted.at("/task/id").asText();
            assertThat(accepted.at("/task/knowledgeBaseId").asText()).isEqualTo(kb);
            assertThat(accepted.at("/task/kind").asText()).isEqualTo("model_test");
            assertThat(accepted.at("/task/resultResourceId").isNull()).isTrue();
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(read(client,path+"/tasks").path("items").valueStream().anyMatch(row -> row.path("id").asText().equals(task) && row.path("knowledgeBaseId").asText().equals(kb))).isTrue();
            assertThat(JSON.readTree(send(client,check,"POST","{\"allowChargedCall\":true}",alias).body()).at("/task/id").asText()).isEqualTo(task);
            blocked.set(false); release.countDown();
            assertThat(await(client,task).path("state").asText()).isEqualTo("succeeded");
            JsonNode result = read(client,"/tasks/"+task+"/connection-test");
            assertThat(result.path("available").asBoolean()).isTrue();
            assertThat(result.path("mayHaveIncurredCost").asBoolean()).isTrue();
            for (String same : java.util.List.of(key,alias)) assertThat(JSON.readTree(send(client,check,"POST","{\"allowChargedCall\":true}",same).body()).at("/task/id").asText()).isEqualTo(task);
            assertThat(inputs.size()).isEqualTo(before+1);
            assertThat(inputs.getLast()).isEqualTo("知识库连接检查。");
            assertThat(read(client,path).path("availability").asText()).isEqualTo("available");
            vector.stop(0);
            JsonNode unavailable = JSON.readTree(send(client,check,"POST","{\"allowChargedCall\":true}",UUID.randomUUID().toString()).body());
            String failedProbe = unavailable.at("/task/id").asText();
            assertThat(await(client,failedProbe).path("state").asText()).isEqualTo("succeeded");
            assertThat(read(client,"/tasks/"+failedProbe+"/connection-test").path("available").asBoolean()).isFalse();
            assertThat(read(client,path).path("availability").asText()).isEqualTo("unavailable");
            assertThat(read(client,path).path("publicationState").asText()).isEqualTo("published");
            for (String suffix : java.util.List.of("/publish","/offline","/check")) {
                String body = suffix.equals("/check") ? "{\"allowChargedCall\":true}" : null;
                assertThat(send(new Client(client.base(),null,null),path+suffix,"POST",body,UUID.randomUUID().toString()).statusCode()).isEqualTo(401);
                assertThat(send(new Client(client.base(),client.cookie(),null),path+suffix,"POST",body,UUID.randomUUID().toString()).statusCode()).isEqualTo(403);
                assertThat(send(client,path+suffix+"?unknown=1","POST",body,UUID.randomUUID().toString()).statusCode()).isEqualTo(400);
            }
        } finally { release.countDown(); vector.stop(0); }
    }

    @Test
    @DisplayName("旧副本不可用标记提交失败不删字节，物理删除失败保留可重试意图")
    void obsoleteOriginalCleanupKeepsCommittedAvailabilityTruth() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("target"),"original-cleanup-http-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            app.getBean(cn.knowpool.knowledgebases.Indexes.class).close();
            Client client = client(app);
            String dataset = readCreatedDataset(client,"副本清理故障资料");
            JsonNode first = uploadText(client,dataset,"清理前必须保全的原文。").at("/items/0/file");
            String file = first.path("id").asText(), old = first.at("/latestVersion/id").asText();
            uploadText(client,dataset,"当前最新正文。");
            var files = app.getBean(cn.knowpool.datasets.FileService.class);
            try (var database = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("application.sqlite")); var statement = database.createStatement()) {
                statement.execute("CREATE TRIGGER reject_original_cleanup BEFORE UPDATE OF available ON file_versions BEGIN SELECT RAISE(ABORT,'固定清理事务故障'); END");
                try {
                    org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,() -> files.cleanObsoleteVersions(java.util.Set.of()));
                    assertThat(send(client,"/files/" + file + "/versions/" + old + "/download","GET",null,null).body()).isEqualTo("清理前必须保全的原文。");
                } finally { statement.execute("DROP TRIGGER reject_original_cleanup"); }
                Path target = directory.resolve("files/versions").resolve(old);
                Files.delete(target); Files.createDirectory(target); Files.writeString(target.resolve("blocked"),"固定不可删除子项");
                files.cleanObsoleteVersions(java.util.Set.of());
                assertThat(send(client,"/files/" + file + "/versions/" + old + "/download","GET",null,null).statusCode()).isEqualTo(410);
                try (var rows = statement.executeQuery("SELECT attempts FROM file_version_cleanup WHERE version_id='" + old + "'")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(1);
                }
                Files.delete(target.resolve("blocked")); Files.delete(target);
                files.cleanObsoleteVersions(java.util.Set.of());
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM file_version_cleanup")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
                }
                assertThat(send(client,"/files/" + file + "/versions/" + old + "/download","GET",null,null).statusCode()).isEqualTo(410);
            }
        }
    }

    @Test
    @DisplayName("配置整库切换保留发布决定及旧服务，历史版本归属与凭据端点身份独立")
    void configurationRebuildPublicationAndHistoryStayIndependent() throws Exception {
        var blocked=new java.util.concurrent.atomic.AtomicBoolean();var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var inputs=new java.util.concurrent.CopyOnWriteArrayList<String>();var otherInputs=new java.util.concurrent.CopyOnWriteArrayList<String>();
        HttpServer vector=indexedVector(inputs,() -> { if (blocked.get()) { entered.countDown();try { if (!release.await(15,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("受控重建等待超时。"); } catch (InterruptedException failure) { Thread.currentThread().interrupt();throw new AssertionError(failure); } } });
        HttpServer other=indexedVector(otherInputs);
        Path directory=Files.createTempDirectory(Path.of("target"),"config-rebuild-http-").toAbsolutePath();
        try (ConfigurableApplicationContext app=start(directory)) {
            Client client=client(app);String processing=processing(client,vector.getAddress().getPort(),0,"{}","[]");String kb=createIndexedBase(client,processing,"配置重建发布库","配置切换前后可核对的原文。");
            assertThat(send(client,"/knowledge-bases/"+kb+"/publish","POST",null,null).statusCode()).isEqualTo(200);
            JsonNode original=read(client,"/knowledge-bases/"+kb),oldConfig=read(client,"/knowledge-bases/"+kb+"/processing-config");String oldVersion=oldConfig.path("id").asText();
            String history="/knowledge-bases/"+kb+"/processing-config/versions/";assertThat(read(client,history+oldVersion)).isEqualTo(oldConfig);
            int count=inputs.size();String model=oldConfig.at("/input/embedding/modelConfigVersionId").asText();
            assertThat(send(client,"/settings/models/embedding/versions/"+model+"/credential","PUT","{\"action\":\"replace\",\"value\":\"replacement-fixed-key\"}",null).statusCode()).isEqualTo(200);
            assertThat(read(client,"/settings/models/embedding").path("versionId").asText()).isEqualTo(model);assertThat(read(client,"/knowledge-bases/"+kb).path("targetConfigVersionId")).isEqualTo(original.path("targetConfigVersionId"));assertThat(inputs.size()).isEqualTo(count);
            var update=(tools.jackson.databind.node.ObjectNode)JSON.readTree(processing);((tools.jackson.databind.node.ObjectNode)update.path("chunking")).put("strategy","fixed");blocked.set(true);
            String endpoint="/knowledge-bases/"+kb+"/processing-config",key=UUID.randomUUID().toString();JsonNode accepted=JSON.readTree(send(client,endpoint,"PUT",update.toString(),key).body());String parent=accepted.at("/task/id").asText(),target=accepted.at("/task/targetConfigVersionId").asText();
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();JsonNode during=read(client,"/knowledge-bases/"+kb);assertThat(during.path("publicationState").asText()).isEqualTo("published");assertThat(during.path("effectiveConfigVersionId").asText()).isEqualTo(oldVersion);assertThat(during.path("targetConfigVersionId").asText()).isEqualTo(target);assertThat(read(client,"/knowledge-bases/"+kb+"/files").at("/items/0/usingPreviousVersion").asBoolean()).isTrue();
            assertThat(read(client,history+target).path("input")).isEqualTo(update);
            assertThat(send(client,"/knowledge-bases/"+kb+"/offline","POST",null,null).statusCode()).isEqualTo(200);blocked.set(false);release.countDown();assertThat(await(client,parent).path("state").asText()).isEqualTo("succeeded");
            JsonNode switched=read(client,"/knowledge-bases/"+kb);assertThat(switched.path("publicationState").asText()).isEqualTo("offline");assertThat(switched.path("effectiveConfigVersionId").asText()).isEqualTo(target);assertThat(switched.path("effectiveIndexVersionId").asText()).isEqualTo(parent);assertThat(read(client,history+oldVersion)).isEqualTo(oldConfig);
            assertThat(JSON.readTree(send(client,endpoint,"PUT",update.toString(),key).body()).at("/task/id").asText()).isEqualTo(parent);
            assertThat(read(client,"/knowledge-bases/"+kb+"/index-switches").path("items")).anySatisfy(item -> { assertThat(item.path("toConfigVersionId").asText()).isEqualTo(target);assertThat(item.path("fromConfigVersionId").asText()).isEqualTo(oldVersion); });
            assertThat(send(client,"/knowledge-bases/"+kb+"/publish","POST",null,null).statusCode()).isEqualTo(200);
            ((tools.jackson.databind.node.ObjectNode)update.path("chunking")).put("strategy","page");String failed=JSON.readTree(send(client,endpoint,"PUT",update.toString(),UUID.randomUUID().toString()).body()).at("/task/id").asText();assertThat(await(client,failed).path("state").asText()).isEqualTo("failed");assertThat(read(client,"/knowledge-bases/"+kb).path("publicationState").asText()).isEqualTo("published");assertThat(read(client,"/knowledge-bases/"+kb).path("effectiveConfigVersionId").asText()).isEqualTo(target);assertThat(read(client,"/knowledge-bases/"+kb+"/files").at("/items/0/updateState").asText()).isEqualTo("failed");
            JsonNode request=search(client,kb);assertThat(await(client,request.at("/task/id").asText()).path("state").asText()).isEqualTo("succeeded");assertThat(read(client,"/search-requests/"+request.path("id").asText()).at("/result/evidence/0/configVersionId").asText()).isEqualTo(target);
            String nextEndpoint="http://127.0.0.1:"+other.getAddress().getPort()+"/v1";
            assertThat(send(client,"/settings/models/embedding","PUT",JSON.createObjectNode().put("endpoint",nextEndpoint).put("model","text-embedding-v4").putNull("contextWindowTokens").putNull("maxOutputTokens").set("credential",JSON.createObjectNode().put("action","replace").put("value","fixed-other-key")).toString(),null).statusCode()).isEqualTo(200);
            String nextModel=read(client,"/settings/models/embedding").path("versionId").asText();assertThat(nextModel).isNotEqualTo(model);assertThat(read(client,"/knowledge-bases/"+kb).path("effectiveConfigVersionId").asText()).isEqualTo(target);
            ((tools.jackson.databind.node.ObjectNode)update.path("chunking")).put("strategy","fixed");var embedding=(tools.jackson.databind.node.ObjectNode)update.path("embedding");embedding.put("modelConfigVersionId",nextModel);((tools.jackson.databind.node.ObjectNode)embedding.path("modelSnapshot")).put("endpoint",nextEndpoint);
            JsonNode changed=JSON.readTree(send(client,endpoint,"PUT",update.toString(),UUID.randomUUID().toString()).body());assertThat(await(client,changed.at("/task/id").asText()).path("state").asText()).isEqualTo("succeeded");assertThat(otherInputs).isNotEmpty();assertThat(read(client,"/knowledge-bases/"+kb).path("publicationState").asText()).isEqualTo("published");
            JsonNode emptyAccepted=JSON.readTree(send(client,"/knowledge-bases","POST","{\"name\":\"空配置重建库\",\"processing\":"+processing+"}",UUID.randomUUID().toString()).body());String empty=await(client,emptyAccepted.at("/task/id").asText()).path("resultResourceId").asText();
            String emptyTask=JSON.readTree(send(client,"/knowledge-bases/"+empty+"/processing-config","PUT",update.toString(),UUID.randomUUID().toString()).body()).at("/task/id").asText();assertThat(await(client,emptyTask).path("state").asText()).isEqualTo("succeeded");JsonNode emptyBase=read(client,"/knowledge-bases/"+empty);assertThat(emptyBase.path("searchableFileCount").asInt()).isZero();assertThat(emptyBase.path("publicationState").asText()).isEqualTo("unpublished");
            assertThat(send(client,"/knowledge-bases/"+empty+"/processing-config/versions/"+oldVersion,"GET",null,null).statusCode()).isEqualTo(404);assertThat(send(client,history+UUID.randomUUID(),"GET",null,null).statusCode()).isEqualTo(404);assertThat(send(client,history+"bad","GET",null,null).statusCode()).isEqualTo(400);assertThat(send(client,history+oldVersion+"?unknown=1","GET",null,null).statusCode()).isEqualTo(400);assertThat(send(new Client(client.base(),null,null),history+oldVersion,"GET",null,null).statusCode()).isEqualTo(401);
        } finally { blocked.set(false);release.countDown();vector.stop(0);other.stop(0); }
    }

    @Test
    @DisplayName("运行中检索及整库重建取消后旧响应不能发布结果或改变生效指针")
    void runningSearchAndRebuildCancellationRejectLateResults() throws Exception {
        var block = new java.util.concurrent.atomic.AtomicBoolean();
        var entered = new java.util.concurrent.atomic.AtomicReference<>(new java.util.concurrent.CountDownLatch(1));
        var release = new java.util.concurrent.atomic.AtomicReference<>(new java.util.concurrent.CountDownLatch(1));
        HttpServer vector = indexedVector(new java.util.concurrent.CopyOnWriteArrayList<>(), () -> {
            if (block.get()) {
                entered.get().countDown();
                try { if (!release.get().await(15,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("受控取消等待超时。"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            }
        });
        Path directory = Files.createTempDirectory(Path.of("target"),"running-cancel-http-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = processing(client,vector.getAddress().getPort(),0,"{}","[]");
            String kb = createIndexedBase(client,processing,"运行取消库","不可被取消结果替换的旧正文。");
            JsonNode base = read(client,"/knowledge-bases/" + kb);
            block.set(true);
            JsonNode request = search(client,kb);
            assertThat(entered.get().await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            String searchTask = request.at("/task/id").asText();
            assertThat(JSON.readTree(send(client,"/tasks/" + searchTask + "/cancel","POST",null,null).body()).path("state").asText()).isEqualTo("cancelled");
            block.set(false); release.get().countDown();
            JsonNode next = search(client,kb);
            assertThat(await(client,next.at("/task/id").asText()).path("state").asText()).isEqualTo("succeeded");
            JsonNode cancelled = read(client,"/search-requests/" + request.path("id").asText());
            assertThat(cancelled.at("/task/state").asText()).isEqualTo("cancelled");
            assertThat(cancelled.path("result").isNull()).isTrue();
            entered.set(new java.util.concurrent.CountDownLatch(1)); release.set(new java.util.concurrent.CountDownLatch(1)); block.set(true);
            var input = (tools.jackson.databind.node.ObjectNode) JSON.readTree(processing);
            ((tools.jackson.databind.node.ObjectNode)input.path("chunking")).put("strategy","fixed");
            JsonNode rebuild = JSON.readTree(send(client,"/knowledge-bases/" + kb + "/processing-config","PUT",input.toString(),UUID.randomUUID().toString()).body());
            assertThat(entered.get().await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            String parent = rebuild.at("/task/id").asText();
            assertThat(JSON.readTree(send(client,"/tasks/" + parent + "/cancel","POST",null,null).body()).path("state").asText()).isEqualTo("cancelled");
            block.set(false); release.get().countDown();
            assertThat(read(client,"/knowledge-bases/" + kb).path("effectiveConfigVersionId")).isEqualTo(base.path("effectiveConfigVersionId"));
            for (JsonNode task : read(client,"/knowledge-bases/" + kb + "/tasks").path("items"))
                if (task.path("targetConfigVersionId").equals(rebuild.at("/task/targetConfigVersionId")))
                    assertThat(task.path("state").asText()).isEqualTo("cancelled");
            assertThat(read(client,"/knowledge-bases/" + kb + "/index-switches").path("items").size()).isEqualTo(1);
            String route = "/tasks/" + parent + "/cancel";
            assertThat(send(new Client(client.base(),null,null),route,"POST",null,null).statusCode()).isEqualTo(401);
            assertThat(send(new Client(client.base(),client.cookie(),null),route,"POST",null,null).statusCode()).isEqualTo(403);
        } finally { release.get().countDown(); vector.stop(0); }
    }

    @Test
    @DisplayName("各库内容独立切换、取消幂等拒绝迟到发布且历史关系及指定下载绑定真实版本")
    void contentSwitchHistoryAndCancellationRemainBoundToVersions() throws Exception {
        var blocked = new java.util.concurrent.atomic.AtomicBoolean();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        HttpServer vector = indexedVector(new java.util.concurrent.CopyOnWriteArrayList<>(), () -> {
            if (blocked.get()) {
                entered.countDown();
                try { if (!release.await(15,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("受控版本更新等待超时。"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            }
        });
        Path directory = Files.createTempDirectory(Path.of("target"),"content-switch-http-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = processing(client,vector.getAddress().getPort(),0,"{}","[]");
            String first = createIndexedBase(client,processing,"版本切换第一库","最初内容😀。");
            JsonNode source = read(client,"/knowledge-bases/" + first + "/sources").path("items").get(0);
            String dataset = source.path("resourceId").asText();
            JsonNode creation = JSON.readTree(send(client,"/knowledge-bases","POST","{\"name\":\"版本切换第二库\",\"processing\":" + processing + ",\"datasetIds\":[\"" + dataset + "\"]}",UUID.randomUUID().toString()).body());
            String second = await(client,creation.at("/task/id").asText()).path("resultResourceId").asText();
            assertThat(await(client,read(client,"/knowledge-bases/" + second + "/files").at("/items/0/task/id").asText()).path("state").asText()).isEqualTo("succeeded");
            JsonNode old = read(client,"/knowledge-bases/" + first + "/files").path("items").get(0);
            String file = old.path("fileId").asText(), oldVersion = old.path("latestContentVersionId").asText();
            JsonNode initialSwitch = read(client,"/knowledge-bases/" + first + "/index-switches").path("items").get(0);
            assertThat(initialSwitch.path("fromContentVersionId").isNull()).isTrue();
            assertThat(initialSwitch.path("fromIndexVersionId").isNull()).isTrue();
            assertThat(initialSwitch.path("fromConfigVersionId").isNull()).isTrue();
            blocked.set(true);
            JsonNode replacement = uploadText(client,dataset,"第二次成功内容。");
            String version = replacement.at("/items/0/file/latestVersion/id").asText();
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            String running = null, cancelledKb = null;
            for (JsonNode id : replacement.at("/items/0/indexTaskIds")) {
                JsonNode task = read(client,"/tasks/" + id.asText());
                if (task.path("state").asText().equals("queued")) {
                    cancelledKb = task.path("knowledgeBaseId").asText();
                    JsonNode cancelled = JSON.readTree(send(client,"/tasks/" + id.asText() + "/cancel","POST",null,null).body());
                    assertThat(cancelled.path("state").asText()).isEqualTo("cancelled");
                    assertThat(JSON.readTree(send(client,"/tasks/" + id.asText() + "/cancel","POST",null,null).body())).isEqualTo(cancelled);
                } else running = id.asText();
            }
            assertThat(running).isNotNull(); assertThat(cancelledKb).isNotNull();
            JsonNode invalid = uploadText(client,dataset,"不支持文件不能替代进行中内容。","source.exe");
            assertThat(invalid.at("/items/0/outcome").asText()).isEqualTo("failed");
            assertThat(read(client,"/tasks/" + running).path("state").asText()).isEqualTo("running");
            HttpResponse<String> oldDownload = send(client,"/files/" + file + "/versions/" + oldVersion + "/download","GET",null,null);
            assertThat(oldDownload.statusCode()).isEqualTo(200); assertThat(oldDownload.body()).isEqualTo("最初内容😀。");
            assertThat(oldDownload.headers().firstValue("Content-Disposition").orElseThrow()).startsWith("attachment;");
            assertThat(oldDownload.headers().firstValue("Cache-Control").orElseThrow()).isEqualTo("no-store");
            assertThat(oldDownload.headers().firstValue("X-Content-Type-Options").orElseThrow()).isEqualTo("nosniff");
            blocked.set(false); release.countDown();
            assertThat(await(client,running).path("state").asText()).isEqualTo("succeeded");
            String successfulKb = cancelledKb.equals(first) ? second : first;
            JsonNode successful = read(client,"/knowledge-bases/" + successfulKb + "/files").path("items").get(0);
            assertThat(successful.path("effectiveContentVersionId").asText()).isEqualTo(version);
            assertThat(read(client,"/knowledge-bases/" + cancelledKb + "/files").at("/items/0/effectiveContentVersionId").asText()).isEqualTo(oldVersion);
            JsonNode switches = read(client,"/knowledge-bases/" + successfulKb + "/index-switches");
            assertThat(switches.path("items")).anySatisfy(item -> {
                assertThat(item.path("fromContentVersionId").asText()).isEqualTo(oldVersion);
                assertThat(item.path("toContentVersionId").asText()).isEqualTo(version);
                assertThat(item.path("toIndexVersionId")).isEqualTo(successful.path("effectiveIndexVersionId"));
                assertThat(item.path("fromConfigVersionId")).isEqualTo(item.path("toConfigVersionId"));
            });
            assertThat(read(client,"/knowledge-bases/" + cancelledKb + "/index-switches").path("items").size()).isEqualTo(1);
            JsonNode finalUpload = uploadText(client,dataset,"第三次全部生效正文。");
            for (JsonNode id : finalUpload.at("/items/0/indexTaskIds"))
                assertThat(await(client,id.asText()).path("state").asText()).isEqualTo("succeeded");
            int cleanedStatus = 200;
            for (int attempt=0; attempt<160 && cleanedStatus==200; attempt++) {
                cleanedStatus = send(client,"/files/" + file + "/versions/" + oldVersion + "/download","GET",null,null).statusCode();
                if (cleanedStatus==200) Thread.sleep(50);
            }
            assertThat(cleanedStatus).isEqualTo(410);
            assertThat(read(client,"/files/" + file + "/versions").path("items")).anySatisfy(item -> {
                assertThat(item.path("id").asText()).isEqualTo(oldVersion);
                assertThat(item.path("originalAvailable").asBoolean()).isFalse();
            });
            assertThat(send(client,"/tasks/" + creation.at("/task/id").asText() + "/cancel","POST",null,null).statusCode()).isEqualTo(409);
            assertThat(send(client,"/tasks/" + UUID.randomUUID() + "/cancel","POST",null,null).statusCode()).isEqualTo(404);
            assertThat(send(client,"/tasks/" + running + "/cancel?unknown=1","POST",null,null).statusCode()).isEqualTo(400);
            assertThat(send(client,"/knowledge-bases/" + first + "/index-switches?limit=0","GET",null,null).statusCode()).isEqualTo(400);
            assertThat(send(client,"/files/" + file + "/versions/" + UUID.randomUUID() + "/download","GET",null,null).statusCode()).isEqualTo(404);
        } finally { release.countDown(); vector.stop(0); }
    }

    @Test
    @DisplayName("来源并集不重复处理、排除优先且撤最后来源立即下线，迟到索引不能复活")
    void associationUnionExclusionsAndLateTasksStayConsistent() throws Exception {
        var inputs = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var block = new java.util.concurrent.atomic.AtomicBoolean();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        HttpServer vector = indexedVector(inputs, () -> {
            if (block.get()) {
                entered.countDown();
                try { if (!release.await(15,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("受控嵌入等待超时。"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            }
        });
        Path directory = Files.createTempDirectory(Path.of("target"),"associations-http-").toAbsolutePath();
        String kb, file;
        try {
            try (ConfigurableApplicationContext app = start(directory)) {
                Client client = client(app);
                String processing = processing(client,vector.getAddress().getPort(),0,"{}","[]");
                kb = createIndexedBase(client,processing,"来源并集库","最初完整原文。");
                JsonNode member = read(client,"/knowledge-bases/" + kb + "/files").path("items").get(0);
                file = member.path("fileId").asText();
                String originalTask = member.at("/task/id").asText();
                JsonNode datasetSource = read(client,"/knowledge-bases/" + kb + "/sources").path("items").get(0);
                String dataset = datasetSource.path("resourceId").asText();
                String sourcePath = "/knowledge-bases/" + kb + "/sources";
                String exclusionPath = "/knowledge-bases/" + kb + "/exclusions/" + file;
                HttpResponse<String> added = send(client,sourcePath,"POST","{\"type\":\"file\",\"resourceId\":\"" + file + "\"}",null);
                assertThat(added.statusCode()).isEqualTo(201);
                String fileSource = JSON.readTree(added.body()).path("id").asText();
                JsonNode both = read(client,"/knowledge-bases/" + kb + "/files").path("items").get(0);
                assertThat(both.path("sources").size()).isEqualTo(2);
                assertThat(both.at("/task/id").asText()).isEqualTo(originalTask);
                assertThat(send(client,sourcePath + "/" + datasetSource.path("id").asText(),"DELETE",null,null).statusCode()).isEqualTo(204);
                assertThat(read(client,"/knowledge-bases/" + kb + "/files").at("/items/0/effectiveIndexVersionId")).isEqualTo(member.path("effectiveIndexVersionId"));
                JsonNode exclusion = JSON.readTree(send(client,exclusionPath,"PUT",null,null).body());
                assertThat(JSON.readTree(send(client,exclusionPath,"PUT",null,null).body())).isEqualTo(exclusion);
                JsonNode excluded = read(client,"/files/" + file + "/knowledge-bases").path("items").get(0);
                assertThat(excluded.path("included").asBoolean()).isFalse();
                assertThat(excluded.path("excluded").asBoolean()).isTrue();
                assertThat(excluded.path("task").isNull()).isTrue();
                assertThat(excluded.path("effectiveIndexVersionId").isNull()).isTrue();
                assertThat(read(client,"/knowledge-bases/" + kb).path("searchableFileCount").asInt()).isZero();
                assertThat(send(client,sourcePath + "/" + fileSource,"DELETE",null,null).statusCode()).isEqualTo(204);
                assertThat(read(client,"/knowledge-bases/" + kb + "/files").at("/items/0/sources").isEmpty()).isTrue();
                assertThat(read(client,"/knowledge-bases/" + kb + "/exclusions").path("items").size()).isEqualTo(1);
                int calls = inputs.size();
                assertThat(send(client,exclusionPath,"DELETE",null,null).statusCode()).isEqualTo(204);
                assertThat(read(client,"/files/" + file + "/knowledge-bases").path("items").isEmpty()).isTrue();
                assertThat(inputs.size()).isEqualTo(calls);
                assertThat(send(client,exclusionPath,"DELETE",null,null).statusCode()).isEqualTo(404);
                assertThat(send(client,sourcePath,"POST","{\"type\":\"dataset\",\"resourceId\":\"" + dataset + "\"}",null).statusCode()).isEqualTo(201);
                JsonNode restored = read(client,"/knowledge-bases/" + kb + "/files").path("items").get(0);
                assertThat(await(client,restored.at("/task/id").asText()).path("state").asText()).isEqualTo("succeeded");
                uploadText(client,dataset,"自动同步新增原文。","second.txt");
                for (JsonNode current : read(client,"/knowledge-bases/" + kb + "/files").path("items"))
                    assertThat(await(client,current.at("/task/id").asText()).path("state").asText()).isEqualTo("succeeded");
                assertThat(read(client,"/knowledge-bases/" + kb).path("includedFileCount").asInt()).isEqualTo(2);
                block.set(true);
                uploadText(client,dataset,"覆盖后不可迟到复活的正文。");
                assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                String lateTask = read(client,"/knowledge-bases/" + kb + "/files").path("items").valueStream().filter(row -> row.path("fileId").asText().equals(file)).findFirst().orElseThrow().at("/task/id").asText();
                assertThat(send(client,exclusionPath,"PUT",null,null).statusCode()).isEqualTo(200);
                block.set(false); release.countDown();
                assertThat(await(client,lateTask).path("state").asText()).isEqualTo("cancelled");
                assertThat(read(client,"/files/" + file + "/knowledge-bases").at("/items/0/included").asBoolean()).isFalse();
                assertThat(send(client,sourcePath + "/" + UUID.randomUUID(),"DELETE",null,null).statusCode()).isEqualTo(404);
                assertThat(send(client,exclusionPath + "?unknown=1","PUT",null,null).statusCode()).isEqualTo(400);
                assertThat(send(client,"/knowledge-bases/" + kb + "/exclusions/" + UUID.randomUUID(),"PUT",null,null).statusCode()).isEqualTo(404);
                assertThat(send(client,"/files/" + UUID.randomUUID() + "/knowledge-bases","GET",null,null).statusCode()).isEqualTo(404);
                for (String[] route : java.util.List.of(
                        new String[]{sourcePath + "/" + UUID.randomUUID(),"DELETE"},
                        new String[]{"/knowledge-bases/" + kb + "/exclusions","GET"},
                        new String[]{exclusionPath,"PUT"},new String[]{exclusionPath,"DELETE"},
                        new String[]{"/files/" + file + "/knowledge-bases","GET"})) {
                    assertThat(send(new Client(client.base(),null,null),route[0],route[1],null,null).statusCode()).isEqualTo(401);
                    if (!route[1].equals("GET")) assertThat(send(new Client(client.base(),client.cookie(),null),route[0],route[1],null,null).statusCode()).isEqualTo(403);
                }
                assertThat(send(client,"/knowledge-bases/" + UUID.randomUUID() + "/exclusions","GET",null,null).statusCode()).isEqualTo(404);
                assertThat(send(client,"/files/" + file + "/knowledge-bases?limit=0","GET",null,null).statusCode()).isEqualTo(400);
                assertThat(send(client,"/knowledge-bases/" + kb + "/exclusions?cursor=invalid","GET",null,null).statusCode()).isEqualTo(400);
                for (JsonNode task : read(client,"/knowledge-bases/" + kb + "/tasks").path("items"))
                    if (task.path("kind").asText().equals("cleanup")) assertThat(await(client,task.path("id").asText()).path("state").asText()).isEqualTo("succeeded");
            }
            try (ConfigurableApplicationContext app = start(directory)) {
                Client client = client(app);
                assertThat(read(client,"/knowledge-bases/" + kb + "/exclusions").at("/items/0/fileId").asText()).isEqualTo(file);
                assertThat(read(client,"/files/" + file + "/knowledge-bases").at("/items/0/excluded").asBoolean()).isTrue();
            }
        } finally { release.countDown(); vector.stop(0); }
    }

    @Test
    @DisplayName("切片预检记录真实降级与码点，配置幂等重建原子生效且失败保留旧版")
    void preflightAndRebuildKeepVersionsAtomic() throws Exception {
        var inputs = new java.util.concurrent.CopyOnWriteArrayList<String>();
        HttpServer vector = indexedVector(inputs, () -> {});
        Path directory = Files.createTempDirectory(Path.of("target"), "chunk-preflight-http-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = processing(client, vector.getAddress().getPort(), 0, "{}", "[]");
            String source = "甲😀".repeat(1400) + "，" + "乙".repeat(2500) + "。末句。";
            String kb = createIndexedBase(client, processing, "切片预检库", source);
            JsonNode original = read(client, "/knowledge-bases/" + kb);
            JsonNode file = read(client, "/knowledge-bases/" + kb + "/files").path("items").get(0);
            String taskId = file.at("/task/id").asText();
            int calls = inputs.size();
            JsonNode preview = read(client, "/tasks/" + taskId + "/chunk-preflight");
            assertThat(preview.path("integrityPassed").asBoolean()).isTrue();
            assertThat(preview.path("emptyText").asBoolean()).isFalse();
            assertThat(preview.path("maximumLength").asInt()).isLessThanOrEqualTo(2000);
            assertThat(preview.path("fallbackCounts").path("hard").asInt()).isPositive();
            StringBuilder reconstructed = new StringBuilder();
            for (JsonNode sample : preview.path("samples")) {
                String text = sample.path("text").asText();
                assertThat(sample.path("codePointCount").asInt()).isEqualTo(text.codePointCount(0,text.length()));
                reconstructed.append(text);
            }
            assertThat(reconstructed.toString()).isEqualTo(source);
            read(client, "/tasks/" + taskId + "/chunk-preflight");
            assertThat(inputs.size()).isEqualTo(calls);
            String endpoint = "/knowledge-bases/" + kb + "/processing-config";
            var update = (tools.jackson.databind.node.ObjectNode) JSON.readTree(processing);
            ((tools.jackson.databind.node.ObjectNode) update.path("chunking")).put("strategy", "fixed");
            String key = UUID.randomUUID().toString();
            HttpResponse<String> accepted = send(client, endpoint, "PUT", update.toString(), key);
            assertThat(accepted.statusCode()).isEqualTo(202);
            String parent = JSON.readTree(accepted.body()).at("/task/id").asText();
            assertThat(JSON.readTree(send(client, endpoint, "PUT", update.toString(), key).body()).at("/task/id").asText()).isEqualTo(parent);
            assertThat(await(client,parent).path("state").asText()).isEqualTo("succeeded");
            JsonNode effective = read(client, "/knowledge-bases/" + kb);
            assertThat(effective.path("effectiveConfigVersionId")).isEqualTo(effective.path("targetConfigVersionId"));
            assertThat(effective.path("effectiveConfigVersionId")).isNotEqualTo(original.path("effectiveConfigVersionId"));
            ((tools.jackson.databind.node.ObjectNode) update.path("chunking")).put("strategy", "page");
            assertThat(send(client, endpoint, "PUT", update.toString(), key).statusCode()).isEqualTo(409);
            JsonNode failed = JSON.readTree(send(client, endpoint, "PUT", update.toString(), UUID.randomUUID().toString()).body());
            assertThat(await(client,failed.at("/task/id").asText()).path("state").asText()).isEqualTo("failed");
            assertThat(read(client, "/knowledge-bases/" + kb).path("effectiveConfigVersionId")).isEqualTo(effective.path("effectiveConfigVersionId"));
            JsonNode search = search(client,kb);
            assertThat(await(client,search.at("/task/id").asText()).path("state").asText()).isEqualTo("succeeded");
            JsonNode result = read(client,"/search-requests/" + search.path("id").asText());
            for (JsonNode evidence : result.at("/result/evidence")) assertThat(evidence.path("configVersionId")).isEqualTo(effective.path("effectiveConfigVersionId"));
            assertThat(send(client,"/tasks/" + parent + "/chunk-preflight","GET",null,null).statusCode()).isEqualTo(404);
            assertThat(send(client,"/tasks/" + taskId + "/chunk-preflight?unknown=1","GET",null,null).statusCode()).isEqualTo(400);
            assertThat(send(client,"/tasks/" + UUID.randomUUID() + "/chunk-preflight","GET",null,null).statusCode()).isEqualTo(404);
            read(client,"/knowledge-bases/" + kb + "/tasks");
        } finally { vector.stop(0); }
    }

    @Test
    @DisplayName("Markdown索引保留真实标题段落表格代码与源行")
    void markdownLocationsRemainBoundToOriginalSource() throws Exception {
        HttpServer vector = indexedVector(new java.util.concurrent.CopyOnWriteArrayList<>(), () -> {});
        Path directory = Files.createTempDirectory(Path.of("target"), "markdown-http-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = processing(client, vector.getAddress().getPort(), 0, "{}", "[]");
            String dataset = readCreatedDataset(client, "文档定位资料");
            String source = Files.readString(Path.of("src/test/resources/documents/source-location.md"));
            uploadText(client, dataset, source, "source.md");
            JsonNode accepted = JSON.readTree(send(client, "/knowledge-bases", "POST",
                    "{\"name\":\"文档定位库\",\"processing\":" + processing + ",\"datasetIds\":[\"" + dataset + "\"]}",
                    UUID.randomUUID().toString()).body());
            String kb = await(client, accepted.at("/task/id").asText()).path("resultResourceId").asText();
            for (JsonNode file : read(client, "/knowledge-bases/" + kb + "/files").path("items")) {
                JsonNode task = await(client, file.at("/task/id").asText());
                assertThat(task.path("state").asText()).as(task.toString()).isEqualTo("succeeded");
            }
            JsonNode request = search(client, kb);
            await(client, request.at("/task/id").asText());
            JsonNode result = read(client, "/search-requests/" + request.path("id").asText());
            assertThat(result.at("/result/evidence")).anySatisfy(evidence -> {
                assertThat(evidence.path("text").asText()).contains("第一段😀。");
                assertThat(evidence.at("/location/format").asText()).isEqualTo("markdown");
                assertThat(evidence.at("/location/lineStart").asInt()).isEqualTo(3);
                assertThat(evidence.at("/location/lineEnd").asInt()).isEqualTo(4);
                assertThat(evidence.at("/location/titlePath/0").asText()).isEqualTo("使用指南");
                assertThat(evidence.at("/location/paragraphId").isTextual()).isTrue();
            });
            assertThat(result.at("/result/evidence")).anySatisfy(evidence -> {
                assertThat(evidence.at("/location/tableRowStart").asInt(-1)).isEqualTo(0);
                assertThat(evidence.at("/location/tableRowEnd").asInt(-1)).isEqualTo(1);
                assertThat(evidence.at("/location/tableColumnEnd").asInt(-1)).isEqualTo(1);
                assertThat(evidence.at("/location/lineStart").asInt()).isEqualTo(11);
                assertThat(evidence.at("/location/lineEnd").asInt()).isEqualTo(13);
            });
            assertThat(result.at("/result/evidence")).anySatisfy(evidence -> {
                assertThat(evidence.at("/location/codeLanguage").asText()).isEqualTo("java");
                assertThat(evidence.at("/location/codeBlockId").isTextual()).isTrue();
                assertThat(evidence.at("/location/lineStart").asInt()).isEqualTo(15);
                assertThat(evidence.at("/location/lineEnd").asInt()).isEqualTo(17);
            });
            for (JsonNode evidence : result.at("/result/evidence")) {
                JsonNode complete = read(client, "/search-requests/" + request.path("id").asText() + "/evidence/" + evidence.path("id").asText());
                assertThat(complete.path("location")).isEqualTo(evidence.path("location"));
            }
        } finally { vector.stop(0); }
    }

    @Test
    @DisplayName("中文PDF全文证据保留跨页句子与中间空白页实际范围")
    void pdfLocationsKeepCrossPageSourceAndBlankPages() throws Exception {
        HttpServer vector = indexedVector(new java.util.concurrent.CopyOnWriteArrayList<>(), () -> {});
        Path directory = Files.createTempDirectory(Path.of("target"), "pdf-location-http-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = processing(client, vector.getAddress().getPort(), 0, "{}", "[]");
            for (String fixture : java.util.List.of("chinese-cross-page.pdf", "chinese-blank-middle-page.pdf")) {
                String dataset = readCreatedDataset(client, fixture);
                byte[] bytes = Files.readAllBytes(Path.of("src/test/resources/documents", fixture));
                String boundary = "pdf-location-boundary";
                var body = new java.io.ByteArrayOutputStream();
                body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"" + fixture + "\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                body.write(bytes);
                body.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                HttpResponse<String> uploaded = http.send(HttpRequest.newBuilder(URI.create(client.base() + "/datasets/" + dataset + "/files:upload"))
                        .header("Cookie", client.cookie()).header("X-CSRF-Token", client.token())
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(uploaded.statusCode()).isEqualTo(200);
                assertThat(JSON.readTree(uploaded.body()).at("/items/0/outcome").asText()).isEqualTo("created");
                JsonNode accepted = JSON.readTree(send(client, "/knowledge-bases", "POST",
                        "{\"name\":\"" + fixture + "\",\"processing\":" + processing + ",\"datasetIds\":[\"" + dataset + "\"]}", UUID.randomUUID().toString()).body());
                String kb = await(client, accepted.at("/task/id").asText()).path("resultResourceId").asText();
                for (JsonNode file : read(client, "/knowledge-bases/" + kb + "/files").path("items")) {
                    JsonNode task = await(client, file.at("/task/id").asText());
                    assertThat(task.path("state").asText()).as(task.toString()).isEqualTo("succeeded");
                    assertThat(task.path("warnings")).isNotEmpty();
                }
                JsonNode request = search(client, kb);
                await(client, request.at("/task/id").asText());
                JsonNode result = read(client, "/search-requests/" + request.path("id").asText());
                JsonNode evidence = result.at("/result/evidence/0");
                assertThat(evidence.at("/location/format").asText()).isEqualTo("pdf");
                assertThat(evidence.at("/location/pageStart").asInt()).isEqualTo(1);
                assertThat(evidence.at("/location/pageEnd").asInt()).isEqualTo(fixture.contains("blank") ? 3 : 2);
                assertThat(evidence.at("/location/lineStart").isNull()).isTrue();
                assertThat(evidence.at("/location/tableRowStart").isNull()).isTrue();
                if (!fixture.contains("blank")) assertThat(evidence.path("text").asText()).contains("这是一句跨页的", "连续中文句子。");
                read(client, "/search-requests/" + request.path("id").asText() + "/evidence/" + evidence.path("id").asText());
            }
        } finally { vector.stop(0); }
    }

    @Test
    @DisplayName("公开排队快照、知识库依赖状态、未知资源及存储故障均保留真实契约证据")
    void publicContractBranchesRemainReachable() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var blocked = new java.util.concurrent.atomic.AtomicBoolean(false);
        HttpServer vector = indexedVector(new java.util.concurrent.CopyOnWriteArrayList<>(), () -> {
            if (blocked.get()) {
                entered.countDown();
                try {
                    if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS))
                        throw new AssertionError("受控模型等待超时。");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("受控模型等待被中断。", failure);
                }
            }
        });
        Path directory =
                Files.createTempDirectory(Path.of("target"), "contract-gaps-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = processing(client, vector.getAddress().getPort(), 0, "{}", "[]");
            String kb = createIndexedBase(client, processing, "契约可达分支", "完整原文依据。");
            JsonNode current = read(client, "/knowledge-bases/" + kb);
            assertThat(current.path("effectiveConfigVersionId").isTextual()).isTrue();
            assertThat(current.path("effectiveIndexVersionId").isTextual()).isTrue();
            read(client, "/knowledge-bases");
            assertThat(send(client, "/knowledge-bases/" + kb, "PATCH", "{\"description\":\"已生效\"}", null)
                            .statusCode())
                    .isEqualTo(200);
            blocked.set(true);
            JsonNode first = search(client, kb);
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            JsonNode queued = search(client, kb);
            JsonNode snapshot =
                    read(client, "/search-requests/" + queued.path("id").asText());
            assertThat(snapshot.at("/task/state").asText()).isEqualTo("queued");
            for (String pointer : java.util.List.of("/scope", "/task/startedAt", "/task/finishedAt"))
                assertThat(snapshot.at(pointer).isNull()).as(pointer).isTrue();
            String dataset = readCreatedDataset(client, "排队索引资料");
            uploadText(client, dataset, "第二份排队资料。");
            uploadText(client, dataset, "第三份排队资料。", "third.txt");
            JsonNode creation = JSON.readTree(send(
                            client,
                            "/knowledge-bases",
                            "POST",
                            "{\"name\":\"排队索引库\",\"processing\":" + processing + ",\"datasetIds\":[\"" + dataset
                                    + "\"]}",
                            UUID.randomUUID().toString())
                    .body());
            String queuedKb = await(client, creation.at("/task/id").asText())
                    .path("resultResourceId")
                    .asText();
            JsonNode tasks = read(client, "/knowledge-bases/" + queuedKb + "/tasks");
            assertThat(tasks.path("items")).anySatisfy(task -> {
                assertThat(task.path("state").asText()).isEqualTo("queued");
                assertThat(task.path("startedAt").isNull()).isTrue();
                assertThat(task.path("finishedAt").isNull()).isTrue();
                assertThat(task.path("progressTotal").isNull()).isTrue();
            });
            blocked.set(false);
            release.countDown();
            await(client, first.at("/task/id").asText());
            await(client, queued.at("/task/id").asText());
            for (JsonNode task : tasks.path("items"))
                await(client, task.path("id").asText());
            String absent = UUID.randomUUID().toString();
            assertThat(send(client, "/knowledge-bases/" + absent + "/tasks", "GET", null, null)
                            .statusCode())
                    .isEqualTo(404);
            assertThat(send(
                                    client,
                                    "/knowledge-bases/" + absent + "/search-test",
                                    "POST",
                                    "{\"query\":\"未知库\",\"knowledgeBaseIds\":[\"" + absent + "\"]}",
                                    UUID.randomUUID().toString())
                            .statusCode())
                    .isEqualTo(404);
            var invalid = send(
                    client, "/search-requests/" + first.path("id").asText() + "/evidence/invalid", "GET", null, null);
            assertThat(invalid.statusCode()).isEqualTo(400);
            assertThat(JSON.readTree(invalid.body()).path("fieldErrors")).isNotEmpty();
            vector.stop(0);
            JsonNode failed = search(client, kb);
            assertThat(await(client, failed.at("/task/id").asText())
                            .path("state")
                            .asText())
                    .isEqualTo("failed");
            assertThat(read(client, "/knowledge-bases/" + kb)
                            .path("availabilityReason")
                            .isTextual())
                    .isTrue();
            read(client, "/knowledge-bases");
            var renamed = send(client, "/knowledge-bases/" + kb, "PATCH", "{\"description\":\"依赖故障仍保留生效版本\"}", null);
            assertThat(renamed.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(renamed.body()).path("availabilityReason").isTextual())
                    .isTrue();
            try (var database = java.sql.DriverManager.getConnection(
                            "jdbc:sqlite:" + directory.resolve("application.sqlite"));
                    var statement = database.createStatement()) {
                statement.execute("ALTER TABLE search_identities RENAME TO blocked_search_identities");
                statement.execute("ALTER TABLE task_events RENAME TO blocked_task_events");
                try {
                    for (String path : java.util.List.of(
                            "/search-requests/" + absent,
                            "/search-requests/" + absent + "/evidence/" + UUID.randomUUID(),
                            "/tasks/" + first.at("/task/id").asText() + "/events"))
                        assertThat(send(client, path, "GET", null, null).statusCode())
                                .as(path)
                                .isEqualTo(500);
                } finally {
                    statement.execute("ALTER TABLE blocked_search_identities RENAME TO search_identities");
                    statement.execute("ALTER TABLE blocked_task_events RENAME TO task_events");
                }
            }
            assertThat(send(client, "/search-requests/" + absent, "GET", null, null)
                            .statusCode())
                    .isEqualTo(404);
        } finally {
            release.countDown();
            vector.stop(0);
        }
    }

    @Test
    @DisplayName("真实上传切片保留缩写小数引用和换行，不切断完整句")
    void sentencesRemainIntact() throws Exception {
        String first = "首".repeat(1800) + "。";
        String second = "Dr. Smith measured 3.14, e.g. a reference “引用\n续行” " + "b".repeat(240) + ".";
        assertThat(index(first + second, 0, "{}", "[]")).containsExactly(first, second);
    }

    @Test
    @DisplayName("重叠只复用完整句，并在下一句需要空间时取消")
    void overlapKeepsCompleteSentences() throws Exception {
        String first = "前".repeat(1500) + "。";
        String second = "中".repeat(100) + "。";
        String third = "后".repeat(500) + "。";
        assertThat(index(first + second + third, 200, "{}", "[]")).containsExactly(first + second, second + third);
        String large = "尾".repeat(1950) + "。";
        assertThat(index(first + second + large, 200, "{}", "[]")).containsExactly(first + second, large);
    }

    @Test
    @DisplayName("超长句按分隔符降级，补充平面字符按码点硬切且正文无丢失")
    void longSentencesKeepEveryCodePoint() throws Exception {
        String first = "甲".repeat(1900) + "，";
        String second = "乙".repeat(300);
        assertThat(index(first + second, 0, "{}", "[]")).containsExactly(first, second);
        String text = "😀甲".repeat(1300);
        java.util.List<String> chunks = index(text, 0, "{}", "[]");
        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst()).isEqualTo("😀甲".repeat(1000));
        assertThat(chunks.getLast()).isEqualTo("😀甲".repeat(300));
        assertThat(String.join("", chunks)).isEqualTo(text);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.codePointCount(0, chunk.length()))
                .isLessThanOrEqualTo(2000));
    }

    @Test
    @DisplayName("句末引用保留在原句，列表编号和首字母缩写不冒充句末")
    void quotationAndListBoundariesRemainTraceable() throws Exception {
        String first = "前".repeat(1948) + "。”";
        String second = "\n1. Dr. A. Smith in the U.S. measured 3.14, e.g. “引用\n续行” " + "后".repeat(200) + "！";
        assertThat(index(first + second, 0, "{}", "[]")).containsExactly(first, second);
    }

    @Test
    @DisplayName("嵌入输入仅加入选定自定义文本字段，未选中字段不外发")
    void onlySelectedMetadataEntersEmbedding() throws Exception {
        assertThat(index("固定正文", 0, "{\"user_label\":\"选中内容\",\"user_private\":\"未选内容\"}", "[\"user_label\"]"))
                .containsExactly("固定正文\nuser_label: 选中内容");
    }

    @Test
    @DisplayName("正文与选中元信息共同超过模型预算时拒绝外发和生效")
    void metadataBudgetPreventsDispatch() throws Exception {
        assertThat(index(
                        "正".repeat(1000),
                        0,
                        "{\"user_label\":\"" + "元".repeat(2000) + "\"}",
                        "[\"user_label\"]",
                        "failed"))
                .isEmpty();
    }

    @Test
    @DisplayName("首次嵌入外发期间覆盖上传，旧任务取消且迟到响应不能发布")
    void replacedVersionCannotPublishLateEmbedding() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        var inputs = new java.util.concurrent.CopyOnWriteArrayList<String>();
        HttpServer vector = indexedVector(inputs, () -> {
            if (first.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                            .isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("受控嵌入等待被中断。", interrupted);
                }
            }
        });
        Path directory =
                Files.createTempDirectory(Path.of("target"), "late-embedding-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = processing(client, vector.getAddress().getPort(), 0, "{}", "[]");
            String dataset = JSON.readTree(send(client, "/datasets", "POST", "{\"name\":\"首次版本保护\"}", null)
                            .body())
                    .path("id")
                    .asText();
            uploadText(client, dataset, "旧正文迟到返回");
            JsonNode accepted = JSON.readTree(send(
                            client,
                            "/knowledge-bases",
                            "POST",
                            "{\"name\":\"首次索引保护\",\"processing\":" + processing + ",\"datasetIds\":[\"" + dataset
                                    + "\"]}",
                            UUID.randomUUID().toString())
                    .body());
            String kb = await(client, accepted.at("/task/id").asText())
                    .path("resultResourceId")
                    .asText();
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            JsonNode initial = read(client, "/knowledge-bases/" + kb + "/files").at("/items/0");
            String oldTask = initial.at("/task/id").asText();
            assertThat(initial.path("effectiveIndexVersionId").isNull()).isTrue();
            JsonNode replacement = uploadText(client, dataset, "新正文唯一生效");
            JsonNode cancelled = await(client, oldTask);
            assertThat(cancelled.path("state").asText()).isEqualTo("cancelled");
            assertThat(cancelled.path("resultResourceId").isNull()).isTrue();
            release.countDown();
            JsonNode completed =
                    await(client, replacement.at("/items/0/indexTaskIds/0").asText());
            assertThat(completed.path("state").asText())
                    .as(completed.toString())
                    .isEqualTo("succeeded");
            JsonNode current = read(client, "/knowledge-bases/" + kb + "/files").at("/items/0");
            assertThat(current.path("effectiveIndexVersionId").asText())
                    .isEqualTo(completed.path("resultResourceId").asText());
            assertThat(current.path("effectiveContentVersionId").asText())
                    .isEqualTo(completed.path("targetContentVersionId").asText());
            assertThat(read(client, "/tasks/" + oldTask).path("state").asText()).isEqualTo("cancelled");
            JsonNode search = JSON.readTree(send(
                            client,
                            "/knowledge-bases/" + kb + "/search-test",
                            "POST",
                            "{\"query\":\"新正文\",\"knowledgeBaseIds\":[\"" + kb + "\"]}",
                            UUID.randomUUID().toString())
                    .body());
            assertThat(await(client, search.at("/task/id").asText())
                            .path("state")
                            .asText())
                    .isEqualTo("succeeded");
            JsonNode result =
                    read(client, "/search-requests/" + search.path("id").asText());
            assertThat(result.at("/result/evidence").size()).isEqualTo(1);
            assertThat(result.at("/result/evidence/0/text").asText()).isEqualTo("新正文唯一生效");
            assertThat(result.at("/result/evidence/0/contentVersionId").asText())
                    .isEqualTo(completed.path("targetContentVersionId").asText());
        } finally {
            release.countDown();
            vector.stop(0);
        }
    }

    @Test
    @DisplayName("检索外发期间切换生效版本，当前请求仍返回捕获版本的完整证据")
    void searchRetainsCapturedVersionDuringSwitch() throws Exception {
        checkSearchSnapshot(false);
    }

    @Test
    @DisplayName("覆盖更新尚在运行时旧索引证据使用契约updating状态且知识库聚合保持运行")
    void updatingEvidenceUsesContractState() throws Exception {
        checkSearchSnapshot(true);
    }

    private void checkSearchSnapshot(boolean updating) throws Exception {
        var updatingEntered = new java.util.concurrent.CountDownLatch(1);
        var updatingRelease = new java.util.concurrent.CountDownLatch(1);
        var embeddingCalls = new java.util.concurrent.atomic.AtomicInteger();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        HttpServer vector = indexedVector(
                new java.util.concurrent.CopyOnWriteArrayList<>(),
                () -> {
                    if (updating && embeddingCalls.incrementAndGet() == 3) {
                        updatingEntered.countDown();
                        try {
                            assertThat(updatingRelease.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                    .isTrue();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("受控更新等待被中断。", interrupted);
                        }
                    }
                },
                () -> {
                    if (first.compareAndSet(true, false)) {
                        entered.countDown();
                        try {
                            assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                    .isTrue();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("受控检索等待被中断。", interrupted);
                        }
                    }
                });
        Path directory =
                Files.createTempDirectory(Path.of("target"), "search-snapshot-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = processing(client, vector.getAddress().getPort(), 0, "{}", "[]");
            String dataset = JSON.readTree(send(client, "/datasets", "POST", "{\"name\":\"检索版本快照\"}", null)
                            .body())
                    .path("id")
                    .asText();
            uploadText(client, dataset, "原生效正文");
            JsonNode accepted = JSON.readTree(send(
                            client,
                            "/knowledge-bases",
                            "POST",
                            "{\"name\":\"读取版本保护\",\"processing\":" + processing + ",\"datasetIds\":[\"" + dataset
                                    + "\"]}",
                            UUID.randomUUID().toString())
                    .body());
            String kb = await(client, accepted.at("/task/id").asText())
                    .path("resultResourceId")
                    .asText();
            JsonNode oldTask = await(
                    client,
                    read(client, "/knowledge-bases/" + kb + "/files")
                            .at("/items/0/task/id")
                            .asText());
            assertThat(oldTask.path("state").asText()).isEqualTo("succeeded");
            JsonNode search = JSON.readTree(send(
                            client,
                            "/knowledge-bases/" + kb + "/search-test",
                            "POST",
                            "{\"query\":\"版本快照\",\"knowledgeBaseIds\":[\"" + kb + "\"]}",
                            UUID.randomUUID().toString())
                    .body());
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            JsonNode replacement = uploadText(client, dataset, "后续生效正文");
            String replacementTask = replacement.at("/items/0/indexTaskIds/0").asText();
            if (updating)
                assertThat(updatingEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        .isTrue();
            JsonNode newTask = updating ? read(client, "/tasks/" + replacementTask) : await(client, replacementTask);
            assertThat(newTask.path("state").asText())
                    .as(newTask.toString())
                    .isEqualTo(updating ? "running" : "succeeded");
            assertThat(read(client, "/knowledge-bases/" + kb)
                            .path("processingState")
                            .asText())
                    .isEqualTo(updating ? "running" : "idle");
            assertThat(read(client, "/knowledge-bases")
                            .at("/items/0/processingState")
                            .asText())
                    .isEqualTo(updating ? "running" : "idle");
            assertThat(newTask.path("targetContentVersionId").asText())
                    .isNotEqualTo(oldTask.path("targetContentVersionId").asText());
            release.countDown();
            assertThat(await(client, search.at("/task/id").asText())
                            .path("state")
                            .asText())
                    .isEqualTo("succeeded");
            JsonNode result =
                    read(client, "/search-requests/" + search.path("id").asText());
            assertThat(result.at("/result/outcome").asText())
                    .as(result.toString())
                    .isEqualTo("matches");
            assertThat(result.at("/result/evidence").size()).isEqualTo(1);
            assertThat(result.at("/result/evidence/0/text").asText()).isEqualTo("原生效正文");
            assertThat(result.at("/result/evidence/0/contentVersionId").asText())
                    .isEqualTo(oldTask.path("targetContentVersionId").asText());
            assertThat(result.at("/result/evidence/0/indexVersionId").asText())
                    .isEqualTo(oldTask.path("resultResourceId").asText());
            assertThat(result.at("/result/evidence/0/updateState").asText()).isEqualTo(updating ? "updating" : "ready");
            assertThat(result.at("/result/evidence/0/usingPreviousVersion").asBoolean())
                    .isTrue();
            updatingRelease.countDown();
            assertThat(await(client, replacementTask).path("state").asText()).isEqualTo("succeeded");
        } finally {
            release.countDown();
            updatingRelease.countDown();
            vector.stop(0);
        }
    }

    @Test
    @DisplayName("供应商原始用量小数和整数溢出均不得产生生效索引")
    void malformedUsageCannotPublish() throws Exception {
        for (String usage :
                java.util.List.of("4.5", "4294967300", "-1", "null", "\"4\"", "9007199254740992", "100", "missing")) {
            assertThat(index("固定正文", 0, "{}", "[]", "failed", usage, "INTEGRITY_FAILED"))
                    .hasSize(1);
        }
    }

    @Test
    @DisplayName("索引成功持久保存独立实际用量字节上界和计量器版本")
    void successfulIndexRecordsMeasuredUsage() throws Exception {
        assertThat(index("😀甲e\u0301\t正文", 0, "{}", "[]")).containsExactly("😀甲e\u0301\t正文");
    }

    @Test
    @DisplayName("完整输入恰好字节预算可运行，超一字节拒绝外发，零实际用量合法")
    void exactInputBudgetAndZeroUsage() throws Exception {
        String text = "正".repeat(2000);
        String metadata = "元".repeat(726) + "a";
        assertThat(index(text, 0, "{\"user_label\":\"" + metadata + "\"}", "[\"user_label\"]"))
                .containsExactly(text + "\nuser_label: " + metadata);
        assertThat(index(text, 0, "{\"user_label\":\"" + metadata + "b\"}", "[\"user_label\"]", "failed"))
                .isEmpty();
        assertThat(index("固定正文", 0, "{}", "[]", "succeeded", "0", "")).containsExactly("固定正文");
    }

    @Test
    @DisplayName("多文件一项成功但另一项仍运行时知识库列表与详情保持运行状态")
    void multipleFilesKeepAggregateRunning() throws Exception {
        var firstEntered = new java.util.concurrent.CountDownLatch(1);
        var firstRelease = new java.util.concurrent.CountDownLatch(1);
        var secondEntered = new java.util.concurrent.CountDownLatch(1);
        var secondRelease = new java.util.concurrent.CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer vector = indexedVector(new java.util.concurrent.CopyOnWriteArrayList<>(), () -> {
            int call = calls.incrementAndGet();
            (call == 1 ? firstEntered : secondEntered).countDown();
            try {
                assertThat((call == 1 ? firstRelease : secondRelease).await(5, java.util.concurrent.TimeUnit.SECONDS))
                        .isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("受控多文件等待被中断。", interrupted);
            }
        });
        Path directory =
                Files.createTempDirectory(Path.of("target"), "aggregate-http-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = processing(client, vector.getAddress().getPort(), 0, "{}", "[]");
            String dataset = readCreatedDataset(client, "多文件聚合");
            uploadText(client, dataset, "甲资料");
            uploadText(client, dataset, "乙资料", "second.txt");
            JsonNode accepted = JSON.readTree(send(
                            client,
                            "/knowledge-bases",
                            "POST",
                            "{\"name\":\"多文件聚合\",\"processing\":" + processing + ",\"datasetIds\":[\"" + dataset
                                    + "\"]}",
                            UUID.randomUUID().toString())
                    .body());
            String kb = await(client, accepted.at("/task/id").asText())
                    .path("resultResourceId")
                    .asText();
            assertThat(firstEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isTrue();
            assertThat(read(client, "/knowledge-bases/" + kb)
                            .path("processingState")
                            .asText())
                    .isEqualTo("running");
            firstRelease.countDown();
            assertThat(secondEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isTrue();
            assertThat(read(client, "/knowledge-bases/" + kb)
                            .path("searchableFileCount")
                            .asInt())
                    .isEqualTo(1);
            assertThat(read(client, "/knowledge-bases/" + kb)
                            .path("processingState")
                            .asText())
                    .isEqualTo("running");
            assertThat(read(client, "/knowledge-bases")
                            .at("/items/0/processingState")
                            .asText())
                    .isEqualTo("running");
            secondRelease.countDown();
            for (JsonNode file :
                    read(client, "/knowledge-bases/" + kb + "/files").path("items"))
                assertThat(await(client, file.at("/task/id").asText())
                                .path("state")
                                .asText())
                        .isEqualTo("succeeded");
            assertThat(read(client, "/knowledge-bases/" + kb)
                            .path("processingState")
                            .asText())
                    .isEqualTo("idle");
        } finally {
            firstRelease.countDown();
            secondRelease.countDown();
            vector.stop(0);
        }
    }

    @Test
    @DisplayName("批量命中按召回顺序去重并排除越库切片，超候选上限失败")
    void retrievedCandidatesAreBoundedOrderedAndScoped() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var foreign = new java.util.concurrent.atomic.AtomicReference<JsonNode>();
        var orderedIds = new java.util.ArrayList<String>();
        HttpServer vector =
                indexedVector(new java.util.concurrent.CopyOnWriteArrayList<>(), () -> {}, () -> {}, "4", hits -> {
                    int call = calls.incrementAndGet();
                    if (call == 1) foreign.set(hits.get(0).deepCopy());
                    else if (call == 2) {
                        var reversed = new java.util.ArrayList<JsonNode>();
                        hits.forEach(reversed::add);
                        java.util.Collections.reverse(reversed);
                        hits.removeAll();
                        for (JsonNode hit : reversed) {
                            hits.add(hit);
                            orderedIds.add(hit.path("id").asText());
                        }
                        hits.add(reversed.getFirst());
                        hits.add(foreign.get());
                        hits.add(JSON.createObjectNode()
                                .put("id", UUID.randomUUID().toString())
                                .put("score", 0.9));
                    } else {
                        JsonNode hit = hits.get(0).deepCopy();
                        hits.removeAll();
                        for (int i = 0; i < 11; i++) hits.add(hit);
                    }
                });
        Path directory =
                Files.createTempDirectory(Path.of("target"), "retrieval-batch-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = processing(client, vector.getAddress().getPort(), 0, "{}", "[]");
            String other = createIndexedBase(client, processing, "另一知识库", "越库资料");
            JsonNode foreignRequest = search(client, other);
            assertThat(await(client, foreignRequest.at("/task/id").asText())
                            .path("state")
                            .asText())
                    .isEqualTo("succeeded");
            String kb = createIndexedBase(client, processing, "当前知识库", "甲".repeat(2000) + "乙".repeat(2000));
            JsonNode request = search(client, kb);
            assertThat(await(client, request.at("/task/id").asText())
                            .path("state")
                            .asText())
                    .isEqualTo("succeeded");
            JsonNode result = read(
                            client, "/search-requests/" + request.path("id").asText())
                    .at("/result/evidence");
            var actual = new java.util.ArrayList<String>();
            for (JsonNode value : result) {
                actual.add(value.path("chunkId").asText());
                assertThat(value.path("knowledgeBaseId").asText()).isEqualTo(kb);
            }
            assertThat(actual).containsExactlyElementsOf(orderedIds).hasSize(2);
            JsonNode overflow = search(client, kb);
            JsonNode failed = await(client, overflow.at("/task/id").asText());
            assertThat(failed.path("state").asText()).isEqualTo("failed");
            assertThat(failed.at("/failure/code").asText()).isEqualTo("INTEGRITY_FAILED");
        } finally {
            vector.stop(0);
        }
    }

    private String readCreatedDataset(Client client, String name) throws Exception {
        return JSON.readTree(send(client, "/datasets", "POST", "{\"name\":\"" + name + "\"}", null)
                        .body())
                .path("id")
                .asText();
    }

    private String createIndexedBase(Client client, String processing, String name, String text) throws Exception {
        String dataset = readCreatedDataset(client, name);
        uploadText(client, dataset, text);
        JsonNode accepted = JSON.readTree(send(
                        client,
                        "/knowledge-bases",
                        "POST",
                        "{\"name\":\"" + name + "\",\"processing\":" + processing + ",\"datasetIds\":[\"" + dataset
                                + "\"]}",
                        UUID.randomUUID().toString())
                .body());
        String kb = await(client, accepted.at("/task/id").asText())
                .path("resultResourceId")
                .asText();
        for (JsonNode file : read(client, "/knowledge-bases/" + kb + "/files").path("items"))
            assertThat(await(client, file.at("/task/id").asText()).path("state").asText())
                    .isEqualTo("succeeded");
        return kb;
    }

    private JsonNode search(Client client, String kb) throws Exception {
        return JSON.readTree(send(
                        client,
                        "/knowledge-bases/" + kb + "/search-test",
                        "POST",
                        "{\"query\":\"固定查询\",\"knowledgeBaseIds\":[\"" + kb + "\"]}",
                        UUID.randomUUID().toString())
                .body());
    }

    private java.util.List<String> index(String text, int overlap, String metadata, String fields) throws Exception {
        return index(text, overlap, metadata, fields, "succeeded");
    }

    private java.util.List<String> index(String text, int overlap, String metadata, String fields, String expectedState)
            throws Exception {
        return index(text, overlap, metadata, fields, expectedState, "4", "MODEL_CONFIG_INVALID");
    }

    private java.util.List<String> index(
            String text,
            int overlap,
            String metadata,
            String fields,
            String expectedState,
            String usage,
            String failureCode)
            throws Exception {
        var inputs = new java.util.concurrent.CopyOnWriteArrayList<String>();
        HttpServer vector = indexedVector(inputs, () -> {}, () -> {}, usage);
        Path directory =
                Files.createTempDirectory(Path.of("target"), "chunking-http-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = processing(client, vector.getAddress().getPort(), overlap, metadata, fields);
            String dataset = JSON.readTree(send(client, "/datasets", "POST", "{\"name\":\"切片固定样例\"}", null)
                            .body())
                    .path("id")
                    .asText();
            uploadText(client, dataset, text);
            HttpResponse<String> response = send(
                    client,
                    "/knowledge-bases",
                    "POST",
                    "{\"name\":\"切片校验\",\"processing\":" + processing + ",\"datasetIds\":[\"" + dataset + "\"]}",
                    UUID.randomUUID().toString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
            JsonNode created =
                    await(client, JSON.readTree(response.body()).at("/task/id").asText());
            assertThat(created.path("state").asText()).as(created.toString()).isEqualTo("succeeded");
            String kb = created.path("resultResourceId").asText();
            JsonNode file = read(client, "/knowledge-bases/" + kb + "/files")
                    .path("items")
                    .get(0);
            JsonNode indexed = await(client, file.at("/task/id").asText());
            assertThat(indexed.path("state").asText()).as(indexed.toString()).isEqualTo(expectedState);
            if (expectedState.equals("failed")) {
                assertThat(indexed.at("/failure/code").asText()).isEqualTo(failureCode);
                assertThat(read(client, "/knowledge-bases/" + kb)
                                .path("searchableFileCount")
                                .asInt())
                        .isZero();
            }
            assertThat(read(client, "/knowledge-bases/" + kb)
                            .path("processingState")
                            .asText())
                    .isEqualTo(expectedState.equals("failed") ? "failed" : "idle");
            if (expectedState.equals("succeeded")) {
                var measurements = app.getBean(cn.knowpool.infrastructure.ApplicationData.class)
                        .read(jdbc -> jdbc.sql(
                                        "SELECT input_upper_bound,prompt_tokens,meter_version FROM index_chunks ORDER BY ordinal")
                                .query((row, n) -> java.util.List.of(row.getInt(1), row.getInt(2), row.getString(3)))
                                .list());
                assertThat(measurements).hasSize(inputs.size());
                for (int i = 0; i < inputs.size(); i++) {
                    assertThat(measurements.get(i))
                            .containsExactly(
                                    inputs.get(i).getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                                    Integer.parseInt(usage),
                                    "qwen-byte-upper-bound-1");
                }
            }
            return java.util.List.copyOf(inputs);
        } finally {
            vector.stop(0);
        }
    }

    private String processing(Client client, int port, int overlap, String metadata, String fields) throws Exception {
        configure(client, port);
        send(
                client,
                "/settings/models/embedding",
                "PUT",
                "{\"endpoint\":\"http://127.0.0.1:" + port
                        + "/v1\",\"model\":\"text-embedding-v4\",\"contextWindowTokens\":null,\"maxOutputTokens\":null,\"credential\":{\"action\":\"replace\",\"value\":\"fixed-test-key\"}}",
                null);
        JsonNode saved = read(client, "/settings/models/embedding");
        return "{\"chunking\":{\"strategy\":\"structure\",\"overlapCodePoints\":" + overlap
                + ",\"removePageHeadersFooters\":false,\"customMetadata\":" + metadata
                + ",\"embeddedMetadataFields\":" + fields
                + "},\"embedding\":{\"modelConfigVersionId\":\""
                + saved.path("versionId").asText()
                + "\",\"dimensions\":3,\"distance\":\"cosine\",\"similarityThreshold\":0.5,\"candidatesPerQuery\":10,\"modelSnapshot\":{\"endpoint\":\"http://127.0.0.1:"
                + port + "/v1\",\"model\":\"text-embedding-v4\"}}}";
    }

    private void configure(Client client, int port) throws Exception {
        String endpoint = "http://127.0.0.1:" + port;
        assertThat(send(
                                client,
                                "/settings/vector-store",
                                "PUT",
                                "{\"endpoint\":\"" + endpoint + "\",\"credential\":{\"action\":\"clear\"}}",
                                null)
                        .statusCode())
                .isEqualTo(200);
    }

    private JsonNode uploadText(Client client, String dataset, String text) throws Exception {
        return uploadText(client, dataset, text, "source.txt");
    }

    private JsonNode uploadText(Client client, String dataset, String text, String filename) throws Exception {
        String boundary = "knowledge-boundary";
        String body = "--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"" + filename
                + "\"\r\nContent-Type: " + (filename.endsWith(".md") ? "text/markdown" : "text/plain") + "\r\n\r\n"
                + text + "\r\n--"
                + boundary + "--\r\n";
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(client.base() + "/datasets/" + dataset + "/files:upload"))
                .header("Cookie", client.cookie())
                .header("X-CSRF-Token", client.token())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private JsonNode await(Client client, String id) throws Exception {
        for (int i = 0; i < 200; i++) {
            JsonNode task = read(client, "/tasks/" + id);
            if (!java.util.Set.of("queued", "running")
                    .contains(task.path("state").asText())) return task;
            Thread.sleep(25);
        }
        throw new AssertionError("创建任务没有按期结束。");
    }

    private JsonNode read(Client client, String path) throws Exception {
        HttpResponse<String> response = send(client, path, "GET", null, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> send(Client client, String path, String method, String body, String key)
            throws Exception {
        return send(client,path,method,body,key,null);
    }

    private HttpResponse<String> send(Client client,String path,String method,String body,String key,String lastEventId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(client.base() + path));
        if (lastEventId!=null) request.header("Last-Event-ID",lastEventId);
        if (client.cookie() != null) request.header("Cookie", client.cookie());
        if (client.token() != null) request.header("X-CSRF-Token", client.token());
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        HttpResponse<String> response = http.send(
                request.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (java.util.Arrays.stream(Thread.currentThread().getStackTrace())
                .anyMatch(frame -> java.util.Set.of("configurationRebuildPublicationAndHistoryStayIndependent", "conversationHistoryScopeAndReferenceReadsStayBound", "conversationCancellationDeletionAndLateResultsStayIsolated", "groundedQuestionSharedQueueAndEventCapacityAreBounded", "groundedQuestionBudgetsValidationAndSnapshotsStayBound", "groundedQuestionFixedVersionsCancellationAndEventsStaySafe", "searchInstanceMetadataAndRestartKeepOnlyConditions", "searchInstanceCancellationDeletionAndLateResultsStayIsolated", "multiSearchScopesFusionAndCostsAreExplicit", "multiSearchLatestRequestAndQueueStayAtomic", "publicationAndChecksKeepIndependentStates", "runningSearchAndRebuildCancellationRejectLateResults", "contentSwitchHistoryAndCancellationRemainBoundToVersions", "associationUnionExclusionsAndLateTasksStayConsistent", "preflightAndRebuildKeepVersionsAtomic", "publicContractBranchesRemainReachable", "markdownLocationsRemainBoundToOriginalSource", "pdfLocationsKeepCrossPageSourceAndBlankPages").contains(frame.getMethodName()))) {
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("path", response.request().uri().getRawPath() + (response.request().uri().getRawQuery() == null ? "" : "?" + response.request().uri().getRawQuery()));
            item.put("method", method);
            item.put("status", response.statusCode());
            item.put(
                    "contentType", response.headers().firstValue("Content-Type").orElse(""));
            if (response.statusCode() == 204) assertThat(response.body()).isEmpty();
            boolean binary = response.statusCode() == 200 && (path.endsWith("/download") || path.endsWith("/preview"));
            boolean stream=response.statusCode()==200 && path.contains("/events");
            item.put("response", response.statusCode() == 204 ? null : binary || stream ? response.body() : JSON.readTree(response.body()));
            java.util.Map<String,String> responseHeaders = new java.util.LinkedHashMap<>();
            response.headers().map().forEach((name,values) -> responseHeaders.put(name,values.getFirst()));
            item.put("responseHeaders",responseHeaders);
            item.put("body", body);
            java.util.Map<String, String> parameters = new java.util.LinkedHashMap<>();
            response.request().headers().map().forEach((name, values) -> parameters.put(name, values.getFirst()));
            item.put("parameters", parameters);
            String behavior = java.util.Arrays.stream(Thread.currentThread().getStackTrace()).map(StackTraceElement::getMethodName).filter(name -> java.util.Set.of("configurationRebuildPublicationAndHistoryStayIndependent", "conversationHistoryScopeAndReferenceReadsStayBound", "conversationCancellationDeletionAndLateResultsStayIsolated", "groundedQuestionSharedQueueAndEventCapacityAreBounded", "groundedQuestionBudgetsValidationAndSnapshotsStayBound", "groundedQuestionFixedVersionsCancellationAndEventsStaySafe", "searchInstanceMetadataAndRestartKeepOnlyConditions", "searchInstanceCancellationDeletionAndLateResultsStayIsolated", "multiSearchScopesFusionAndCostsAreExplicit", "multiSearchLatestRequestAndQueueStayAtomic", "publicationAndChecksKeepIndependentStates", "runningSearchAndRebuildCancellationRejectLateResults", "contentSwitchHistoryAndCancellationRemainBoundToVersions", "associationUnionExclusionsAndLateTasksStayConsistent", "preflightAndRebuildKeepVersionsAtomic", "markdownLocationsRemainBoundToOriginalSource", "pdfLocationsKeepCrossPageSourceAndBlankPages").contains(name)).findFirst().orElse("publicContractBranchesRemainReachable");
            item.put("behavior", behavior);
            if (behavior.startsWith("conversation")) item.put("behaviorChecks",java.util.List.of("conversationVersions","conversationPublication","conversationCost"));
            if (behavior.startsWith("groundedQuestion")) item.put("behaviorChecks", java.util.List.of("questionVersions", "questionPublication", "questionCost", "questionEvents", "questionEventCursor", "questionEventReconnect"));
            if (behavior.startsWith("searchInstance")) item.put("behaviorChecks", java.util.List.of("instanceLifecycleCost", "instanceLifecycleVersions"));
            if (behavior.startsWith("multiSearch")) item.put("behaviorChecks", java.util.List.of("multiSearchVersions", "multiSearchPublication", "multiSearchCost"));
            if (behavior.equals("publicationAndChecksKeepIndependentStates")) item.put("behaviorChecks", java.util.List.of("publicationStates", "checkDoesNotRepeatCalls"));
            if (behavior.equals("configurationRebuildPublicationAndHistoryStayIndependent")) item.put("behaviorChecks",java.util.List.of("configurationRebuildVersions","configurationRebuildPublication"));
            if (behavior.equals("preflightAndRebuildKeepVersionsAtomic")) item.put("behaviorChecks", java.util.List.of("processingConfigurationSwitch"));
            if (behavior.equals("contentSwitchHistoryAndCancellationRemainBoundToVersions") || behavior.equals("runningSearchAndRebuildCancellationRejectLateResults")) item.put("behaviorChecks", java.util.List.of("contentVersionSwitch"));
            GAP_OBSERVATIONS.add(item);
        }
        return response;
    }

    private Client client(ConfigurableApplicationContext app) throws Exception {
        String base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port") + "/api/v1";
        HttpResponse<String> response = send(new Client(base, null, null), "/session", "GET", null, null);
        return new Client(
                base,
                response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                JSON.readTree(response.body()).get("csrfToken").asText());
    }

    private HttpServer indexedVector(java.util.List<String> inputs) throws Exception {
        return indexedVector(inputs, () -> {});
    }

    private HttpServer indexedVector(java.util.List<String> inputs, Runnable beforeEmbedding) throws Exception {
        return indexedVector(inputs, beforeEmbedding, () -> {});
    }

    private HttpServer indexedVector(java.util.List<String> inputs, Runnable beforeEmbedding, Runnable beforeQuery)
            throws Exception {
        return indexedVector(inputs, beforeEmbedding, beforeQuery, "4");
    }

    private HttpServer indexedVector(
            java.util.List<String> inputs, Runnable beforeEmbedding, Runnable beforeQuery, String usage)
            throws Exception {
        return indexedVector(inputs, beforeEmbedding, beforeQuery, usage, hits -> {});
    }

    private HttpServer indexedVector(
            java.util.List<String> inputs,
            Runnable beforeEmbedding,
            Runnable beforeQuery,
            String usage,
            java.util.function.Consumer<tools.jackson.databind.node.ArrayNode> adjustHits)
            throws Exception {
        return indexedVector(inputs,beforeEmbedding,beforeQuery,usage,adjustHits,null);
    }

    private HttpServer indexedVector(java.util.List<String> inputs,Runnable beforeEmbedding,Runnable beforeQuery,String usage,
            java.util.function.Consumer<tools.jackson.databind.node.ArrayNode> adjustHits,java.util.function.Function<JsonNode,JsonNode> chat) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(command -> Thread.ofVirtual().start(command));
        java.util.Set<String> collections = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.Map<String, JsonNode> points = new java.util.concurrent.ConcurrentHashMap<>();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] request = exchange.getRequestBody().readAllBytes();
            JsonNode body = request.length == 0 ? JSON.createObjectNode() : JSON.readTree(request);
            String response;
            int status = 200;
            if (path.equals("/v1/chat/completions") && chat!=null) {
                response=chat.apply(body).toString();
            } else if (path.equals("/v1/embeddings")) {
                inputs.add(body.at("/input/0").asText());
                beforeEmbedding.run();
                String usageBody =
                        usage.equals("missing") ? "{}" : "{\"prompt_tokens\":" + usage + ",\"total_tokens\":4}";
                response =
                        "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[1,0,0]}],\"model\":\"text-embedding-v4\",\"usage\":"
                                + usageBody + "}";
            } else if (path.endsWith("/points") && exchange.getRequestMethod().equals("PUT")) {
                for (JsonNode point : body.path("points"))
                    points.put(point.path("id").asText(), point);
                response = "{\"status\":\"ok\",\"result\":{\"status\":\"completed\"}}";
            } else if (path.endsWith("/points") && exchange.getRequestMethod().equals("POST")) {
                var result = JSON.createArrayNode();
                for (JsonNode id : body.path("ids"))
                    if (points.containsKey(id.asText())) {
                        var point = (tools.jackson.databind.node.ObjectNode)
                                points.get(id.asText()).deepCopy();
                        var normalized = JSON.createArrayNode();
                        for (JsonNode component : point.path("vector")) {
                            if (component.doubleValue() == Math.rint(component.doubleValue()))
                                normalized.add(component.intValue());
                            else normalized.add(component.doubleValue());
                        }
                        point.set("vector", normalized);
                        result.add(point);
                    }
                response = JSON.writeValueAsString(java.util.Map.of("status", "ok", "result", result));
            } else if (path.endsWith("/points/query")) {
                beforeQuery.run();
                var result = JSON.createArrayNode();
                for (JsonNode point : points.values())
                    for (JsonNode index : body.at("/filter/must/0/match/any"))
                        if (point.at("/payload/index_id").asText().equals(index.asText()))
                            result.add(((tools.jackson.databind.node.ObjectNode) point.deepCopy()).put("score", 0.9));
                adjustHits.accept(result);
                response = JSON.writeValueAsString(
                        java.util.Map.of("status", "ok", "result", java.util.Map.of("points", result)));
            } else if (path.endsWith("/points/delete")) {
                String index = body.at("/filter/must/0/match/value").asText();
                points.entrySet().removeIf(entry -> entry.getValue().at("/payload/index_id").asText().equals(index));
                response = "{\"status\":\"ok\",\"result\":{\"status\":\"completed\"}}";
            } else if (exchange.getRequestMethod().equals("PUT")) {
                collections.add(path);
                response = "{\"status\":\"ok\",\"result\":true}";
            } else if (collections.contains(path)) {
                response =
                        "{\"status\":\"ok\",\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":3,\"distance\":\"Cosine\"}}}}}";
            } else {
                status = 404;
                response = "{}";
            }
            byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private ConfigurableApplicationContext start(Path directory) {
        ConfigurableApplicationContext app = new SpringApplication(KnowledgePoolApplication.class)
                .run("--server.port=0", "--know-pool.data-directory=" + directory);
        if (GAP_ROUTES.isEmpty())
            app.getBean(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class)
                    .getHandlerMethods()
                    .forEach((mapping, handler) -> {
                        for (String path : mapping.getPatternValues())
                            for (var method : mapping.getMethodsCondition().getMethods())
                                GAP_ROUTES.add(java.util.Map.of("method", method.name(), "path", path));
                    });
        return app;
    }

    @org.junit.jupiter.api.AfterAll
    static void gapEvidence() throws Exception {
        if (!gapsPassed && !documentPassed && !pdfPassed && !previewPassed && !associationsPassed && !versionsPassed && !publicationPassed && !multiSearchPassed && !instancesPassed && !questionsPassed) return;
        GAP_OBSERVATIONS.getFirst().put("routes", GAP_ROUTES);
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".."))
                .toAbsolutePath()
                .normalize();
        Process process = new ProcessBuilder(
                        "python",
                        "server/src/test/resources/contract/settings-evidence.py",
                        root.toString(),
                        "indexing-gaps")
                .directory(root.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        try (var output = process.getOutputStream()) {
            output.write(JSON.writeValueAsBytes(GAP_OBSERVATIONS));
        }
        String result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(result).isZero();
    }

    private record Client(String base, String cookie, String token) {}
}
