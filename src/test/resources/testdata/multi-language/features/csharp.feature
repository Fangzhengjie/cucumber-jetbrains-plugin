Feature: C# steps
  Scenario: Reqnroll patterns
    Given the csharp service is ready
    And the csharp user "frank" exists
    When the csharp client sends 1 request
    Then the csharp total is 10
    And the csharp flag is on

  Scenario: Reqnroll binds definitions by keyword
    Given the csharp phrase is shared
    Then the csharp phrase is shared
    But the csharp phrase is shared
