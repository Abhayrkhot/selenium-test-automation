package assignment.data;

import org.openqa.selenium.By;

/** Converts a selector from Excel into a Selenium locator. */
public record LocatorDefinition(String strategy, String selector) {
    public By toBy() {
        return toBy(selector);
    }

    // Use the selector after any Excel placeholders have been filled in.
    public By toBy(String resolvedSelector) {
        return switch (strategy) {
            case "css" -> By.cssSelector(resolvedSelector);
            case "id" -> By.id(resolvedSelector);
            case "xpath" -> By.xpath(resolvedSelector);
            case "name" -> By.name(resolvedSelector);
            default -> throw new IllegalArgumentException("Unsupported locator strategy: " + strategy);
        };
    }
}
