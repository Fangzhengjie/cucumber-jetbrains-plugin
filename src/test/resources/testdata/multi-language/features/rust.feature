Feature: Rust steps
  Scenario: cucumber-rs patterns
    Given the rust service is ready
    And the rust header "x-trace" is "abc"
    When the rust client sends 3 requests
    And the rust client sends 1 request
    Then the rust gateway received 1 request
    And the rust raw "value" pattern
    And the rust value is 2.5
    And the rust color is blue
