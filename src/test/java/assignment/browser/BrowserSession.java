package assignment.browser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

/** Starts and closes the Chrome session shared by the five scenarios. */
public final class BrowserSession implements AutoCloseable {
    private final WebDriver driver;

    private BrowserSession(WebDriver driver) {
        this.driver = driver;
    }

    public static BrowserSession start(Path downloadDirectory, boolean headless) throws IOException {
        // Start with an empty folder so old downloads cannot satisfy this run.
        Files.createDirectories(downloadDirectory);
        try (var contents = Files.list(downloadDirectory)) {
            if (contents.findAny().isPresent()) {
                throw new IllegalStateException("Download directory must be empty: " + downloadDirectory);
            }
        }
        // Send downloads and saved PDFs to the same run folder.
        Map<String, Object> preferences = new HashMap<>();
        preferences.put("download.default_directory", downloadDirectory.toAbsolutePath().toString());
        preferences.put("download.prompt_for_download", false);
        preferences.put("download.directory_upgrade", true);
        preferences.put("savefile.default_directory", downloadDirectory.toAbsolutePath().toString());
        // Choose Save as PDF instead of a physical printer.
        preferences.put("printing.print_preview_sticky_settings.appState", """
            {"recentDestinations":[{"id":"Save as PDF","origin":"local","account":""}],
             "selectedDestinationId":"Save as PDF","version":2}
            """);
        preferences.put("safebrowsing.enabled", true);
        var options = new ChromeOptions();
        options.setExperimentalOption("prefs", preferences);
        options.addArguments("--window-size=1440,1000", "--disable-search-engine-choice-screen",
            "--disable-notifications");
        if (headless) options.addArguments("--headless=new");
        // Windows saves without a print dialog; macOS uses the visible menu and dialog.
        if (!headless && System.getProperty("os.name", "").toLowerCase().contains("win")) {
            options.addArguments("--kiosk-printing");
        }
        return new BrowserSession(new ChromeDriver(options));
    }

    public WebDriver driver() { return driver; }

    @Override public void close() {
        driver.quit();
    }
}
