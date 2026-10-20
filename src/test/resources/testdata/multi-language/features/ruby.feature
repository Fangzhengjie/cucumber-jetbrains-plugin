Feature: Ruby steps
  Scenario: cucumber-ruby patterns
    Given the ruby service is ready
    And the ruby user "grace" exists
    When the ruby client sends 11 requests
    Then the ruby total is 11
