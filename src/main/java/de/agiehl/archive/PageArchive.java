package de.agiehl.archive;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;

@Component
public final class PageArchive {
    private final Path archive;
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    public PageArchive(@Value("${monitor.data-dir:./data}") Path dataDirectory) {
        archive = dataDirectory.resolve("archive");
    }

    public void save(String schoolId, URI articleUri, String revisionId)
            throws IOException, InterruptedException {
        if (!schoolId.matches("[A-Za-z0-9_-]+") || !revisionId.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Ungültiger Archivschlüssel");
        }
        Path school = Files.createDirectories(archive.resolve(schoolId));
        Path target = school.resolve(revisionId);
        if (Files.isRegularFile(target.resolve("manifest.tsv"))) {
            return;
        }
        Path stage = Files.createTempDirectory(school, ".capture-");
        try {
            new ArchiveCapture(client, stage).write(articleUri);
            try {
                publish(stage, target);
            } catch (IOException failure) {
                if (!Files.isRegularFile(target.resolve("manifest.tsv"))) {
                    throw failure;
                }
            }
        } finally {
            if (Files.exists(stage)) {
                try (var paths = Files.walk(stage)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
    }

    private void publish(Path stage, Path target) throws IOException {
        try {
            Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException _) {
            Files.move(stage, target);
        }
    }
}
