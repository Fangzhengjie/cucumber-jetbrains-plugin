import static io.cucumber.groovy.EN.*

Given(~/^the groovy service is ready$/) { -> }
When(~'^the groovy client sends (\\d+) requests$') { int count -> }
Then("the groovy total is {int}") { int total -> }
