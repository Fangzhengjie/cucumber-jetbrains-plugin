const { Given, When, Then } = require('@cucumber/cucumber');

Given('the js service is ready', function () {});
Given("the js user {string} exists", function (name) {});
When(/^the js client sends (\d+) requests?$/, function (count) {});
Then(`the js total is {int}`, function (total) {});
// Given('the js commented step', function () {});
