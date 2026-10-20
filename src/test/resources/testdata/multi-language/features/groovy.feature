Feature: Groovy steps
  Scenario: cucumber-groovy patterns
    Given the groovy service is ready
    When the groovy client sends 9 requests
    Then the groovy total is 9
