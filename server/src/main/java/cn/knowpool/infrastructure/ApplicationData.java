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
            if (version > 4) {
                throw new IOException("数据表版本高于当前应用，已停止业务写入。");
            }
            if (version > 0 && version < 4) {
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
