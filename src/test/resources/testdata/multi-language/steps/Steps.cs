using Reqnroll;

namespace Demo
{
    [Binding]
    public class Steps
    {
        [Given("the csharp service is ready")]
        public void Ready() { }

        [Given(@"the csharp user ""(.*)"" exists")]
        public void User(string name) { }

        [When("the csharp client sends {int} request(s)")]
        public void Sends(int count) { }

        [Then(@"^the csharp total is (\d+)$")]
        public void Total(int total) { }

        [StepDefinition("the csharp flag is on/off")]
        public void Flag() { }

        [Given("the csharp phrase is shared")]
        public void SharedGiven() { }

        [Then("the csharp phrase is shared")]
        public void SharedThen() { }

        // [Given("the csharp commented step")]
    }
}
