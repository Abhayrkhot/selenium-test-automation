package assignment.steps;

import assignment.browser.BrowserSession;
import assignment.data.LocatorDefinition;
import assignment.data.StepDefinition;
import assignment.data.WorkbookData;
import assignment.reporting.AssignmentReportListener;
import assignment.tests.AssignmentScenarioTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.testng.Assert;
import org.testng.TestNG;
import org.testng.annotations.Test;
import org.testng.asserts.SoftAssert;

/** Local Chrome fixtures verify the framework, not the live Northeastern workflows. */
public class FrameworkRegressionTest {
    // Use local pages to check scenario order, the designed failure and screenshot links.
    @Test
    public void sequencingAndFailureScreenshots() throws Exception {
        Path temp = Files.createTempDirectory("assignment-fixture-");
        Path page = temp.resolve("page.html");
        var zipBytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(zipBytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("data.txt"));
            zip.write("Dataset fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Files.writeString(page, "<h1 id='ready'>Ready</h1><a id='download' download='fixture.zip'>Download</a>"
            + "<script>const bytes=Uint8Array.from(atob('" + java.util.Base64.getEncoder().encodeToString(zipBytes.toByteArray())
            + "'),c=>c.charCodeAt(0));document.getElementById('download').href=URL.createObjectURL(new Blob([bytes]));</script>");
        Path fixture = temp.resolve("fixture.xlsx");
        String original = System.getProperty("workbook");
        String headless = System.getProperty("headless");
        try (var in = Files.newInputStream(Path.of(original)); var book = WorkbookFactory.create(in)) {
            var data = book.getSheet("Data");
            var row = data.createRow(data.getLastRowNum() + 1);
            String[] input = {"fixture_url", page.toUri().toString(), "Local test only"};
            for (int c = 0; c < input.length; c++) row.createCell(c).setCellValue(input[c]);
            var locators = book.getSheet("Locators");
            row = locators.createRow(locators.getLastRowNum() + 1);
            String[] locator = {"fixture_ready", "id", "ready", "Local test only"};
            for (int c = 0; c < locator.length; c++) row.createCell(c).setCellValue(locator[c]);
            row = locators.createRow(locators.getLastRowNum() + 1);
            String[] downloadLocator = {"fixture_download", "id", "download", "Local download"};
            for (int c = 0; c < downloadLocator.length; c++) row.createCell(c).setCellValue(downloadLocator[c]);
            // Replace live actions only in the temporary test workbook.
            var steps = book.getSheet("Steps");
            for (int i = steps.getLastRowNum(); i > 0; i--) {
                if (steps.getRow(i) != null) steps.removeRow(steps.getRow(i));
            }
            int index = 1;
            for (var scenario : WorkbookData.load(Path.of(original)).scenarios()) {
                String id = scenario.get("scenario_id");
                boolean negative = id.equals("S04");
                String[][] records = {
                    {id, "1", "OPEN", "", "fixture_url", "", "2"},
                    {id, "2", negative ? "ASSERT_DOWNLOAD" : "ASSERT_VISIBLE",
                        negative ? "" : "fixture_ready", "",
                        negative ? "negative_expected_filename" : "", "1"}
                };
                if (negative) records = new String[][] {
                    {id, "1", "OPEN", "", "fixture_url", "", "2"},
                    {id, "2", "CLICK", "fixture_download", "", "", "2"},
                    {id, "3", "ASSERT_NEW_ZIP", "", "", "", "5"},
                    {id, "4", "ASSERT_DOWNLOAD", "", "", "negative_expected_filename", "1"}
                };
                for (String[] cells : records) {
                    row = steps.createRow(index++);
                    for (int c = 0; c < cells.length; c++) row.createCell(c).setCellValue(cells[c]);
                }
            }
            try (var out = Files.newOutputStream(fixture)) {
                book.write(out);
            }
        }
        try {
            System.setProperty("workbook", fixture.toString());
            System.setProperty("headless", "true");
            var testng = new TestNG();
            testng.setUseDefaultListeners(false);
            testng.setTestClasses(new Class<?>[]{AssignmentScenarioTest.class});
            testng.addListener(new AssignmentReportListener());
            testng.run();
            // The nested suite must fail once for the deliberate wrong-filename check.
            Assert.assertTrue(testng.hasFailure());
            Path reportFile = AssignmentReportListener.REPORT_ROOT.resolve("report.html");
            String report = Files.readString(reportFile);
            Files.writeString(reportFile, report.replace("<h1>Selenium assignment report</h1>",
                "<h1>Local fixture regression report</h1><p>Local pages only. Not live Northeastern evidence.</p>"));
            Assert.assertTrue(report.contains("Executed 5 scenarios: 4 passed and 1 failed."));
            Assert.assertTrue(report.contains("Update Academic Calendar"));
            Assert.assertTrue(report.contains("Expected downloaded file"));
            Assert.assertTrue(report.contains("Verified completed ZIP:"));
            Assert.assertTrue(report.contains("Designed failure"));
            var links = java.util.regex.Pattern.compile("href=\"([^\"]+\\.png)\"").matcher(report);
            int screenshotCount = 0;
            while (links.find()) {
                Assert.assertTrue(Files.isRegularFile(AssignmentReportListener.REPORT_ROOT.resolve(links.group(1))));
                screenshotCount++;
            }
            Assert.assertEquals(screenshotCount, 24);
        } finally {
            System.setProperty("workbook", original);
            if (headless == null) System.clearProperty("headless");
            else System.setProperty("headless", headless);
        }
    }

