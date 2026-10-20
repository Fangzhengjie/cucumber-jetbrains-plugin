import { Given, When, Then } from '@cucumber/cucumber';

Given<object>('the ts service is ready', async function () {});
When('the ts flag is on/off', function () {});
Then('the ts item(s) count is {int}', function (count: number) {});
