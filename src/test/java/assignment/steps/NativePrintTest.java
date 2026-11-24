package assignment.steps;

import assignment.browser.BrowserSession;
import assignment.data.LocatorDefinition;
import assignment.data.StepDefinition;
import java.nio.file.Files;
import java.util.Map;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.asserts.SoftAssert;

/** Opt-in visible macOS test. Keep the keyboard and mouse idle while it runs. */
public class NativePrintTest {
    @Test
    public void nativeContextMenuProducesPdf() throws Exception {
        // Use a local page and temporary files; no account login is needed.
        var root = Files.createTempDirectory("native-print-test-");
        var downloads = Files.createDirectory(root.resolve("downloads"));
        String previous = System.getProperty("headless");
        try (var browser = BrowserSession.start(downloads, false)) {
            System.setProperty("headless", "false");
            System.out.println("Chrome process: " + ((org.openqa.selenium.HasCapabilities) browser.driver()).getCapabilities().getCapability("goog:processID"));
            // Like the Banner transcript, the page has its own "Print" link that must be ignored.
            browser.driver().get("data:text/html,<body style='min-height:900px'>"
                + "<div style='text-align:right;font-size:18px;padding:20px 40px'>Print</div>"
                + "<h1>Native context menu print verification</h1></body>");
            var executor = new StepExecutor(browser.driver(), downloads, root.resolve("screenshots"),
                Map.of("pdf", "native-print-test.pdf"), Map.of("page", new LocatorDefinition("css", "body")));
            executor.execute(new StepDefinition("fixture", 1, "CONTEXT_CLICK", "page", "", "", 10), new SoftAssert());
            String result = executor.execute(new StepDefinition("fixture", 2, "PRINT_TO_PDF", "", "pdf", "", 30), new SoftAssert());
            System.out.println("Print step: " + result);
            Assert.assertTrue(result.contains("native context-menu"));
            // This checks file creation, not transcript text or an authenticated workflow.
            Assert.assertTrue(Files.size(downloads.resolve("native-print-test.pdf")) > 5);
            System.out.println("Native menu PDF verified: " + downloads.resolve("native-print-test.pdf"));
        } finally {
            if (previous == null) System.clearProperty("headless"); else System.setProperty("headless", previous);
        }
    }
}
