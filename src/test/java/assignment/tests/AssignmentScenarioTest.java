package assignment.tests;

import assignment.browser.BrowserSession;
import assignment.browser.RunPaths;
import assignment.data.StepDefinition;
import assignment.data.WorkbookData;
import assignment.reporting.AssignmentReportListener;
import assignment.reporting.AssignmentReportListener.Entry;
import assignment.steps.StepExecutor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.openqa.selenium.NoSuchWindowException;
import org.openqa.selenium.WebDriver;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.testng.asserts.SoftAssert;

/** Executes the five assignment scenarios in one authenticated browser session. */
public class AssignmentScenarioTest {
    private WorkbookData workbook;
    private BrowserSession browser;
    private Path downloads;

    // Load the chosen workbook and start one browser for the full suite.
    @BeforeClass
    public void startBrowser() throws Exception {
        workbook = WorkbookData.load(Path.of(System.getProperty("workbook")));
        var paths = new RunPaths(AssignmentReportListener.REPORT_ROOT);
        downloads = paths.freshDownloadDirectory();
        boolean headless = Boolean.parseBoolean(System.getProperty("headless", "false"));
        // One browser preserves SSO after the single permitted Duo interaction.
        browser = BrowserSession.start(downloads, headless);
    }

    // Close Chrome even when a scenario fails.
    @AfterClass(alwaysRun = true)
    public void stopBrowser() {
        if (browser != null) browser.close();
    }

    // Give TestNG one scenario at a time so they can share the login.
    @DataProvider(name = "scenarios", parallel = false)
    public Object[][] scenarios() throws Exception {
        if (workbook == null) workbook = WorkbookData.load(Path.of(System.getProperty("workbook")));
        List<Map<String, String>> scenarios = workbook.scenarios();
        Set<String> selected = selectedScenarioIds();
        if (!selected.isEmpty()) {
            // Calibration runs can exercise only authenticated pages while preserving workbook order.
            scenarios = scenarios.stream().filter(row -> selected.contains(row.get("scenario_id"))).toList();
            if (scenarios.isEmpty()) throw new IllegalArgumentException("scenarioIds did not match any workbook scenario");
        }
        Object[][] values = new Object[scenarios.size()][1];
        for (int index = 0; index < scenarios.size(); index++) values[index][0] = scenarios.get(index);
        return values;
    }

    // An empty scenarioIds setting means run all five scenarios.
    private static Set<String> selectedScenarioIds() {
        String configured = System.getProperty("scenarioIds", "").trim();
        if (configured.isEmpty()) return Set.of();
        return Arrays.stream(configured.split(",")).map(String::trim).filter(value -> !value.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }

    @Test(dataProvider = "scenarios")
    public void runScenario(Map<String, String> scenario) throws Throwable {
        String id = scenario.get("scenario_id");
        int order = Integer.parseInt(scenario.get("execution_order"));
        String name = scenario.get("scenario_name");
        List<StepDefinition> steps = workbook.stepsFor(id);
        Map<String, String> data = workbook.dataValues();
        String expected = scenario.get("description") + " | " + expectedSummary(steps, data);
        String actual = "Scenario did not start";
        boolean passed = false;
        Throwable failure = null;
        Path screenshots = null;
        var checks = new ArrayList<String>();
        boolean intendedAssertionReached = false;
        boolean zipVerified = false;

        try {
            useOpenWindow(browser.driver());
            var paths = new RunPaths(AssignmentReportListener.REPORT_ROOT);
            screenshots = paths.screenshotDirectory(id, name);
            var executor = new StepExecutor(browser.driver(), downloads, screenshots, data, workbook.locators());
            // Verify failures are collected; Assert failures stop the current scenario.
            var verify = new SoftAssert();
            for (StepDefinition step : steps) {
                // Only the final wrong-filename assertion counts as the designed failure.
                intendedAssertionReached = "FAIL".equals(scenario.get("intended_outcome"))
                    && step == steps.get(steps.size() - 1) && "ASSERT_DOWNLOAD".equals(step.action())
                    && zipVerified;
                String result = executor.execute(step, verify);
                if ("ASSERT_NEW_ZIP".equals(step.action())) zipVerified = true;
                if (!result.isBlank()) checks.add(result);
            }
            intendedAssertionReached = false;
            verify.assertAll();
            actual = checks.isEmpty() ? "All " + steps.size() + " steps completed" : String.join(" | ", checks);
            passed = true;
        } catch (Throwable error) {
            failure = error;
            // Keep earlier observations alongside the failure in the report.
            actual = (checks.isEmpty() ? "" : String.join(" | ", checks) + " | ") + failureSummary(error);
        } finally {
            // An intended failure is still reported as FAIL.
            AssignmentReportListener.add(new Entry(order, name, actual, expected, passed,
                intendedAssertionReached && failure instanceof AssertionError, screenshots));
        }

        if (failure != null) throw failure;
    }

    private static void useOpenWindow(WebDriver driver) {
        // A failed scenario can leave the driver on a tab Chrome has since closed;
        // the next scenario must still start in a live tab.
        try {
            driver.getWindowHandle();
        } catch (NoSuchWindowException closed) {
            List<String> open = new ArrayList<>(driver.getWindowHandles());
            if (open.isEmpty()) throw new IllegalStateException("All browser windows were closed before this scenario started", closed);
            driver.switchTo().window(open.get(open.size() - 1));
        }
        driver.switchTo().defaultContent();
    }

    // Show expected values from Excel without repeating them.
    private static String expectedSummary(List<StepDefinition> steps, Map<String, String> data) {
        var expected = new LinkedHashSet<String>();
        for (StepDefinition step : steps) {
            if (!step.expectedKey().isBlank()) expected.add(data.get(step.expectedKey()));
        }
        return expected.isEmpty() ? "All workbook-defined steps complete" : String.join(" | ", expected);
    }

    private static String failureSummary(Throwable failure) {
        // Include the cause as well as the failed step.
        var messages = new ArrayList<String>();
        for (Throwable current = failure; current != null && messages.size() < 3; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null || message.isBlank()) continue;
            String firstLine = message.lines().findFirst().orElse(message).trim();
            if (messages.stream().noneMatch(m -> m.contains(firstLine))) messages.add(firstLine);
        }
        return messages.isEmpty() ? failure.getClass().getSimpleName() : String.join(": ", messages);
    }
}
