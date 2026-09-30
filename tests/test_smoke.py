from pathlib import Path
import shutil
import subprocess

from pyjvm315.compiler import compile_source


def test_class_magic():
    data = compile_source("print(1 + 2)", "Main")
    assert data[:4] == bytes.fromhex("CAFEBABE")
