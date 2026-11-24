package assignment.data;

/** One Steps row; keys point to the Data and Locators sheets. */
public record StepDefinition(
    String scenarioId,
    int order,
    String action,
    String locatorKey,
    String inputKey,
    String expectedKey,
    int timeoutSeconds
) {}
