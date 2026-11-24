package assignment.browser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Keeps screenshots and downloads under the report folder. */
public final class RunPaths {
    private final Path root;

    public RunPaths(Path root) throws IOException {
        this.root = root;
        Files.createDirectories(root);
    }

    public Path screenshotDirectory(String scenarioId, String scenarioName) throws IOException {
        // One folder per scenario, named after it, e.g. screenshots/S01-download-latest-transcript.
        Path directory = root.resolve("screenshots").resolve(scenarioId + "-" + safeName(scenarioName));
        Files.createDirectories(directory);
        // Remove screenshots from an earlier run so the report links only this run's evidence.
        try (var earlier = Files.list(directory)) {
            for (Path file : earlier.filter(path -> path.getFileName().toString().endsWith(".png")).toList()) {
                Files.delete(file);
            }
        }
        return directory;
    }

    // Give each run a separate download folder.
    public Path freshDownloadDirectory() throws IOException {
        Files.createDirectories(root.resolve("downloads"));
        return Files.createTempDirectory(root.resolve("downloads"), "suite-").toAbsolutePath();
    }

    public static String safeName(String value) {
        // Avoid characters that are unsafe in filenames on common presentation systems.
        String safe = value.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return safe.isBlank() ? "unnamed" : safe;
    }
}
