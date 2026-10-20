use cucumber::{given, then, when, World};

#[derive(Debug, Default, World)]
struct DemoWorld;

#[given("the rust service is ready")]
async fn ready(_world: &mut DemoWorld) {}

#[given(expr = "the rust header {string} is {string}")]
async fn header(_world: &mut DemoWorld, _name: String, _value: String) {}

#[when(expr = "the rust client sends {int} request(s)")]
async fn sends(_world: &mut DemoWorld, _count: u32) {}

#[then(regex = r"^the rust gateway received (\d+) requests?$")]
async fn received(_world: &mut DemoWorld, _count: String) {}

#[then(regex = r#"^the rust raw "([^"]*)" pattern$"#)]
async fn raw(_world: &mut DemoWorld, _value: String) {}

#[then(expr = "the rust value is {float}")]
async fn value(_world: &mut DemoWorld, _value: f64) {}

#[then(expr = "the rust color is red/blue/green")]
async fn color(_world: &mut DemoWorld) {}

// #[given("the rust commented step")]
