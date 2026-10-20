Feature: Java steps
  Scenario: cucumber-jvm Java patterns
    Given the java service is ready
    And the java user "erin" exists
    When the java client sends 7 requests
    Then the java total is 7
