print("init leaf", __name__, __package__)
from .. import beta
from ..beta import describe
LEAF = describe() + "/" + str(beta.beta_value)


class Thing:
    pass


def helper():
    return Thing().__class__.__module__
