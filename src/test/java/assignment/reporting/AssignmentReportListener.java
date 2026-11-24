package assignment.reporting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.testng.ISuite;
import org.testng.ISuiteListener;

/** Builds the HTML report after the suite, including failed scenarios. */
public final class AssignmentReportListener implements ISuiteListener {
    public static final Path REPORT_ROOT = Path.of("target", "assignment-report");

    // One result row, its screenshot folder and any designed-failure label.
    public record Entry(int order, String scenarioName, String actual, String expected,
                        boolean passed, boolean designedFailure, Path screenshotDirectory) {}
    private static final List<Entry> ENTRIES = new ArrayList<>();
    public static void add(Entry entry) { ENTRIES.add(entry); }
    // Do not carry results into a second suite in the same process.
    @Override public void onStart(ISuite suite) { ENTRIES.clear(); }

    @Override public void onFinish(ISuite suite) {
        try {
            Files.createDirectories(REPORT_ROOT);
            // Keep report rows in the workbook's scenario order.
            List<Entry> entries = ENTRIES.stream().sorted(Comparator.comparingInt(Entry::order)).toList();
            long passed = entries.stream().filter(Entry::passed).count();
            StringBuilder rows = new StringBuilder();
            for (Entry entry : entries) rows.append(row(entry));
            String body = """
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Selenium assignment report</title><style>%s</style></head><body><main>
                <h1>Selenium assignment report</h1>
                <p class="summary">Executed %d scenarios: %d passed and %d failed.</p>
                <p>Scenario 4 intentionally checks an incorrect download filename.</p>
                <table><thead><tr><th>Test scenario name</th><th>Actual</th><th>Expected</th><th>Pass/Fail</th></tr></thead>
                <tbody>%s</tbody></table>
                </main></body></html>
                """.formatted(styles(), entries.size(), passed, entries.size() - passed, rows);
            Files.writeString(REPORT_ROOT.resolve("report.html"), body, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Could not write assignment HTML report", error);
        }
    }

    private String row(Entry entry) throws IOException {
        String status = entry.passed() ? "PASS" : "FAIL";
        String statusClass = entry.passed() ? "pass" : "fail";
        // Label the expected negative test without turning its FAIL into PASS.
        String designed = entry.designedFailure() ? "<div class=\"note\">Designed failure</div>" : "";
        String evidence = screenshotLinks(entry.screenshotDirectory());
        return "<tr><td>" + html(entry.scenarioName()) + "</td><td>" + html(entry.actual())
            + "<div class=\"evidence\">" + evidence + "</div>"
            + "</td><td>" + html(entry.expected()) + "</td><td class=\"" + statusClass + "\">"
            + status + designed + "</td></tr>";
    }

    private String screenshotLinks(Path directory) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) return "No screenshots captured";
        try (var files = Files.list(directory)) {
            List<Path> screenshots = files.filter(path -> path.getFileName().toString().endsWith(".png"))
                .sorted(Comparator.comparing(AssignmentReportListener::screenshotSortKey)).toList();
            if (screenshots.isEmpty()) return "No screenshots captured";
            StringBuilder links = new StringBuilder();
            for (Path screenshot : screenshots) {
                // Relative links still work when the report folder is moved.
                String href = REPORT_ROOT.relativize(screenshot).toString().replace('\\', '/');
                if (!links.isEmpty()) links.append(' ');
                links.append("<a href=\"").append(html(href)).append("\">Screenshot ")
                    .append(html(screenshot.getFileName().toString())).append("</a>");
            }
            return links.toString();
        }
    }

    // Show each before image ahead of its matching after image.
    private static String screenshotSortKey(Path path) {
        return path.getFileName().toString().replace("-before.png", "-0-before.png")
            .replace("-after.png", "-1-after.png");
    }

    private static String styles() {
        return "body{font:15px system-ui;background:#f4f7fb;color:#172033;margin:0}main{max-width:1400px;margin:40px auto;padding:0 24px}h1{margin-bottom:8px}.summary{font-weight:700}table{width:100%;border-collapse:collapse;background:white;box-shadow:0 6px 24px #18315318}th{background:#243b53;color:white;text-align:left}th,td{padding:14px;border-bottom:1px solid #d9e2ec;vertical-align:top}td:nth-child(1){font-weight:700;min-width:180px}td:nth-child(2),td:nth-child(3){max-width:420px;word-break:break-word}.pass{color:#16794b;font-weight:800}.fail{color:#b42318;font-weight:800}.note{font-size:12px;font-weight:500;margin-top:4px}.evidence{margin-top:8px}a{display:inline-block;margin:2px;color:#1f5d99;font-size:12px}";
    }

    // Escape workbook values and browser messages before inserting them into HTML.
    private static String html(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
