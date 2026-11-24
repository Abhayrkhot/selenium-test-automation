package assignment.data;

import java.nio.file.Path;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

/** Infrastructure checks only; these do not execute the five browser scenarios. */
public class WorkbookValidationTest {
    private Path workbookPath() {
        return Path.of(System.getProperty("workbook"));
    }

    // Check workbook structure without logging in or opening Chrome.
    @Test
    public void suppliedWorkbookHasValidReferences() throws Exception {
        var data = WorkbookData.load(workbookPath());
        Assert.assertFalse(data.scenarios().isEmpty());
        System.out.printf("Validated %d scenario definitions and %d steps. Browser scenarios not executed.%n",
            data.scenarios().size(), data.rows("Steps").size());
    }

    // Require four positive scenarios and a ZIP check before the designed failure.
    @Test
    public void assignmentHasFiveOrderedScenariosAndNegativeDownload() throws Exception {
        var book = WorkbookData.load(workbookPath());
        Assert.assertEquals(book.scenarios().stream().map(row -> row.get("scenario_id")).toList(),
            java.util.List.of("S01", "S02", "S03", "S04", "S05"));
        Assert.assertEquals(book.scenarios().stream().map(row -> row.get("intended_outcome")).toList(),
            java.util.List.of("PASS", "PASS", "PASS", "FAIL", "PASS"));
        var negative = book.stepsFor("S04");
        Assert.assertEquals(negative.get(negative.size() - 1).action(), "ASSERT_DOWNLOAD");
        Assert.assertEquals(negative.get(negative.size() - 1).expectedKey(), "negative_expected_filename");
        Assert.assertEquals(negative.get(negative.size() - 2).action(), "ASSERT_NEW_ZIP");
    }

    // Both event-title checks must expect exactly one visible match.
    @Test
    public void eventChecksRequireOneVisibleMatch() throws Exception {
        var book = WorkbookData.load(workbookPath());
        var checks = book.stepsFor("S02").stream()
            .filter(step -> step.action().equals("ASSERT_COUNT")).toList();
        Assert.assertEquals(checks.size(), 2);
        for (var check : checks) Assert.assertEquals(book.dataValues().get(check.expectedKey()), "1");
    }

    // Check that key actions exist; this does not prove live-site compliance.
    @Test
    public void assignmentIncludesRequiredTransitions() throws Exception {
        var book = WorkbookData.load(workbookPath());
        Assert.assertEquals(book.stepsFor("S02").stream().filter(step -> step.action().equals("REMEMBER_VALUE")).count(), 2L);
        Assert.assertEquals(book.stepsFor("S02").stream().filter(step -> step.action().equals("ASSERT_VALUE_CHANGED")).count(), 2L);
        Assert.assertTrue(book.stepsFor("S05").stream().anyMatch(step -> step.action().equals("CHANGE_CHECKBOX")));
        Assert.assertTrue(book.stepsFor("S01").stream().anyMatch(step -> step.locatorKey().equals("transcript_submit")));
        Assert.assertTrue(book.stepsFor("S01").stream().anyMatch(step -> step.locatorKey().equals("student_hub_link")));
    }

    @Test
    public void missingInputReferenceIsRejected() throws Exception {
        try (var book = WorkbookFactory.create(workbookPath().toFile(), null, true)) {
            // Remove a referenced input; it must never silently resolve to a default.
            var sheet = book.getSheet("Data");
            sheet.removeRow(sheet.getRow(1));
            var error = Assert.expectThrows(IllegalArgumentException.class, () -> WorkbookData.read(book));
            Assert.assertTrue(error.getMessage().contains("Unknown input_key reference"));
        }
    }

    // Duplicate scenario IDs must fail validation.
    @Test
    public void duplicateScenarioIsRejected() throws Exception {
        try (var book = WorkbookFactory.create(workbookPath().toFile(), null, true)) {
            var sheet = book.getSheet("Scenarios");
            sheet.getRow(2).getCell(0).setCellValue(sheet.getRow(1).getCell(0).getStringCellValue());
            var error = Assert.expectThrows(IllegalArgumentException.class, () -> WorkbookData.read(book));
            Assert.assertTrue(error.getMessage().contains("Duplicate Scenarios key"));
        }
    }

    // Two steps in one scenario cannot share an order number.
    @Test
    public void duplicateStepIsRejected() throws Exception {
        try (var book = WorkbookFactory.create(workbookPath().toFile(), null, true)) {
            var sheet = book.getSheet("Steps");
            sheet.getRow(2).getCell(1).setCellValue(sheet.getRow(1).getCell(1).getNumericCellValue());
            var error = Assert.expectThrows(IllegalArgumentException.class, () -> WorkbookData.read(book));
            Assert.assertTrue(error.getMessage().contains("Duplicate step_order"));
        }
    }
}
