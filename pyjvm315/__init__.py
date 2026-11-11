# SPDX-License-Identifier: GPL-3.0-or-later
__version__ = "0.31.0"

from .versions import check_host as _check_host

_check_host()

from .compiler import compile_source
from .driver import compile_file

__all__ = ["compile_file", "compile_source"]
