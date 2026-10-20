Feature: behave steps
  Scenario: behave patterns
    Given the behave service is ready
    And the behave user "bob" exists
    When the behave client sends 4 requests
    Then the behave result is done
    And the behave value is 1.25

  Scenario: behave binds definitions by keyword
    Given the behave phrase is shared
    When the behave phrase is shared
    And the behave phrase is shared
    Then the behave generic step runs
