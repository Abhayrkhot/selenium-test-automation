package assignment.steps;

import assignment.data.LocatorDefinition;
import assignment.data.StepDefinition;
import java.awt.AWTException;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.openqa.selenium.By;
import org.openqa.selenium.ElementClickInterceptedException;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NotFoundException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.PrintsPage;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.WindowType;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.print.PrintOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testng.Assert;
import org.testng.asserts.SoftAssert;

/** Runs Excel actions and captures the page before and after each step. */
public final class StepExecutor {
    private static final List<String> PARTIAL_DOWNLOAD_SUFFIXES = List.of(".crdownload", ".part", ".tmp");
    private static final String SIGN_IN_PROMPTS = "post_duo_prompts";
    private static final String DROPDOWN_OPTION = "dropdown_option";
    private final WebDriver driver;
    private final Path downloadDirectory;
    private final Path screenshots;
    private final Map<String, String> data;
    private final Map<String, LocatorDefinition> locators;
    private final Set<Path> previousDownloads;
    private final Map<String, String> rememberedValues = new java.util.HashMap<>();
    private String activeFrameKey;
    private String contextMenuLocator;
    // Print candidates must be near the last right-click.
    private java.awt.Point rightClickPoint;
    private static final int MAX_MENU_DISTANCE = 400;
    // Record when a blocked mouse click needed JavaScript instead.
    private boolean javaScriptClickUsed;
    public static final String JS_CLICK_NOTE = "(real click was intercepted; clicked with JavaScript instead)";
    private final List<String> answeredPrompts = new ArrayList<>();

    public StepExecutor(WebDriver driver, Path downloadDirectory, Path screenshots,
                        Map<String, String> data, Map<String, LocatorDefinition> locators) throws IOException {
        this.driver = driver;
        this.downloadDirectory = downloadDirectory;
        this.screenshots = screenshots;
        this.data = data;
        this.locators = locators;
        // Remember existing files so later assertions look for new downloads.
        try (var files = Files.list(downloadDirectory)) {
            previousDownloads = files.collect(java.util.stream.Collectors.toSet());
        }
    }

    // Run one step, keep failure evidence, then pass the result back to the report.
    public String execute(StepDefinition step, SoftAssert softAssert) {
        Throwable failure = null;
        String actual = "";
        String expected = value(step.expectedKey());
        javaScriptClickUsed = false;
        try {
            capture(step, "before");
            actual = perform(step, expected, softAssert);
            if (javaScriptClickUsed) actual += " " + JS_CLICK_NOTE;
        } catch (Throwable error) {
            failure = error;
        } finally {
            try {
                capture(step, "after");
            } catch (Throwable screenshotError) {
                // Keep the step error if its after-screenshot also fails.
                if (failure == null) failure = screenshotError;
                else failure.addSuppressed(screenshotError);
            }
        }
        if (failure != null) {
            if (failure instanceof AssertionError assertion) throw assertion;
            throw new IllegalStateException("Step %d %s failed".formatted(step.order(), step.action()), failure);
        }
        // Tell the report what this step did. Typed values are never included.
        return actual;
    }

