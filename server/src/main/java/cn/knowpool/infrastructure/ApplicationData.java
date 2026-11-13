package cn.knowpool.infrastructure;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public final class ApplicationData implements AutoCloseable {
    private final Path directory;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final DriverManagerDataSource dataSource;
    private final Instant startedAt = Instant.now();

    public ApplicationData(@Value("${know-pool.data-directory}") String configuredDirectory) throws IOException {
        Path requested = Path.of(configuredDirectory).toAbsolutePath().normalize();
        if (requested.toString().startsWith("\\\\") || requested.toString().startsWith("//")) {
            throw new IOException("数据目录必须位于本机磁盘。");
        }
        Files.createDirectories(requested);
        directory = requested.toRealPath();
        lockChannel = FileChannel.open(
                directory.resolve("application.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = lockChannel.tryLock();
            if (lock == null) {
                throw new IOException("数据目录已由另一应用进程使用。");
            }
        } catch (IOException | OverlappingFileLockException failure) {
            lockChannel.close();
            throw new IOException("数据目录已由另一应用进程使用。", failure);
        }
        dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl(
                "jdbc:sqlite:" + directory.resolve("application.sqlite") + "?foreign_keys=on&busy_timeout=5000");
        try {
            JdbcClient jdbc = JdbcClient.create(dataSource);
            jdbc.sql("PRAGMA journal_mode=WAL").query(String.class).single();
            int version = jdbc.sql("PRAGMA user_version").query(Integer.class).single();
            if (version > 12) {
                throw new IOException("数据表版本高于当前应用，已停止业务写入。");
            }
            if (version > 0 && version < 12) {
                Path backups = directory.resolve("schema-backups");
                Files.createDirectories(backups);
                Path backup = backups.resolve("schema-" + version + "-" + java.util.UUID.randomUUID() + ".sqlite");
                jdbc.sql("VACUUM INTO '" + backup.toString().replace("'", "''") + "'")
                        .update();
            }
            if (version < 3) {
                write(transaction -> {
                    if (version < 2) {
                        transaction
                                .sql(
                                        "CREATE TABLE IF NOT EXISTS application_metadata (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
                                .update();
                        transaction
                                .sql("INSERT OR IGNORE INTO application_metadata(name, value) VALUES ('created_at', ?)")
                                .param(startedAt.toString())
                                .update();
                        transaction
                                .sql(
                                        "CREATE TABLE datasets (id TEXT PRIMARY KEY, name TEXT NOT NULL, name_key TEXT NOT NULL UNIQUE, description TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)")
                                .update();
                    }
                    transaction
                            .sql(
                                    "CREATE TABLE files (id TEXT PRIMARY KEY, dataset_id TEXT NOT NULL REFERENCES datasets(id), name TEXT NOT NULL, name_key TEXT NOT NULL, created_by TEXT, created_at TEXT NOT NULL, uploaded_at TEXT NOT NULL, updated_at TEXT NOT NULL, latest_version TEXT NOT NULL, UNIQUE(dataset_id,name_key))")
                            .update();
                    transaction
                            .sql(
                                    "CREATE TABLE file_versions (id TEXT PRIMARY KEY, file_id TEXT NOT NULL REFERENCES files(id), uploaded_name TEXT NOT NULL, file_type TEXT NOT NULL, media_type TEXT NOT NULL, size_bytes INTEGER NOT NULL, content_hash TEXT NOT NULL, uploaded_by TEXT, uploaded_at TEXT NOT NULL, relative_path TEXT NOT NULL, available INTEGER NOT NULL)")
                            .update();
                    transaction.sql("PRAGMA user_version=3").update();
                    return null;
                });
            }
            if (version < 4) {
                write(transaction -> {
                    transaction
                            .sql(
                                    "CREATE TABLE model_configs (id TEXT PRIMARY KEY, kind TEXT NOT NULL, endpoint TEXT, model TEXT, context_tokens INTEGER, output_tokens INTEGER, credential TEXT, updated_at TEXT NOT NULL)")
                            .update();
                    transaction
                            .sql(
                                    "CREATE TABLE active_configs (kind TEXT PRIMARY KEY, config_id TEXT NOT NULL REFERENCES model_configs(id))")
                            .update();
                    transaction
                            .sql(
                                    "CREATE TABLE vector_config (id INTEGER PRIMARY KEY CHECK(id=1), endpoint TEXT, credential TEXT, updated_at TEXT NOT NULL)")
                            .update();
                    for (String kind : List.of("generation", "embedding")) {
                        String id = java.util.UUID.randomUUID().toString();
                        transaction
                                .sql("INSERT INTO model_configs(id,kind,updated_at) VALUES(?,?,?)")
                                .params(id, kind, startedAt.toString())
                                .update();
                        transaction
                                .sql("INSERT INTO active_configs(kind,config_id) VALUES(?,?)")
                                .params(kind, id)
                                .update();
                    }
                    transaction
                            .sql("INSERT INTO vector_config(id,updated_at) VALUES(1,?)")
                            .param(startedAt.toString())
                            .update();
                    transaction
                            .sql(
                                    "CREATE TABLE tasks (id TEXT PRIMARY KEY, kind TEXT NOT NULL, state TEXT NOT NULL, payload TEXT NOT NULL, result TEXT, session_id TEXT NOT NULL, operation TEXT NOT NULL, idempotency_key TEXT NOT NULL, request_fingerprint TEXT NOT NULL, UNIQUE(session_id,operation,idempotency_key))")
                            .update();
                    transaction.sql("PRAGMA user_version=4").update();
                    return null;
                });
            }
            if (version < 5) {
                write(transaction -> {
                    transaction
                            .sql(
                                    "CREATE TABLE knowledge_bases (id TEXT PRIMARY KEY, name_key TEXT NOT NULL UNIQUE, visible INTEGER NOT NULL, payload TEXT NOT NULL, config TEXT NOT NULL)")
                            .update();
                    transaction
                            .sql(
                                    "CREATE TABLE knowledge_base_creations (task_id TEXT PRIMARY KEY REFERENCES tasks(id), knowledge_base_id TEXT NOT NULL, collection_name TEXT NOT NULL, endpoint TEXT NOT NULL, ownership TEXT NOT NULL, phase TEXT NOT NULL, datasets TEXT NOT NULL, cleanup_error TEXT, cleanup_attempts INTEGER NOT NULL DEFAULT 0)")
                            .update();
                    transaction
                            .sql(
                                    "CREATE TABLE knowledge_base_sources (id TEXT PRIMARY KEY, knowledge_base_id TEXT NOT NULL REFERENCES knowledge_bases(id), type TEXT NOT NULL, resource_id TEXT NOT NULL, payload TEXT NOT NULL, UNIQUE(knowledge_base_id,type,resource_id))")
                            .update();
                    transaction
                            .sql(
                                    "CREATE TABLE knowledge_base_files (knowledge_base_id TEXT NOT NULL REFERENCES knowledge_bases(id), file_id TEXT NOT NULL, content_version_id TEXT NOT NULL, config_version_id TEXT NOT NULL, task_id TEXT NOT NULL REFERENCES tasks(id), PRIMARY KEY(knowledge_base_id,file_id))")
                            .update();
                    transaction.sql("PRAGMA user_version=5").update();
                    return null;
                });
            }
            if (version < 6) {
                write(transaction -> {
                    transaction
                            .sql(
                                    "CREATE TABLE index_runs (task_id TEXT PRIMARY KEY REFERENCES tasks(id),id TEXT NOT NULL UNIQUE,phase TEXT NOT NULL)")
                            .update();
                    transaction
                            .sql(
                                    "CREATE TABLE index_chunks (id TEXT PRIMARY KEY,index_id TEXT NOT NULL REFERENCES index_runs(id),ordinal INTEGER NOT NULL,text TEXT NOT NULL,location TEXT NOT NULL,vector TEXT,input_upper_bound INTEGER,prompt_tokens INTEGER,meter_version TEXT,write_attempts INTEGER NOT NULL DEFAULT 0,embed_attempts INTEGER NOT NULL DEFAULT 0,verify_attempts INTEGER NOT NULL DEFAULT 0,write_completed INTEGER NOT NULL DEFAULT 0,written INTEGER NOT NULL DEFAULT 0)")
                            .update();
                    transaction
                            .sql(
                                    "CREATE TABLE effective_files (knowledge_base_id TEXT NOT NULL,file_id TEXT NOT NULL,content_version_id TEXT NOT NULL,config_version_id TEXT NOT NULL,index_id TEXT NOT NULL,PRIMARY KEY(knowledge_base_id,file_id))")
                            .update();
                    transaction
                            .sql(
                                    "CREATE TABLE index_switches (id TEXT PRIMARY KEY,task_id TEXT NOT NULL UNIQUE,knowledge_base_id TEXT NOT NULL,file_id TEXT NOT NULL,previous_content_id TEXT,content_id TEXT NOT NULL,previous_index_id TEXT,index_id TEXT NOT NULL,config_id TEXT NOT NULL,switched_at TEXT NOT NULL)")
                            .update();
                    transaction
                            .sql(
                                    "CREATE TABLE task_events (task_id TEXT PRIMARY KEY,sequence INTEGER NOT NULL,payload TEXT NOT NULL)")
                            .update();
                    transaction
                            .sql("CREATE TABLE search_identities (id TEXT PRIMARY KEY)")
                            .update();
                    transaction
                            .sql(
                                    "CREATE TABLE search_evidence_identities (id TEXT PRIMARY KEY,request_id TEXT NOT NULL REFERENCES search_identities(id))")
                            .update();
                    var eventJson =
                            tools.jackson.databind.json.JsonMapper.builder().build();
                    for (String task : transaction
                            .sql("SELECT payload FROM tasks")
                            .query(String.class)
                            .list()) {
                        TaskEvents.record(transaction, eventJson, eventJson.readTree(task));
                    }
                    transaction.sql("PRAGMA user_version=6").update();
                    return null;
                });
            }
            if (version < 7) {
                write(transaction -> {
                    transaction.sql("ALTER TABLE index_runs ADD COLUMN parser_version TEXT NOT NULL DEFAULT 'plain-text-08'").update();
                    transaction.sql("ALTER TABLE index_runs ADD COLUMN source_map TEXT NOT NULL DEFAULT '{}'").update();
                    transaction.sql("ALTER TABLE index_runs ADD COLUMN config_snapshot TEXT NOT NULL DEFAULT '{}'").update();
                    transaction.sql("ALTER TABLE index_chunks ADD COLUMN source_range TEXT NOT NULL DEFAULT '{}'").update();
                    transaction.sql("PRAGMA user_version=7").update();
                    return null;
                });
            }
            if (version < 8) {
                write(transaction -> {
                    transaction.sql("ALTER TABLE index_runs ADD COLUMN preflight TEXT").update();
                    transaction.sql("CREATE TABLE processing_config_history(id TEXT PRIMARY KEY,knowledge_base_id TEXT NOT NULL,payload TEXT NOT NULL,collection_name TEXT NOT NULL)").update();
                    transaction.sql("INSERT INTO processing_config_history SELECT json_extract(config,'$.id'),id,config,'kp_'||replace(id,'-','') FROM knowledge_bases").update();
                    transaction.sql("CREATE TABLE processing_rebuilds(task_id TEXT PRIMARY KEY,knowledge_base_id TEXT NOT NULL,config_version_id TEXT NOT NULL,ready INTEGER NOT NULL DEFAULT 0)").update();
                    transaction.sql("CREATE TABLE staged_indexes(task_id TEXT PRIMARY KEY,knowledge_base_id TEXT NOT NULL,file_id TEXT NOT NULL,content_version_id TEXT NOT NULL,config_version_id TEXT NOT NULL,index_id TEXT NOT NULL)").update();
                    transaction.sql("PRAGMA user_version=8").update();
                    return null;
                });
            }
            if (version < 9) {
                write(transaction -> {
                    transaction.sql("CREATE TABLE knowledge_base_exclusions(knowledge_base_id TEXT NOT NULL,file_id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(knowledge_base_id,file_id))").update();
                    transaction.sql("CREATE TABLE index_cleanup(task_id TEXT PRIMARY KEY,targets TEXT NOT NULL,endpoint TEXT)").update();
                    transaction.sql("PRAGMA user_version=9").update();
                    return null;
                });
            }
            if (version < 10) {
                write(transaction -> {
                    transaction.sql("CREATE TABLE file_version_cleanup(version_id TEXT PRIMARY KEY,relative_path TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0)").update();
                    transaction.sql("PRAGMA user_version=10").update();
                    return null;
                });
            }
            if (version < 11) {
                write(transaction -> {
                    transaction.sql("CREATE TABLE knowledge_base_check_keys(session_id TEXT NOT NULL,knowledge_base_id TEXT NOT NULL,idempotency_key TEXT NOT NULL,task_id TEXT NOT NULL,PRIMARY KEY(session_id,knowledge_base_id,idempotency_key))").update();
                    transaction.sql("PRAGMA user_version=11").update();
                    return null;
                });
            }
            if (version < 12) {
                write(transaction -> {
                    transaction.sql("CREATE TABLE search_instances(id TEXT PRIMARY KEY,payload TEXT NOT NULL)").update();
                    transaction.sql("PRAGMA user_version=12").update();
                    return null;
                });
            }
        } catch (RuntimeException | IOException failure) {
            close();
            throw new IOException("数据目录初始化失败，已停止业务写入。", failure);
        }
    }

    public <T> T read(Function<JdbcClient, T> action) {
        return action.apply(JdbcClient.create(dataSource));
    }

    public Path directory() {
        return directory;
    }

    public synchronized <T> T write(Function<JdbcClient, T> action) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .execute(status -> action.apply(JdbcClient.create(dataSource)));
    }

    public synchronized Status status() {
        int version = JdbcClient.create(dataSource)
                .sql("PRAGMA user_version")
                .query(Integer.class)
                .single();
        return new Status("0.1.0", startedAt.toString(), "ready", directory.toString(), version, List.of());
    }

    @PreDestroy
    @Override
    public void close() throws IOException {
        if (lock.isValid()) {
            lock.release();
        }
        lockChannel.close();
    }

    public record Status(
            String applicationVersion,
            String startedAt,
            String writeState,
            String dataDirectory,
            int schemaVersion,
            List<String> warnings) {
        public Status {
            warnings = List.copyOf(warnings);
        }
    }
}
