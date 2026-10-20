Feature: TypeScript steps
  Scenario: cucumber-js TypeScript patterns
    Given the ts service is ready
    When the ts flag is off
    Then the ts items count is 2
    And the ts item count is 1
