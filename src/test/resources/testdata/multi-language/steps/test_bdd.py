from pytest_bdd import given, when, then, parsers

@given("the pytest-bdd service is ready")
def ready():
    pass

@given(parsers.parse('the pytest-bdd user "{name}" exists'))
def user(name):
    pass

@when(parsers.re(r"the pytest-bdd client sends (?P<count>\d+) requests?"))
def sends(count):
    pass

@then(parsers.cfparse("the pytest-bdd total is {total:d}"), target_fixture="total")
def total(total):
    return total
