package assignment.tests;

import assignment.browser.BrowserSession;
import assignment.browser.RunPaths;
import assignment.data.StepDefinition;
import assignment.data.WorkbookData;
import assignment.reporting.AssignmentReportListener;
import assignment.reporting.AssignmentReportListener.Entry;
import assignment.reporting.AssignmentReportListener.Line;
import assignment.steps.StepExecutor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
        List<Line> expected = expectedSteps(steps, data);
        // One line per step that ran, in order.
        var actual = new ArrayList<Line>();
        StepDefinition current = null;
        int finished = 0;
        boolean passed = false;
        Throwable failure = null;
        Path screenshots = null;
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
                current = step;
                // Only the final wrong-filename assertion counts as the designed failure.
                intendedAssertionReached = "FAIL".equals(scenario.get("intended_outcome"))
                    && step == steps.get(steps.size() - 1) && "ASSERT_DOWNLOAD".equals(step.action())
                    && zipVerified;
                actual.add(new Line(step.order(), executor.execute(step, verify)));
                finished++;
                if ("ASSERT_NEW_ZIP".equals(step.action())) zipVerified = true;
            }
            current = null;
            intendedAssertionReached = false;
            verify.assertAll();
            passed = true;
        } catch (Throwable error) {
            failure = error;
            // Put the failure on the step that failed, then say which steps did not run.
            actual.add(new Line(current == null ? 0 : current.order(), "Failed: " + failureSummary(error)));
            int notRun = steps.size() - finished - (current == null ? 0 : 1);
            if (notRun > 0) {
                int first = steps.get(steps.size() - notRun).order();
                int last = steps.get(steps.size() - 1).order();
                actual.add(new Line(0, first == last ? "Step " + first + " did not run"
                    : "Steps " + first + " to " + last + " did not run"));
            }
        } finally {
            // An intended failure is still reported as FAIL.
            AssignmentReportListener.add(new Entry(order, name, scenario.get("description"), actual, expected,
                passed, intendedAssertionReached && failure instanceof AssertionError, screenshots));
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

    // List what each checking step should find, using the values in Excel.
    private static List<Line> expectedSteps(List<StepDefinition> steps, Map<String, String> data) {
        var expected = new ArrayList<Line>();
        for (StepDefinition step : steps) {
            String value = step.expectedKey().isBlank() ? "" : data.get(step.expectedKey());
            String input = step.inputKey().isBlank() ? "" : data.get(step.inputKey());
            String what = StepExecutor.label(step.locatorKey());
            String text = switch (step.action()) {
                case "WAIT_FOR_AUTHENTICATION" -> "Sign-in and Duo approval finish";
                case "PRINT_TO_PDF" -> "The page is saved as a PDF named " + input;
                case "ASSERT_VISIBLE", "VERIFY_VISIBLE" -> what + " is visible";
                case "ASSERT_TEXT", "VERIFY_TEXT" -> what + " shows " + value;
                case "ASSERT_COUNT" -> "Visible count for " + what + ": " + value;
                case "ASSERT_VALUE_CHANGED" -> what + " changes from its starting value";
                case "CHANGE_CHECKBOX" -> what + " is set to " + input;
                case "ASSERT_URL_CONTAINS" -> "The URL contains " + value;
                case "ASSERT_NEW_ZIP" -> "A new ZIP file finishes downloading";
                case "ASSERT_DOWNLOAD" -> "A downloaded file is named " + value;
                default -> null;
            };
            if (text != null) expected.add(new Line(step.order(), text));
        }
        return expected;
    }

    private static String failureSummary(Throwable failure) {
        // Include the cause. The step number is already on the report line.
        var messages = new ArrayList<String>();
        for (Throwable current = failure; current != null && messages.size() < 3; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null || message.isBlank() || message.matches("Step \\d+ \\S+ failed")) continue;
            String firstLine = message.lines().findFirst().orElse(message).trim();
            if (messages.stream().noneMatch(m -> m.contains(firstLine))) messages.add(firstLine);
        }
        return messages.isEmpty() ? failure.getClass().getSimpleName() : String.join(": ", messages);
    }
}
