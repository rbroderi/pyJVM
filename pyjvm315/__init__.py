__version__ = "0.31.0"

from .versions import check_host as _check_host

_check_host()

from .compiler import compile_file, compile_source

__all__ = ["compile_file", "compile_source"]
