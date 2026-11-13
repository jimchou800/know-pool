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
            if (version > 2) {
                throw new IOException("数据表版本高于当前应用，已停止业务写入。");
            }
            if (version == 1) {
                Path backups = directory.resolve("schema-backups");
                Files.createDirectories(backups);
                Path backup = backups.resolve("schema-1-" + java.util.UUID.randomUUID() + ".sqlite");
                jdbc.sql("VACUUM INTO '" + backup.toString().replace("'", "''") + "'")
                        .update();
            }
            if (version < 2) {
                write(transaction -> {
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
                    transaction.sql("PRAGMA user_version=2").update();
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
