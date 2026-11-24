#!/bin/sh
# Stop if a script variable is unset.
set -u

# Use the private workbook unless WORKBOOK names another file.
workbook_path="${WORKBOOK:-data/scenarios.local.xlsx}"
if [ ! -f "${workbook_path}" ]; then
  printf 'Missing private workbook: %s\nCopy data/scenarios.xlsx to data/scenarios.local.xlsx and enter the Excel login/event values.\n' "${workbook_path}" >&2
  exit 2
fi

if [ "$(uname)" = "Darwin" ]; then
  # Scenario 1 right-clicks and saves the PDF with the real mouse and keyboard.
  printf 'Scenario 1 needs Accessibility and Screen Recording permission for the app running this script.\nKeep the mouse and keyboard still while the transcript is saved.\n'
fi

# clean removes earlier files under target, including saved reports.
mvn --batch-mode --no-transfer-progress clean test -Dheadless=false -Dworkbook="${workbook_path}"
# Keep Maven's result even after printing the report location.
maven_status=$?

report_path="target/assignment-report/report.html"
if [ -f "${report_path}" ]; then
  printf '\nAssignment report: %s\n' "${report_path}"
else
  printf '\nThe assignment report was not generated. Review the Maven output above.\n' >&2
fi

# The intended Scenario 4 failure also gives Maven a nonzero exit status.
exit "${maven_status}"
