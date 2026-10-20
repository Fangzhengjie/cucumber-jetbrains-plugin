#[given("the shared step")]
async fn exact(_w: &mut W) {}

#[given(expr = "the {word} step")]
async fn any(_w: &mut W, _x: String) {}

#[given("a unique step")]
async fn unique(_w: &mut W) {}
