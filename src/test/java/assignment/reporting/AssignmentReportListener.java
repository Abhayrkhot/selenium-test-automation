package assignment.reporting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testng.ISuite;
import org.testng.ISuiteListener;

/** Builds the HTML report after the suite, including failed scenarios. */
public final class AssignmentReportListener implements ISuiteListener {
    public static final Path REPORT_ROOT = Path.of("target", "assignment-report");
    // Screenshot names look like 06-click-before.png.
    private static final Pattern STEP_SCREENSHOT = Pattern.compile("^(\\d+)-.*-(before|after)\\.png$");

    // One line in the report. Step 0 means the line is not tied to one step.
    public record Line(int step, String text) {}

    // One result row, its screenshot folder and any designed-failure label.
    public record Entry(int order, String scenarioName, String description, List<Line> actual,
                        List<Line> expected, boolean passed, boolean designedFailure, Path screenshotDirectory) {}
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
            long failed = entries.size() - passed;
            StringBuilder rows = new StringBuilder();
            for (Entry entry : entries) rows.append(row(entry));
            String runTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a"));
            String body = """
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Selenium assignment report</title><style>%s</style></head><body><main>
                <header>
                <h1>Selenium assignment report</h1>
                <p class="meta">Run on %s</p>
                <div class="stats">
                <div class="stat"><b>%d</b><span>Scenarios</span></div>
                <div class="stat pass"><b>%d</b><span>Passed</span></div>
                <div class="stat fail"><b>%d</b><span>Failed</span></div>
                </div>
                <p class="summary">Executed %d scenarios: %d passed and %d failed.</p>
                <p class="note">Scenario 4 intentionally checks an incorrect download filename.</p>
                </header>
                <table><colgroup><col class="name"><col class="actual"><col class="expected"><col class="status"></colgroup>
                <thead><tr><th>Test scenario name</th><th>Actual</th><th>Expected</th><th>Pass/Fail</th></tr></thead>
                <tbody>%s</tbody></table>
                </main></body></html>
                """.formatted(styles(), html(runTime), entries.size(), passed, failed,
                    entries.size(), passed, failed, rows);
            Files.writeString(REPORT_ROOT.resolve("report.html"), body, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Could not write assignment HTML report", error);
        }
    }

    private String row(Entry entry) throws IOException {
        String status = entry.passed() ? "PASS" : "FAIL";
        String statusClass = entry.passed() ? "pass" : "fail";
        // Label the expected negative test without turning its FAIL into PASS.
        String designed = entry.designedFailure() ? "<div class=\"designed\">Designed failure</div>" : "";
        var shots = new Screenshots(entry.screenshotDirectory());
        String expected = "<p class=\"desc\">" + html(entry.description()) + "</p>"
            + (entry.expected().isEmpty() ? "<p>Every step finishes without an error.</p>"
                : stepList(entry.expected(), null));
        return "<tr><td><span class=\"scenario-no\">Scenario " + entry.order() + "</span>"
            + "<span class=\"scenario-name\">" + html(entry.scenarioName()) + "</span></td>"
            + "<td>" + stepList(entry.actual(), shots) + shots.leftovers() + "</td>"
            + "<td>" + expected + "</td>"
            + "<td><span class=\"badge " + statusClass + "\">" + status + "</span>" + designed + "</td></tr>";
    }

    // Each line becomes "Step N  text", with that step's screenshots when they exist.
    private static String stepList(List<Line> lines, Screenshots shots) {
        if (lines.isEmpty()) return "<p>No steps ran.</p>";
        StringBuilder list = new StringBuilder("<ol class=\"steps\">");
        for (Line line : lines) {
            String kind = line.text().startsWith("Failed: ") ? " class=\"failed\""
                : line.step() == 0 ? " class=\"info\"" : "";
            list.append("<li").append(kind).append("><span class=\"no\">")
                .append(line.step() > 0 ? "Step " + line.step() : "").append("</span><span class=\"text\">")
                .append(html(capitalize(line.text()))).append("</span>")
                .append(shots == null ? "" : shots.linksFor(line.step())).append("</li>");
        }
        return list.append("</ol>").toString();
    }

    // Lines can start with an Excel key such as "submit times", so start them with a capital.
    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    // Groups a scenario's screenshots by step so each step links to its own images.
    private static final class Screenshots {
        private final Map<Integer, List<Path>> byStep = new LinkedHashMap<>();
        private final List<Path> others = new ArrayList<>();
        private final java.util.Set<Integer> shown = new HashSet<>();

        Screenshots(Path directory) throws IOException {
            if (directory == null || !Files.isDirectory(directory)) return;
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".png"))
                        .sorted(Comparator.comparing(AssignmentReportListener::screenshotSortKey)).toList()) {
                    Matcher match = STEP_SCREENSHOT.matcher(file.getFileName().toString());
                    if (match.matches()) byStep.computeIfAbsent(Integer.parseInt(match.group(1)), step -> new ArrayList<>()).add(file);
                    else others.add(file);
                }
            }
        }

        // Link a step's images once, even if the step has more than one line.
        String linksFor(int step) {
            if (step <= 0 || !shown.add(step) || !byStep.containsKey(step)) return "";
            StringBuilder links = new StringBuilder("<span class=\"shots\">");
            for (Path file : byStep.get(step)) {
                String name = file.getFileName().toString();
                links.append(link(file, name.endsWith("-before.png") ? "before" : "after"));
            }
            return links.append("</span>").toString();
        }

        // Anything not linked to a step, such as the saved Print search screen.
        String leftovers() {
            var rest = new ArrayList<>(others);
            byStep.forEach((step, files) -> { if (!shown.contains(step)) rest.addAll(files); });
            if (rest.isEmpty()) return byStep.isEmpty() ? "<p class=\"other\">No screenshots captured</p>" : "";
            StringBuilder links = new StringBuilder("<p class=\"other\">Other screenshots: ");
            for (Path file : rest) links.append(link(file, file.getFileName().toString()));
            return links.append("</p>").toString();
        }

        private static String link(Path file, String text) {
            // Relative links still work when the report folder is moved.
            String href = REPORT_ROOT.relativize(file).toString().replace('\\', '/');
            return "<a href=\"" + html(href) + "\" title=\"" + html(file.getFileName().toString()) + "\">"
                + html(text) + "</a>";
        }
    }

    // Show each before image ahead of its matching after image.
    private static String screenshotSortKey(Path path) {
        return path.getFileName().toString().replace("-before.png", "-0-before.png")
            .replace("-after.png", "-1-after.png");
    }

    private static String styles() {
        return """
            :root{--bg:#f5f6f8;--card:#fff;--line:#e4e7ec;--text:#1d2939;--muted:#667085;--pass:#067647;
            --pass-bg:#ecfdf3;--fail:#b42318;--fail-bg:#fef3f2;--link:#175cd3;--link-bg:#eff4ff}
            *{box-sizing:border-box}
            body{margin:0;background:var(--bg);color:var(--text);font:14px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif}
            main{max-width:1500px;margin:0 auto;padding:32px 24px 48px}
            header{margin-bottom:24px}
            h1{font-size:26px;margin:0 0 4px}
            .meta{color:var(--muted);margin:0 0 16px}
            .stats{display:flex;gap:12px;flex-wrap:wrap;margin-bottom:12px}
            .stat{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:12px 18px;min-width:120px}
            .stat b{display:block;font-size:24px}
            .stat span{color:var(--muted);font-size:12px;text-transform:uppercase;letter-spacing:.04em}
            .stat.pass b{color:var(--pass)}
            .stat.fail b{color:var(--fail)}
            .summary{font-weight:600;margin:8px 0 2px}
            .note{color:var(--muted);margin:0}
            table{width:100%;border-collapse:separate;border-spacing:0;background:var(--card);border:1px solid var(--line);border-radius:12px;overflow:hidden;table-layout:fixed}
            col.name{width:15%}
            col.actual{width:45%}
            col.expected{width:30%}
            col.status{width:10%}
            th{background:#f9fafb;color:var(--muted);font-size:12px;text-transform:uppercase;letter-spacing:.05em;text-align:left;padding:12px 16px;border-bottom:1px solid var(--line)}
            td{padding:16px;border-bottom:1px solid var(--line);vertical-align:top;overflow-wrap:anywhere}
            tr:last-child td{border-bottom:0}
            .scenario-no{display:block;color:var(--muted);font-size:12px;font-weight:600;text-transform:uppercase;letter-spacing:.04em}
            .scenario-name{font-weight:600;font-size:15px}
            ol.steps{list-style:none;margin:0;padding:0}
            ol.steps li{display:flex;gap:10px;align-items:baseline;padding:6px 0;border-bottom:1px dashed var(--line)}
            ol.steps li:last-child{border-bottom:0}
            .no{flex:0 0 56px;color:var(--muted);font-size:12px;font-weight:600;white-space:nowrap}
            .text{flex:1;min-width:0}
            .shots{flex:0 0 auto;display:flex;gap:4px}
            .shots a,.other a{color:var(--link);background:var(--link-bg);border-radius:6px;padding:1px 7px;font-size:11px;text-decoration:none;white-space:nowrap}
            .shots a:hover,.other a:hover{text-decoration:underline}
            ol.steps li.failed{background:var(--fail-bg);color:var(--fail);border-radius:6px;padding:6px 8px;border-bottom:0}
            ol.steps li.info{color:var(--muted);font-style:italic}
            .desc{margin:0 0 10px}
            .badge{display:inline-block;font-weight:700;font-size:12px;padding:4px 12px;border-radius:999px}
            .badge.pass{color:var(--pass);background:var(--pass-bg)}
            .badge.fail{color:var(--fail);background:var(--fail-bg)}
            .designed{margin-top:6px;font-size:12px;color:var(--muted)}
            .other{margin:10px 0 0;font-size:12px;color:var(--muted)}
            .other a{margin-left:4px}
            @media (max-width:900px){table,thead,tbody,tr,td,th,colgroup{display:block}thead,colgroup{display:none}tr{border-bottom:1px solid var(--line)}td{border-bottom:0}}
            """;
    }

    // Escape workbook values and browser messages before inserting them into HTML.
    private static String html(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
