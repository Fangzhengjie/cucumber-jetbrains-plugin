Feature: pytest-bdd steps
  Scenario: pytest-bdd patterns
    Given the pytest-bdd service is ready
    And the pytest-bdd user "carol" exists
    When the pytest-bdd client sends 5 requests
    Then the pytest-bdd total is 5
