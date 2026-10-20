package demo

import io.cucumber.java8.En

class KotlinSteps : En {
    @io.cucumber.java.en.Given("the kotlin service is ready")
    fun ready() {}

    init {
        When("the kotlin client sends {int} requests") { count: Int -> }
        Then("""^the kotlin result is "(\w+)"$""") { result: String -> }
    }
}