    // Exercise missing controls, changed dates, checkbox transitions and soft failures.
    @Test
    public void optionalControlsDatesCheckboxesAndSoftText() throws Exception {
        Path root = Files.createTempDirectory("assignment-controls-");
        Path downloads = Files.createDirectory(root.resolve("downloads"));
        try (var browser = BrowserSession.start(downloads, true)) {
            browser.driver().get("data:text/html,<input id='date' value='Nov 23, 2026'>"
                + "<input type='checkbox'><input type='checkbox' checked><button id='disabled' disabled>Disabled</button><h1 id='ready'>Ready</h1>");
            var executor = new StepExecutor(browser.driver(), downloads, root.resolve("screenshots"),
                Map.of("off", "false", "text", "Ready"), Map.of(
                    "date", new LocatorDefinition("id", "date"),
                    "missing", new LocatorDefinition("id", "absent"),
                    "checks", new LocatorDefinition("css", "input[type=checkbox]"),
                    "ready", new LocatorDefinition("id", "ready"),
                    "disabled", new LocatorDefinition("id", "disabled")));
            var verify = new SoftAssert();
            Assert.assertTrue(executor.execute(new StepDefinition("fixture", 1, "CLICK_IF_PRESENT",
                "missing", "", "", 1), verify).contains("absent"));
            Assert.expectThrows(IllegalStateException.class, () -> executor.execute(new StepDefinition("fixture", 7,
                "CLICK_IF_PRESENT", "disabled", "", "", 1), verify));
            executor.execute(new StepDefinition("fixture", 2, "VERIFY_TEXT", "missing", "", "text", 1), verify);
            executor.execute(new StepDefinition("fixture", 3, "ASSERT_VISIBLE", "ready", "", "", 1), verify);
            Assert.expectThrows(AssertionError.class, verify::assertAll);
            executor.execute(new StepDefinition("fixture", 4, "REMEMBER_VALUE", "date", "", "", 1), new SoftAssert());
            var changed = new StepDefinition("fixture", 5, "ASSERT_VALUE_CHANGED", "date", "", "", 1);
            Assert.expectThrows(AssertionError.class, () -> executor.execute(changed, new SoftAssert()));
            ((org.openqa.selenium.JavascriptExecutor) browser.driver()).executeScript(
                "document.getElementById('date').value='Nov 26, 2026'");
            executor.execute(changed, new SoftAssert());
            var checkbox = new StepDefinition("fixture", 6, "CHANGE_CHECKBOX", "checks", "off", "", 1);
            Assert.assertTrue(executor.execute(checkbox, new SoftAssert()).contains("true to false"));
            Assert.assertFalse(browser.driver().findElements(org.openqa.selenium.By.cssSelector("input[type=checkbox]"))
                .stream().anyMatch(org.openqa.selenium.WebElement::isSelected));
            Assert.expectThrows(IllegalStateException.class, () -> executor.execute(checkbox, new SoftAssert()));
        }
    }

