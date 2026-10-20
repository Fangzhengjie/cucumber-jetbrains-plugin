Feature: Go steps
  Scenario: godog patterns
    Given the go service is ready
    And the go user "alice" exists
    When the go client sends 2 requests
    Then the go result is ok