    // Number filenames by step and label them before or after.
    private void capture(StepDefinition step, String position) throws IOException {
        Files.createDirectories(screenshots);
        Path file = screenshots.resolve("%02d-%s-%s.png".formatted(
            step.order(), step.action().toLowerCase(), position));
        Files.write(file, ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES));
    }

    // Map the action name in Excel to a browser action or check.
    private String perform(StepDefinition step, String expected, SoftAssert softAssert) {
        return switch (step.action()) {
            case "OPEN" -> {
                String url = resolve(value(step.inputKey()));
                activeFrameKey = null;
                driver.navigate().to(url);
                yield "Opened " + driver.getCurrentUrl();
            }
            case "TYPE" -> {
                String input = resolve(value(step.inputKey()));
                WebElement element = visible(step);
                element.clear();
                element.sendKeys(input);
                yield "Typed " + label(step.inputKey()) + " into " + label(step.locatorKey());
            }
            case "CLICK" -> {
                safeClick(clickable(step));
                yield "Clicked " + label(step.locatorKey());
            }
            // Missing optional controls are logged. A visible but unusable control still fails.
            case "CLICK_IF_PRESENT" -> {
                WebElement element;
                try { element = clickable(step); }
                catch (TimeoutException absent) {
                    if (driver.findElements(by(step.locatorKey())).stream().anyMatch(WebElement::isDisplayed)) throw absent;
                    // For transcript Submit, absence is still a requirement gap, not proof of a click.
                    yield label(step.locatorKey()) + " is absent, so it was skipped";
                }
                safeClick(element);
                yield "Clicked " + label(step.locatorKey());
            }
            // Save the date before using the picker, then require it to change.
            case "REMEMBER_VALUE" -> {
                String current = visible(step).getDomProperty("value");
                rememberedValues.put(step.locatorKey(), current);
                yield "Noted the starting " + label(step.locatorKey()) + ": " + current;
            }
            case "ASSERT_VALUE_CHANGED" -> {
                String original = rememberedValues.get(step.locatorKey());
                Assert.assertNotNull(original, "No initial field value recorded");
                try {
                    wait(step).until(ignored -> {
                        String current = visible(step).getDomProperty("value");
                        return current != null && !current.isBlank() && !current.equals(original);
                    });
                } catch (TimeoutException unchanged) {
                    Assert.fail("Date did not change; choose a different day in Excel", unchanged);
                }
                yield "Date changed from " + original + " to " + visible(step).getDomProperty("value");
            }
            case "CLICK_AND_SWITCH" -> clickAndSwitch(step);
            // Use Select for native dropdowns; CHOOSE_OPTION handles custom menus.
            case "SELECT_TEXT" -> {
                String option = resolve(value(step.inputKey()));
                new Select(visible(step)).selectByVisibleText(option);
                yield "Selected " + option + " in " + label(step.locatorKey());
            }
            case "CHOOSE_OPTION" -> {
                String option = resolve(value(step.inputKey()));
                safeClick(clickable(step));
                // Canvas (InstUI) dropdowns render their options in a separate listbox popup.
                By choice = optionLocator(option);
                wait(step).until(ignored -> driver.findElements(choice).stream()
                    .filter(WebElement::isDisplayed).findFirst().orElse(null)).click();
                yield "Chose " + option + " in " + label(step.locatorKey());
            }
            case "SCROLL_INTO_VIEW" -> {
                WebElement element = visible(step);
                ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].scrollIntoView({block:'center', inline:'nearest'});", element);
                yield "Scrolled to " + label(step.locatorKey());
            }
            case "CONTEXT_CLICK" -> {
                contextMenuLocator = step.locatorKey();
                WebElement element = visible(step);
                // Use the real mouse for the visible macOS menu.
                if (isVisibleRun() && isMac()) {
                    nativeContextClick(element);
                } else {
                    new Actions(driver).contextClick(element).pause(Duration.ofSeconds(2)).perform();
                }
                yield "Right-clicked " + label(step.locatorKey());
            }
            case "SET_CHECKBOX", "CHANGE_CHECKBOX" -> {
                String input = resolve(value(step.inputKey()));
                if (!Set.of("true", "false", "0", "1").contains(input.toLowerCase())) {
                    throw new IllegalArgumentException("Checkbox value must be true, false, 0 or 1");
                }
                boolean desired = "true".equalsIgnoreCase(input) || "1".equals(input);
                // CHANGE_CHECKBOX must choose a box whose current state differs from the target.
                WebElement checkbox = "CHANGE_CHECKBOX".equals(step.action())
                    ? firstMatch(step, element -> checkboxState(element) != desired) : present(step);
                // Retain the chosen checkbox's position when Trumba replaces the frame.
                int checkboxIndex = driver.findElements(by(step.locatorKey())).indexOf(checkbox);
                boolean original = checkboxState(checkbox);
                if (checkboxState(checkbox) != desired) {
                    // Trumba visually replaces its native checkbox, leaving the real input hidden.
                    if (checkbox.isDisplayed()) checkbox.click();
                    else ((JavascriptExecutor) driver).executeScript("arguments[0].click();", checkbox);
                }
                wait(step).until(ignored -> {
                    try {
                        reenterActiveFrame();
                        var matches = driver.findElements(by(step.locatorKey()));
                        return checkboxIndex >= 0 && matches.size() > checkboxIndex
                            && checkboxState(matches.get(checkboxIndex)) == desired;
                    } catch (NotFoundException | StaleElementReferenceException replaced) {
                        return false;
                    }
                });
                yield "Checkbox " + (checkboxIndex + 1) + " changed from " + original + " to " + desired;
            }
            // Remember the frame locator in case the page replaces that frame.
            case "SWITCH_TO_IFRAME" -> {
                wait(step).until(ExpectedConditions.frameToBeAvailableAndSwitchToIt(by(step.locatorKey())));
                activeFrameKey = step.locatorKey();
                yield "Switched into " + label(step.locatorKey());
            }
            case "SWITCH_TO_DEFAULT_CONTENT" -> {
                activeFrameKey = null;
                driver.switchTo().defaultContent();
                yield "Switched back to the main page";
            }
            case "WAIT_FOR_AUTHENTICATION" -> waitForAuthentication(step);
            case "WAIT_FOR_LOADING_COMPLETE" -> waitForLoadingComplete(step);
            case "PRINT_TO_PDF" -> printToPdf(step);
            case "ASSERT_VISIBLE" -> {
                WebElement element = visible(step);
                Assert.assertTrue(element.isDisplayed(), "Element is not visible: " + step.locatorKey());
                String text = shownText(element);
                yield label(step.locatorKey()) + " is visible" + (text.isBlank() ? "" : ": " + text);
            }
            // Verify records a failure and lets later steps continue.
            case "VERIFY_VISIBLE" -> {
                boolean displayed;
                try { displayed = visible(step).isDisplayed(); }
                catch (TimeoutException missing) { displayed = false; }
                softAssert.assertTrue(displayed, "Element is not visible: " + step.locatorKey());
                yield label(step.locatorKey()) + (displayed ? " is visible" : " is not visible");
            }
            case "ASSERT_COUNT" -> {
                int count = Integer.parseInt(resolve(expected));
                if (count < 0) throw new IllegalArgumentException("ASSERT_COUNT requires a nonnegative count");
                try {
                    // Stop waiting once there are enough matches; the equality check rejects extras.
                    wait(step).until(ignored -> visibleCount(step) >= count);
                } catch (TimeoutException tooFew) {
                    // Report the observed count below rather than a generic wait timeout.
                }
                long actual = visibleCount(step);
                Assert.assertEquals(actual, (long) count, "Visible count mismatch for " + step.locatorKey());
                yield "Visible count for " + label(step.locatorKey()) + ": " + actual;
            }
            case "ASSERT_TEXT" -> {
                String text = visible(step).getText();
                Assert.assertEquals(text, resolve(expected), "Visible text mismatch");
                yield label(step.locatorKey()) + " shows " + text;
            }
            case "VERIFY_TEXT" -> {
                try {
                    String text = visible(step).getText();
                    softAssert.assertEquals(text, resolve(expected), "Visible text mismatch");
                    yield label(step.locatorKey()) + " shows " + text;
                } catch (TimeoutException missing) {
                    softAssert.fail("Text element missing: " + step.locatorKey());
                    yield "Text element missing: " + step.locatorKey();
                }
            }
            case "ASSERT_NEW_ZIP" -> assertNewZip(step);
            case "ASSERT_URL_CONTAINS" -> {
                String current = driver.getCurrentUrl();
                Assert.assertTrue(current.contains(resolve(expected)),
                    "URL did not contain expected text. Actual: " + current);
                yield "Current URL: " + current;
            }
            case "ASSERT_DOWNLOAD" -> "Found downloaded file " + assertDownload(step, resolve(expected));
            default -> throw new IllegalArgumentException("Unsupported action: " + step.action());
        };
    }

    private long visibleCount(StepDefinition step) {
        return driver.findElements(by(step.locatorKey())).stream().filter(WebElement::isDisplayed).count();
    }

    private void switchToWindow(String handle) {
        activeFrameKey = null;
        driver.switchTo().window(handle);
    }

    // Wait for a newly opened tab; do not switch to an unrelated existing tab.
    private String clickAndSwitch(StepDefinition step) {
        Set<String> before = driver.getWindowHandles();
        safeClick(clickable(step));
        String newHandle = wait(step).until(ignored -> driver.getWindowHandles().stream()
            .filter(handle -> !before.contains(handle)).findFirst().orElse(null));
        switchToWindow(newHandle);
        return "Clicked " + label(step.locatorKey()) + " and switched to the new tab";
    }

    // Allow time for Duo approval, then wait for the Student Hub host.
    private String waitForAuthentication(StepDefinition step) {
        String authenticatedUrl = resolve(value("student_hub_url"));
        String expectedHost = URI.create(authenticatedUrl).getHost();
        // Follow either the automatic redirect or an explicit launcher, including a new tab.
        Set<String> initialWindows = driver.getWindowHandles();
        var clickedLaunchers = new java.util.HashSet<String>();
        wait(step).ignoring(StaleElementReferenceException.class).until(ignored -> {
            driver.getWindowHandles().stream().filter(handle -> !initialWindows.contains(handle))
                .findFirst().ifPresent(this::switchToWindow);
            if (expectedHost.equalsIgnoreCase(URI.create(driver.getCurrentUrl()).getHost())) return true;
            answerSignInPrompt();
            if (!step.locatorKey().isBlank()) {
                var launcher = driver.findElements(by(step.locatorKey())).stream()
                    .filter(element -> element.isDisplayed() && element.isEnabled()).findFirst();
                if (launcher.isPresent() && !clickedLaunchers.contains(driver.getCurrentUrl())) {
                    String sourceUrl = driver.getCurrentUrl();
                    safeClick(launcher.get());
                    clickedLaunchers.add(sourceUrl);
                }
            }
            return false;
        });
        return "Signed in and reached " + driver.getCurrentUrl()
            + (answeredPrompts.isEmpty() ? "" : ", answering " + String.join(" and ", answeredPrompts));
    }

    private void answerSignInPrompt() {
        // SSO prompts can reappear when opening Banner or Canvas.
        if (activeFrameKey != null || !locators.containsKey(SIGN_IN_PROMPTS)) return;
        try {
            for (WebElement prompt : driver.findElements(by(SIGN_IN_PROMPTS))) {
                if (!prompt.isDisplayed() || !prompt.isEnabled()) continue;
                String label = prompt.getText().isBlank() ? prompt.getAttribute("value") : prompt.getText();
                prompt.click();
                answeredPrompts.add("'" + label.trim() + "'");
                return;
            }
        } catch (WebDriverException transientDuringRedirect) {
            // The page changed under us.
        }
    }

    private String waitForLoadingComplete(StepDefinition step) {
        // Wait for both the document and the workbook-defined loading indicators.
        wait(step).until(ignored -> {
            Object ready = ((JavascriptExecutor) driver).executeScript("return document.readyState");
            boolean loading = driver.findElements(by(step.locatorKey()))
                .stream().anyMatch(WebElement::isDisplayed);
            return "complete".equals(ready) && !loading;
        });
        return "Page loading completed at " + driver.getCurrentUrl();
    }

    // Save the page using the filename from Excel, then check the PDF header.
    private String printToPdf(StepDefinition step) {
        String filename = resolve(value(step.inputKey()));
        Path output = downloadDirectory.resolve(filename).normalize();
        if (!output.startsWith(downloadDirectory.normalize())) {
            throw new IllegalArgumentException("PDF filename must stay inside the download directory");
        }
        try {
            String method;
            if (isVisibleRun()) {
                try {
                    savePdfThroughChromeUi(output, step.timeoutSeconds());
                    method = isWindows()
                        ? "Chrome print to Save as PDF after the right-click (Windows kiosk printing)"
                        : "Chrome native context-menu Print and Save as PDF";
                } catch (IOException | IllegalStateException uiSaveFailed) {
                    // Close failed print dialogs so the next scenario can continue.
                    closeOpenDialogs();
                    throw new IllegalStateException("Chrome's print dialog did not save the PDF: "
                        + uiSaveFailed.getMessage(), uiSaveFailed);
                }
            } else {
                // Headless runs have no print dialog to drive, so they use Selenium's print API.
                savePdfThroughSelenium(output);
                method = "Selenium print API (headless run)";
            }

            // This checks the file format, not the transcript contents.
            try (var input = Files.newInputStream(output)) {
                Assert.assertEquals(new String(input.readNBytes(5), java.nio.charset.StandardCharsets.US_ASCII),
                    "%PDF-", "Saved transcript is not a PDF");
            }

            // Open the saved PDF for the after-step screenshot.
            driver.switchTo().newWindow(WindowType.TAB);
            driver.navigate().to(output.toAbsolutePath().toUri().toString());
            new WebDriverWait(driver, Duration.ofSeconds(step.timeoutSeconds())).until(ignored ->
                driver.getCurrentUrl().startsWith("file:")
                    && "complete".equals(((JavascriptExecutor) driver)
                        .executeScript("return document.readyState")));
            return "Saved and opened PDF via %s at %s".formatted(method, output.getFileName());
        } catch (IOException error) {
            throw new IllegalStateException("Could not save printed transcript PDF", error);
        }
    }

    private boolean isVisibleRun() {
        return !Boolean.parseBoolean(System.getProperty("headless", "false"));
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private void savePdfThroughChromeUi(Path output, int timeoutSeconds) throws IOException {
        if (isWindows()) {
            // Windows uses kiosk printing; this path does not select the context-menu item.
            savePdfWithKioskPrinting(output, timeoutSeconds);
            return;
        }
        if (!isMac()) {
            throw new IllegalStateException("Visible PDF printing is supported on macOS and Windows only");
        }
        try {
            Robot keyboard = new Robot();
            keyboard.setAutoDelay(150);

            if (contextMenuLocator == null) throw new IllegalStateException("PRINT_TO_PDF requires a preceding CONTEXT_CLICK");
            // WebDriver screenshots dismiss the menu, so open it again before selecting Print.
            nativeContextClick(driver.findElement(by(contextMenuLocator)));
            selectNativePrint();
            keyboard.delay(5000);

            // Save as PDF is preset; Enter opens the system save dialog.
            press(keyboard, KeyEvent.VK_ENTER);
            keyboard.delay(2000);
            chord(keyboard, selectAllModifier(), KeyEvent.VK_A);
            type(keyboard, output.getFileName().toString());
            keyboard.delay(500);
            press(keyboard, KeyEvent.VK_ENTER);

            waitForFile(output, timeoutSeconds);
        } catch (AWTException error) {
            throw new IllegalStateException(
                "Could not control Chrome's visible print dialog. Allow Java to send keyboard input on this computer.",
                error);
        }
    }

    private void nativeContextClick(WebElement element) {
        try {
            // Focus this Selenium Chrome process, not another open Chrome window.
            Object processId = ((org.openqa.selenium.HasCapabilities) driver).getCapabilities().getCapability("goog:processID");
            if (!(processId instanceof Number)) throw new IOException("Chrome did not provide its browser process ID");
            Process activation = new ProcessBuilder("/usr/bin/osascript", "-e",
                "tell application \"System Events\" to set frontmost of first process whose unix id is "
                    + ((Number) processId).longValue() + " to true").start();
            if (!activation.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) || activation.exitValue() != 0) {
                activation.destroyForcibly();
                throw new IOException("Could not activate Chrome for the native context menu");
            }
            // Convert the element position to screen coordinates, allowing for browser borders.
            var coordinates = (List<?>) ((JavascriptExecutor) driver).executeScript(
                "const r=arguments[0].getBoundingClientRect();return [window.screenX+(window.outerWidth-window.innerWidth)/2+r.left+r.width/2,"
                + "window.screenY+window.outerHeight-window.innerHeight+r.top+Math.min(r.height/2,window.innerHeight/2)];", element);
            Robot mouse = new Robot();
            mouse.setAutoDelay(150);
            // Use this position to compare nearby Print candidates.
            rightClickPoint = new java.awt.Point(((Number) coordinates.get(0)).intValue(),
                ((Number) coordinates.get(1)).intValue());
            mouse.mouseMove(rightClickPoint.x, rightClickPoint.y);
            mouse.mousePress(InputEvent.BUTTON3_DOWN_MASK);
            mouse.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
            mouse.delay(500);
        } catch (IOException | AWTException error) {
            throw new IllegalStateException("Could not open the native page context menu", error);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while opening the native context menu", interrupted);
        }
    }

    private void selectNativePrint() throws IOException {
        Path image = Files.createTempFile("chrome-context-menu-", ".png");
        try {
            Robot mouse = new Robot();
            java.awt.Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            javax.imageio.ImageIO.write(mouse.createScreenCapture(new java.awt.Rectangle(screen)), "png", image.toFile());
            // The local Swift helper reads Print labels from a screen capture.
            Process process = new ProcessBuilder("/usr/bin/swift", "scripts/find-print-menu.swift", image.toString())
                .redirectErrorStream(true).start();
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Timed out locating native Print menu item");
            }
            String result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                keepScreenCapture(image);
                throw new IOException("Native menu recognition failed: " + result);
            }
            // Prefer the nearest Print label within the distance limit.
            // A nearby page label can still be mistaken for a menu item.
            java.awt.Point menuPrint = null;
            double nearest = Double.MAX_VALUE;
            for (String line : result.lines().toList()) {
                if (!line.trim().matches("[0-9.]+\\s+[0-9.]+")) continue;
                String[] point = line.trim().split("\\s+");
                var candidate = new java.awt.Point((int) (Double.parseDouble(point[0]) * screen.width),
                    (int) (Double.parseDouble(point[1]) * screen.height));
                double distance = candidate.distance(rightClickPoint);
                if (distance < nearest) {
                    nearest = distance;
                    menuPrint = candidate;
                }
            }
            if (menuPrint == null || nearest > MAX_MENU_DISTANCE) {
                keepScreenCapture(image);
                throw new IOException("No Print item inside the right-click menu (nearest Print text was "
                    + (menuPrint == null ? "not found" : (int) nearest + " points from the click") + ")");
            }
            mouse.mouseMove(menuPrint.x, menuPrint.y);
            mouse.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            mouse.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        } catch (AWTException error) {
            throw new IOException("Native mouse control unavailable", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted locating native Print", error);
        } finally { Files.deleteIfExists(image); }
    }

    private void keepScreenCapture(Path image) throws IOException {
        // Save what the screen showed so the report explains why Print was not clicked.
        Files.createDirectories(screenshots);
        Files.copy(image, screenshots.resolve("print-menu-search-screen.png"), StandardCopyOption.REPLACE_EXISTING);
    }

    private void closeOpenDialogs() {
        // Escape closes the save dialog and then print preview.
        try {
            Robot keyboard = new Robot();
            keyboard.setAutoDelay(300);
            for (int i = 0; i < 2; i++) press(keyboard, KeyEvent.VK_ESCAPE);
            keyboard.delay(1000);
        } catch (AWTException ignored) {
            // Preserve the original save failure.
        }
    }

    private static int selectAllModifier() {
        int menuShortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        return (menuShortcut & InputEvent.META_DOWN_MASK) != 0
            ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;
    }

    // Headless tests use the browser print API, not the native print menu.
    private void savePdfThroughSelenium(Path output) throws IOException {
        if (!(driver instanceof PrintsPage printable)) {
            throw new WebDriverException("The active browser does not support Selenium print-to-PDF");
        }
        Files.write(output, Base64.getDecoder().decode(
            printable.print(new PrintOptions()).getContent()));
    }

    // Windows printing creates a new PDF named after the page title.
    private void savePdfWithKioskPrinting(Path output, int timeoutSeconds) throws IOException {
        Path folder = output.getParent();
        Set<Path> existing = pdfsIn(folder);
        ((JavascriptExecutor) driver).executeScript("setTimeout(() => window.print(), 250); return true;");
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        while (System.nanoTime() < deadline) {
            for (Path pdf : pdfsIn(folder)) {
                if (existing.contains(pdf)) continue;
                long size = Files.size(pdf);
                pause(750);
                // Chrome names the file after the page title; rename it once it has finished writing.
                if (size > 0 && Files.exists(pdf) && Files.size(pdf) == size) {
                    if (!pdf.equals(output)) Files.move(pdf, output, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
            }
            pause(250);
        }
        throw new IOException("Chrome's kiosk printing did not save a PDF in " + folder
            + " within " + timeoutSeconds + " seconds");
    }

    // Track PDFs present before printing so an old file is not mistaken for the new one.
    private static Set<Path> pdfsIn(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(file -> file.getFileName().toString().toLowerCase().endsWith(".pdf"))
                .collect(java.util.stream.Collectors.toSet());
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the PDF", error);
        }
    }

    // Wait for the save dialog to produce a nonempty file.
    private void waitForFile(Path output, int timeoutSeconds) throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(output) && Files.size(output) > 0) return;
            try {
                Thread.sleep(250);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the PDF save dialog", error);
            }
        }
        throw new IOException("Chrome did not save the PDF within " + timeoutSeconds + " seconds: " + output);
    }

    private static void press(Robot keyboard, int key) {
        keyboard.keyPress(key);
        keyboard.keyRelease(key);
    }

    // Press a shortcut and release both keys so modifiers do not stay held.
    private static void chord(Robot keyboard, int modifier, int key) {
        keyboard.keyPress(modifier);
        keyboard.keyPress(key);
        keyboard.keyRelease(key);
        keyboard.keyRelease(modifier);
    }

    // Type the PDF filename into the system save dialog.
    private static void type(Robot keyboard, String text) {
        for (char character : text.toCharArray()) {
            int key = KeyEvent.getExtendedKeyCodeForChar(character);
            if (key == KeyEvent.VK_UNDEFINED) {
                throw new IllegalArgumentException("Unsupported PDF filename character: " + character);
            }
            press(keyboard, key);
        }
    }

    // Require a new, stable ZIP with a nonempty entry before the negative filename check.
    private String assertNewZip(StepDefinition step) {
        var sizes = new java.util.HashMap<Path, Long>();
        try {
            Path zip = wait(step).until(ignored -> {
                try (var files = Files.list(downloadDirectory)) {
                    for (Path file : files.toList()) {
                        if (previousDownloads.contains(file) || !file.getFileName().toString().toLowerCase().endsWith(".zip")
                                || !completeNonEmptyFile(file)) continue;
                        long size = Files.size(file);
                        Long previous = sizes.put(file, size);
                        if (previous == null || previous != size) continue;
                        try (var archive = new java.util.zip.ZipFile(file.toFile())) {
                            if (archive.stream().anyMatch(entry -> !entry.isDirectory() && entry.getSize() > 0)) return file;
                        } catch (IOException incomplete) {
                            // Retry while the archive is still being written.
                        }
                    }
                } catch (IOException unreadable) {
                    // Retry until the step's deadline.
                }
                return null;
            });
            return "Verified completed ZIP: " + zip.getFileName();
        } catch (TimeoutException missing) {
            throw new AssertionError("Download prerequisite failed: no new completed ZIP; " + downloadedFiles(), missing);
        }
    }

    // Compare the downloaded filename with the exact value from Excel.
    private String assertDownload(StepDefinition step, String expectedFilename) {
        Path expectedFile = downloadDirectory.resolve(expectedFilename).normalize();
        if (!expectedFile.startsWith(downloadDirectory.normalize())) {
            throw new IllegalArgumentException("Download filename must not leave its scenario directory");
        }
        var wait = new WebDriverWait(driver, Duration.ofSeconds(step.timeoutSeconds()));
        try {
            // Ignore files from earlier scenarios, but include downloads that completed
            // before this assertion started (a small ZIP can finish during the click).
            wait.until(ignored -> completeNonEmptyFile(expectedFile) || downloadsSettled());
        } catch (RuntimeException stillDownloading) {
            // Fall through: the listing below shows any partial download.
        }
        if (!completeNonEmptyFile(expectedFile)) {
            Assert.fail("Expected downloaded file '%s'; downloads folder has: %s"
                .formatted(expectedFilename, downloadedFiles()));
        }
        return expectedFile.getFileName().toString();
    }

    // A new nonempty file with no partial downloads lets a wrong filename fail promptly.
    private boolean downloadsSettled() {
        try (Stream<Path> files = Files.list(downloadDirectory)) {
            List<Path> all = files.toList();
            boolean partial = all.stream().anyMatch(path -> PARTIAL_DOWNLOAD_SUFFIXES.stream()
                .anyMatch(suffix -> path.getFileName().toString().endsWith(suffix)));
            return !partial && all.stream().filter(path -> !previousDownloads.contains(path)).anyMatch(this::completeNonEmptyFile);
        } catch (IOException ignored) {
            return false;
        }
    }

    // List filenames and sizes for failures without dumping file contents.
    private String downloadedFiles() {
        try (Stream<Path> files = Files.list(downloadDirectory)) {
            List<String> listing = files
                .sorted(Comparator.comparing(StepExecutor::modifiedTime).reversed())
                .map(path -> "%s (%s)".formatted(path.getFileName(), readableSize(path)))
                .toList();
            return listing.isEmpty() ? "no files" : String.join(", ", listing);
        } catch (IOException error) {
            return "unreadable (" + error.getMessage() + ")";
        }
    }

    private static FileTime modifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException error) {
            return FileTime.fromMillis(0);
        }
    }

    private static String readableSize(Path path) {
        try {
            long bytes = Files.size(path);
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return "%.1f KB".formatted(bytes / 1024.0);
            return "%.1f MB".formatted(bytes / (1024.0 * 1024));
        } catch (IOException error) {
            return "size unknown";
        }
    }

    // Require a nonempty file and no remaining temporary download files.
    private boolean completeNonEmptyFile(Path expectedFile) {
        try {
            if (!Files.isRegularFile(expectedFile) || Files.size(expectedFile) == 0) return false;
            try (Stream<Path> files = Files.list(downloadDirectory)) {
                return files.noneMatch(path -> PARTIAL_DOWNLOAD_SUFFIXES.stream()
                    .anyMatch(suffix -> path.getFileName().toString().endsWith(suffix)));
            }
        } catch (IOException ignored) {
            return false;
        }
    }

    private void safeClick(WebElement element) {
        // Instant scrolling avoids clicking while the page is still moving.
        ((JavascriptExecutor) driver).executeScript(
            "arguments[0].scrollIntoView({block:'center', inline:'nearest', behavior:'instant'});", element);
        try {
            element.click();
        } catch (ElementClickInterceptedException covered) {
            // Record the JavaScript fallback because it bypasses normal mouse events.
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", element);
            javaScriptClickUsed = true;
            System.out.println("JavaScript click fallback used: real click was intercepted (" + covered.getMessage().lines().findFirst().orElse("") + ")");
        }
    }

    private WebElement visible(StepDefinition step) {
        // Ignore hidden copies of controls in responsive layouts.
        return firstMatch(step, WebElement::isDisplayed);
    }

    // Some custom widgets keep the real input hidden but still present in the page.
    private WebElement present(StepDefinition step) {
        return firstMatch(step, element -> true);
    }

    // Retry the lookup while redirects, prompts or frame replacements settle.
    private WebElement firstMatch(StepDefinition step, Predicate<WebElement> usable) {
        return wait(step).until(ignored -> {
            try {
                var match = driver.findElements(by(step.locatorKey())).stream().filter(usable).findFirst();
                if (match.isPresent()) return match.get();
            } catch (NotFoundException | StaleElementReferenceException detached) {
                if (activeFrameKey == null) throw detached;
            }
            answerSignInPrompt();
            reenterActiveFrame();
            return null;
        });
    }

    private void reenterActiveFrame() {
        // Calendar filters can replace the frame; find the current one again.
        if (activeFrameKey == null) return;
        driver.switchTo().defaultContent();
        driver.switchTo().frame(driver.findElement(by(activeFrameKey)));
    }

    // Custom checkboxes use aria-checked; native inputs use isSelected.
    private boolean checkboxState(WebElement element) {
        String checked = element.getAttribute("aria-checked");
        if (checked != null) return "true".equals(checked);
        return element.isSelected();
    }

    private WebElement clickable(StepDefinition step) {
        // Only use visible, enabled controls.
        return firstMatch(step, element -> element.isDisplayed() && element.isEnabled());
    }

    private static String shownText(WebElement element) {
        // Selenium returns every option's text for a dropdown; a viewer only sees the selected one.
        String text = "select".equalsIgnoreCase(element.getTagName())
            ? new Select(element).getFirstSelectedOption().getText()
            : element.getText();
        return text.replaceAll("\\s+", " ").trim();
    }

    private By optionLocator(String option) {
        // The workbook's dropdown_option selector marks where the option text goes with ${option}.
        LocatorDefinition template = locators.get(DROPDOWN_OPTION);
        if (template == null) throw new IllegalArgumentException("Missing locator: " + DROPDOWN_OPTION);
        return template.toBy(resolve(template.selector().replace("${option}", xpathLiteral(option))));
    }

    // Quote option text safely when it contains single or double quotes.
    private static String xpathLiteral(String text) {
        if (!text.contains("'")) return "'" + text + "'";
        if (!text.contains("\"")) return "\"" + text + "\"";
        return "concat('" + text.replace("'", "',\"'\",'") + "')";
    }

    // Use the timeout from this Excel step.
    private WebDriverWait wait(StepDefinition step) {
        return new WebDriverWait(driver, Duration.ofSeconds(step.timeoutSeconds()));
    }

    // Look up the selector and fill in any Data-sheet placeholders.
    // Turn an Excel key such as resources_tab into words for the report.
    public static String label(String key) {
        return key == null ? "" : key.replace('_', ' ');
    }

    private By by(String key) {
        LocatorDefinition locator = locators.get(key);
        if (locator == null) throw new IllegalArgumentException("Unknown locator key: " + key);
        return locator.toBy(resolve(locator.selector()));
    }

    // Blank keys are optional; unknown keys indicate a workbook error.
    private String value(String key) {
        if (key == null || key.isBlank()) return "";
        String value = data.get(key);
        if (value == null) throw new IllegalArgumentException("Unknown data key: " + key);
        return value;
    }

    // Replace ${key} placeholders with Excel values and reject unresolved names.
    private String resolve(String value) {
        String resolved = value;
        for (var entry : data.entrySet()) {
            resolved = resolved.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        if (resolved.matches(".*\\$\\{[^}]+}.*")) {
            throw new IllegalArgumentException("Unresolved placeholder in value: " + resolved);
        }
        return resolved;
    }
}
