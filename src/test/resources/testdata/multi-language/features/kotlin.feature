Feature: Kotlin steps
  Scenario: cucumber-jvm Kotlin patterns
    Given the kotlin service is ready
    When the kotlin client sends 8 requests
    Then the kotlin result is "fine"

  Scenario: doc strings close only on their own delimiter
    Given the kotlin service is ready
      ```
      """
      Given no step starts inside a doc string
      When the quotes above do not close it
      """
      ```
    Then the kotlin result is "fine"
