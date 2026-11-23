package de.agiehl.monitor;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.function.UnaryOperator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
final class StateStore implements AutoCloseable {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Connection database;
    private MonitorState current;

    StateStore(@Value("${monitor.data-dir:./data}") String dataDirectory) throws IOException {
        Path directory = Files.createDirectories(Path.of(dataDirectory).toAbsolutePath().normalize());
        lockChannel = FileChannel.open(directory.resolve("monitor.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        Connection opened = null;
        try {
            lock = lockDirectory(directory);
            Path file = directory.resolve("monitor");
            opened = DriverManager.getConnection("jdbc:h2:file:" + file + ";WRITE_DELAY=0", "sa", "");
            current = load(opened, directory.resolve("state.json"));
            database = opened;
        } catch (SQLException | IOException | RuntimeException failure) {
            closeAfterFailure(opened, failure);
            closeAfterFailure(lockChannel, failure);
            if (failure instanceof SQLException sqlFailure) {
                throw new IOException("Could not open the monitor database at " + directory, sqlFailure);
            }
            if (failure instanceof IOException ioFailure) {
                throw ioFailure;
            }
            throw (RuntimeException) failure;
        }
    }

    synchronized MonitorState snapshot() {
        return current;
    }

    synchronized void update(UnaryOperator<MonitorState> change) throws IOException {
        MonitorState next = change.apply(current);
        if (next == current) {
            return;
        }
        try {
            save(database, next);
            current = next;
        } catch (SQLException failure) {
            throw new IOException("Could not save the monitor state", failure);
        }
    }

    private MonitorState load(Connection connection, Path legacyFile) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS monitor_state (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        payload BINARY LARGE OBJECT NOT NULL
                    )
                    """);
            try (var rows = statement.executeQuery("SELECT payload FROM monitor_state WHERE id = 1")) {
                if (rows.next()) {
                    return mapper.readValue(rows.getBytes(1), MonitorState.class);
                }
            }
        }
        MonitorState initial = Files.notExists(legacyFile) ? MonitorState.initial()
                : mapper.readValue(legacyFile.toFile(), MonitorState.class);
        save(connection, initial);
        return initial;
    }

    private void save(Connection connection, MonitorState state) throws SQLException {
        try (var statement = connection.prepareStatement("MERGE INTO monitor_state (id, payload) VALUES (1, ?)")) {
            statement.setBytes(1, mapper.writeValueAsBytes(state));
            statement.executeUpdate();
        }
    }

    private FileLock lockDirectory(Path directory) throws IOException {
        try {
            FileLock acquired = lockChannel.tryLock();
            if (acquired == null) {
                throw new IOException("Data directory is already in use: " + directory);
            }
            return acquired;
        } catch (OverlappingFileLockException failure) {
            throw new IOException("Data directory is already in use: " + directory, failure);
        }
    }

    private static void closeAfterFailure(AutoCloseable resource, Throwable failure) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception suppressed) {
                failure.addSuppressed(suppressed);
            }
        }
    }

    @Override
    public synchronized void close() throws IOException {
        try (lockChannel; lock) {
            database.close();
        } catch (SQLException failure) {
            throw new IOException("Could not close the monitor database", failure);
        }
    }
}
