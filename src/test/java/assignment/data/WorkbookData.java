package assignment.data;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/** Loads and checks the four Excel sheets used by the scenarios. */
public final class WorkbookData {
    // Sheet names and column order must match the workbook template.
    private static final Map<String, List<String>> SCHEMA = Map.of(
        "Scenarios", List.of("scenario_id", "execution_order", "scenario_name", "intended_outcome", "description"),
        "Steps", List.of("scenario_id", "step_order", "action", "locator_key", "input_key", "expected_key", "timeout_seconds"),
        "Data", List.of("key", "value", "purpose"),
        "Locators", List.of("key", "strategy", "selector", "purpose")
    );
    private final Map<String, List<Map<String, String>>> sheets;

    private WorkbookData(Map<String, List<Map<String, String>>> sheets) {
        this.sheets = Map.copyOf(sheets);
        validate();
    }

    // Open Excel read-only and close it after copying the rows.
    public static WorkbookData load(Path path) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(path.toFile(), null, true)) {
            return read(workbook);
        }
    }

    static WorkbookData read(Workbook workbook) {
        var formatter = new DataFormatter(Locale.ROOT);
        var result = new LinkedHashMap<String, List<Map<String, String>>>();
        for (var entry : SCHEMA.entrySet()) {
            String name = entry.getKey();
            var sheet = workbook.getSheet(name);
            require(sheet != null, "Missing sheet: " + name);
            Row header = sheet.getRow(0);
            require(header != null, "Missing header: " + name);
            List<String> columns = entry.getValue();
            for (int c = 0; c < columns.size(); c++) {
                require(columns.get(c).equals(formatter.formatCellValue(header.getCell(c))),
                    name + ": column " + (c + 1) + " must be " + columns.get(c));
            }
            var records = new ArrayList<Map<String, String>>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                var values = new LinkedHashMap<String, String>();
                for (int c = 0; c < columns.size(); c++) {
                    var cell = row.getCell(c);
                    // Read stored values only; formulas and cell errors are rejected.
                    require(cell == null || (cell.getCellType() != CellType.FORMULA
                        && cell.getCellType() != CellType.ERROR),
                        name + ": row " + (r + 1) + " requires literal values, not formulas or errors");
                    values.put(columns.get(c), formatter.formatCellValue(cell));
                }
                // Ignore empty rows left behind by spreadsheet formatting.
                if (values.values().stream().anyMatch(v -> !v.isBlank())) records.add(Map.copyOf(values));
            }
            require(!records.isEmpty(), "Empty sheet: " + name);
            result.put(name, List.copyOf(records));
        }
        return new WorkbookData(result);
    }

    public List<Map<String, String>> rows(String sheet) {
        if (!sheets.containsKey(sheet)) throw new IllegalArgumentException("Unknown sheet: " + sheet);
        return sheets.get(sheet);
    }

    // Run scenarios in their Excel order, not their physical row order.
    public List<Map<String, String>> scenarios() {
        return rows("Scenarios").stream()
            .sorted(Comparator.comparingInt(row -> positiveInt(row, "execution_order"))).toList();
    }

    // Keep login details and scenario inputs in Excel.
    public Map<String, String> dataValues() {
        return keyedValues("Data", "key", "value");
    }

    // Index selectors by the names used in the Steps sheet.
    public Map<String, LocatorDefinition> locators() {
        var result = new LinkedHashMap<String, LocatorDefinition>();
        for (var row : rows("Locators")) {
            result.put(row.get("key"), new LocatorDefinition(row.get("strategy"), row.get("selector")));
        }
        return Map.copyOf(result);
    }

    // Select this scenario's steps and sort them by step_order.
    public List<StepDefinition> stepsFor(String scenarioId) {
        return rows("Steps").stream()
            .filter(row -> scenarioId.equals(row.get("scenario_id")))
            .sorted(Comparator.comparingInt(row -> positiveInt(row, "step_order")))
            .map(row -> new StepDefinition(
                row.get("scenario_id"), positiveInt(row, "step_order"), row.get("action"),
                row.get("locator_key"), row.get("input_key"), row.get("expected_key"),
                positiveInt(row, "timeout_seconds")))
            .toList();
    }

    // Build a lookup from two named columns.
    private Map<String, String> keyedValues(String sheet, String keyColumn, String valueColumn) {
        var result = new LinkedHashMap<String, String>();
        for (var row : rows(sheet)) result.put(row.get(keyColumn), row.get(valueColumn));
        return Map.copyOf(result);
    }

    // Reject duplicate keys, missing references and unsupported actions before Chrome starts.
    private void validate() {
        Set<String> scenarios = uniqueKeys("Scenarios", "scenario_id");
        Set<String> data = uniqueKeys("Data", "key");
        Set<String> locators = uniqueKeys("Locators", "key");
        Set<Integer> orders = new HashSet<>();
        for (var row : rows("Scenarios")) {
            required(row, "scenario_name");
            require(Set.of("PASS", "FAIL").contains(row.get("intended_outcome")), "Invalid intended_outcome");
            require(orders.add(positiveInt(row, "execution_order")), "Duplicate scenario execution_order");
        }
        for (var row : rows("Data")) required(row, "value");
        for (var row : rows("Locators")) {
            required(row, "selector");
            require(Set.of("css", "id", "xpath", "name").contains(row.get("strategy")), "Invalid locator strategy");
        }
        var steps = new HashSet<String>();
        var covered = new HashSet<String>();
        for (var row : rows("Steps")) {
            String id = row.get("scenario_id");
            require(scenarios.contains(id), "Unknown scenario reference: " + id);
            covered.add(id);
            require(steps.add(id + ":" + positiveInt(row, "step_order")), "Duplicate step_order in " + id);
            positiveInt(row, "timeout_seconds");
            reference(row, "locator_key", locators);
            reference(row, "input_key", data);
            reference(row, "expected_key", data);
            // Each action requires different locator, input or expected-value columns.
            switch (row.get("action")) {
                case "OPEN" -> required(row, "input_key");
                case "TYPE" -> { required(row, "locator_key"); required(row, "input_key"); }
                case "CLICK", "CLICK_IF_PRESENT", "CLICK_AND_SWITCH", "CONTEXT_CLICK",
                     "REMEMBER_VALUE", "ASSERT_VALUE_CHANGED",
                     "SCROLL_INTO_VIEW", "SWITCH_TO_IFRAME",
                     "ASSERT_VISIBLE", "VERIFY_VISIBLE" -> required(row, "locator_key");
                case "SELECT_TEXT", "CHOOSE_OPTION", "SET_CHECKBOX", "CHANGE_CHECKBOX" -> {
                    required(row, "locator_key"); required(row, "input_key");
                }
                case "ASSERT_TEXT", "VERIFY_TEXT", "ASSERT_COUNT" -> {
                    required(row, "locator_key"); required(row, "expected_key");
                }
                case "ASSERT_URL_CONTAINS", "ASSERT_DOWNLOAD" -> required(row, "expected_key");
                case "PRINT_TO_PDF" -> required(row, "input_key");
                case "WAIT_FOR_LOADING_COMPLETE" -> required(row, "locator_key");
                case "ASSERT_NEW_ZIP", "WAIT_FOR_AUTHENTICATION", "SWITCH_TO_DEFAULT_CONTENT" -> { }
                default -> throw new IllegalArgumentException("Unsupported action: " + row.get("action"));
            }
        }
        require(covered.equals(scenarios), "Every scenario must have steps");
        boolean choosesOptions = rows("Steps").stream().anyMatch(row -> "CHOOSE_OPTION".equals(row.get("action")));
        require(!choosesOptions || locators.contains("dropdown_option"),
            "CHOOSE_OPTION steps need a dropdown_option locator");
    }

    // Blank or duplicate keys make workbook lookups ambiguous.
    private Set<String> uniqueKeys(String sheet, String column) {
        var keys = new HashSet<String>();
        for (var row : rows(sheet)) {
            required(row, column);
            require(keys.add(row.get(column)), "Duplicate " + sheet + " key: " + row.get(column));
        }
        return keys;
    }

    // A filled-in key must point to an existing row.
    private static void reference(Map<String, String> row, String column, Set<String> keys) {
        String value = row.get(column);
        require(value.isEmpty() || keys.contains(value), "Unknown " + column + " reference: " + value);
    }

    private static void required(Map<String, String> row, String column) {
        require(!row.get(column).isBlank(), "Missing required " + column);
    }

    // Step order and timeouts must be whole numbers greater than zero.
    private static int positiveInt(Map<String, String> row, String column) {
        try {
            int value = Integer.parseInt(row.get(column));
            require(value > 0, column + " must be positive");
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(column + " must be a positive integer", e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
