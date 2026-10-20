Feature: JavaScript steps
  Scenario: cucumber-js patterns
    Given the js service is ready
    And the js user "dave" exists
    When the js client sends 6 requests
    Then the js total is 6
