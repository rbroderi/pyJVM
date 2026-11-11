print("init alpha", __name__, __package__)
from . import beta
from .sub.leaf import LEAF
ALPHA = ("alpha", beta.beta_value, LEAF)
