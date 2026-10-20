from behave import given, when, then, step, use_step_matcher

@given('the behave phrase is shared')
def step_impl(context):
    pass

@when('the behave phrase is shared')
def step_impl(context):
    pass

@step('the behave generic step runs')
def step_impl(context):
    pass

@given('the behave service is ready')
def step_impl(context):
    pass

@given('the behave user "{name}" exists')
def step_impl(context, name):
    pass

@when('the behave client sends {count:d} requests')
def step_impl(context, count):
    pass

use_step_matcher("re")

@then(r'the behave result is (?P<value>\w+)')
def step_impl(context, value):
    pass

@then(u"the behave value is (\\d+\\.\\d+)")
def step_impl(context, value):
    pass

# @given('the behave commented step')