    // Simulate a delayed launcher locally; no real credentials or Duo approval are used.
    @Test
    public void authenticationFollowsDelayedLauncherAndAutomaticRedirect() throws Exception {
        Path root = Files.createTempDirectory("assignment-auth-");
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hub", exchange -> {
            byte[] html = "<h1>Student Hub fixture</h1>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, html.length);
            try (var output = exchange.getResponseBody()) { output.write(html); }
        });
        server.start();
        try (var browser = BrowserSession.start(Files.createDirectory(root.resolve("downloads")), true)) {
            String target = "http://127.0.0.1:" + server.getAddress().getPort() + "/hub";
            Path launcher = root.resolve("launcher.html");
            Files.writeString(launcher, "<button id='launch' onclick=\"setTimeout(()=>window.open('"
                + target + "'),200)\">Student Hub</button>");
            browser.driver().get(launcher.toUri().toString());
            var executor = new StepExecutor(browser.driver(), root.resolve("downloads"), root.resolve("screenshots"),
                Map.of("student_hub_url", target), Map.of("launcher", new LocatorDefinition("id", "launch")));
            var step = new StepDefinition("fixture", 1, "WAIT_FOR_AUTHENTICATION", "launcher", "", "", 5);
            executor.execute(step, new SoftAssert());
            Assert.assertEquals(browser.driver().getCurrentUrl(), target);
            Assert.assertEquals(browser.driver().getWindowHandles().size(), 2);
            executor.execute(step, new SoftAssert());
            Assert.assertEquals(browser.driver().getWindowHandles().size(), 2);
        } finally { server.stop(0); }
    }

    // Reject unusable downloads before accepting a fresh ZIP.
    @Test
    public void zipPrerequisiteRejectsMissingOldInvalidAndPartialDownloads() throws Exception {
        Path root = Files.createTempDirectory("assignment-zip-");
        Path downloads = Files.createDirectory(root.resolve("downloads"));
        try (var browser = BrowserSession.start(downloads, true)) {
            browser.driver().get("data:text/html,<h1>Download test</h1>");
            writeZip(downloads.resolve("old.zip"));
            var executor = new StepExecutor(browser.driver(), downloads, root.resolve("screenshots"), Map.of(), Map.of());
            var step = new StepDefinition("fixture", 1, "ASSERT_NEW_ZIP", "", "", "", 1);
            Assert.expectThrows(AssertionError.class, () -> executor.execute(step, new SoftAssert()));
            Files.writeString(downloads.resolve("invalid.zip"), "Not an archive");
            Assert.expectThrows(AssertionError.class, () -> executor.execute(step, new SoftAssert()));
            writeZip(downloads.resolve("new.zip"));
            Files.writeString(downloads.resolve("new.zip.crdownload"), "partial");
            Assert.expectThrows(AssertionError.class, () -> executor.execute(step, new SoftAssert()));
            Files.delete(downloads.resolve("new.zip.crdownload"));
            Assert.assertTrue(executor.execute(step, new SoftAssert()).contains("new.zip"));
        }
    }

    private static void writeZip(Path file) throws Exception {
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("data.txt"));
            zip.write("Dataset fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    // Check exact visible counts, including duplicates and zero matches.
    @Test
    public void countAssertionUsesWorkbookExpectation() throws Exception {
        Path root = Files.createTempDirectory("count-assertion-");
        Path downloads = Files.createDirectory(root.resolve("downloads"));
        try (var browser = BrowserSession.start(downloads, true)) {
            var data = new java.util.HashMap<>(WorkbookData.load(
                Path.of(System.getProperty("workbook"))).dataValues());
            var executor = new StepExecutor(browser.driver(), downloads, root.resolve("screenshots"),
                data, Map.of("items", new LocatorDefinition("css", ".item")));
            var step = new StepDefinition("fixture", 1, "ASSERT_COUNT", "items", "", "event_expected_count", 1);
            browser.driver().get("data:text/html,<div class='item'>One</div>"
                + "<div class='item' style='display:none'>Two</div>");
            executor.execute(step, new SoftAssert());
            var js = (org.openqa.selenium.JavascriptExecutor) browser.driver();
            js.executeScript("document.querySelectorAll('.item')[1].style.display = 'block'");
            var duplicate = Assert.expectThrows(AssertionError.class,
                () -> executor.execute(step, new SoftAssert()));
            Assert.assertTrue(duplicate.getMessage().contains("Visible count mismatch"));
            Assert.assertTrue(Files.exists(root.resolve("screenshots/01-assert_count-after.png")));
            data.put("event_expected_count", "2");
            executor.execute(step, new SoftAssert());
            js.executeScript("document.body.innerHTML = ''");
            Assert.expectThrows(AssertionError.class, () -> executor.execute(step, new SoftAssert()));
            data.put("event_expected_count", "0");
            executor.execute(step, new SoftAssert());
            data.put("event_expected_count", "-1");
            Assert.expectThrows(IllegalStateException.class, () -> executor.execute(step, new SoftAssert()));
        }
    }

    // Check soft-failure continuation, wrong filenames and headless PDF output.
    @Test
    public void softVerifyAndFastDownload() throws Exception {
        Path root = Files.createTempDirectory("assignment-actions-");
        Path downloads = Files.createDirectory(root.resolve("downloads"));
        try (var browser = BrowserSession.start(downloads, true)) {
            browser.driver().get("data:text/html,<h1 id='ready'>Ready</h1>");
            Files.writeString(downloads.resolve("earlier.pdf"), "Earlier scenario");
            var executor = new StepExecutor(browser.driver(), downloads, root.resolve("screenshots"),
                WorkbookData.load(Path.of(System.getProperty("workbook"))).dataValues(),
                Map.of("missing", new LocatorDefinition("id", "missing"),
                       "ready", new LocatorDefinition("id", "ready")));
            var verify = new SoftAssert();
            executor.execute(new StepDefinition("fixture", 1, "VERIFY_VISIBLE", "missing", "", "", 1), verify);
            executor.execute(new StepDefinition("fixture", 2, "ASSERT_VISIBLE", "ready", "", "", 1), verify);
            Assert.expectThrows(AssertionError.class, verify::assertAll);
            Files.writeString(downloads.resolve("actual.zip"), "Local fixture download");
            long start = System.nanoTime();
            var error = Assert.expectThrows(AssertionError.class, () -> executor.execute(
                new StepDefinition("fixture", 3, "ASSERT_DOWNLOAD", "", "", "negative_expected_filename", 10), new SoftAssert()));
            Assert.assertTrue(error.getMessage().contains("actual.zip"));
            Assert.assertTrue((System.nanoTime() - start) / 1_000_000_000L < 8);
            Assert.assertTrue(Files.exists(root.resolve("screenshots/03-assert_download-after.png")));
            String previousHeadless = System.getProperty("headless");
            try {
                System.setProperty("headless", "true");
                executor.execute(new StepDefinition("fixture", 4, "PRINT_TO_PDF", "",
                    "transcript_pdf_filename", "", 10), new SoftAssert());
                String filename = WorkbookData.load(Path.of(System.getProperty("workbook")))
                    .dataValues().get("transcript_pdf_filename");
                byte[] pdf = Files.readAllBytes(downloads.resolve(filename));
                Assert.assertTrue(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"));
            } finally {
                if (previousHeadless == null) System.clearProperty("headless");
                else System.setProperty("headless", previousHeadless);
            }
        }
    }
}
