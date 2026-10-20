package steps

import "github.com/cucumber/godog"

func InitializeScenario(ctx *godog.ScenarioContext) {
	ctx.Step(`^the go service is ready$`, ready)
	ctx.Given("^the go user \"([^\"]*)\" exists$", userExists)
	ctx.When(`^the go client sends (\d+) requests?$`, sends)
	ctx.Then(`^the go result is (?P<value>\w+)$`, result)
	// ctx.Step(`^the go commented step$`, ready)
}
