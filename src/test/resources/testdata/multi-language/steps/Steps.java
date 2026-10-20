package demo;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

public class Steps {
    @Given("the java service is ready")
    public void ready() {}

    @Given("^the java user \"([^\"]*)\" exists$")
    public void user(String name) {}

    @When("the java client sends {int} request(s)")
    public void sends(int count) {}

    @Then("/the java total is (\\d+)/")
    public void total(String total) {}

    // @Given("the java commented step")
}
