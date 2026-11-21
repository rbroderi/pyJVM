#!/bin/bash
# Ticket 0 step 1: WSL pythons, ~/pyjvm-n0 clone and venvs, .venv314 in ~/pyjvm.
set -x
. ~/pyjvm-env.sh
uv python install --upgrade 3.13 3.14 3.15
if [ ! -d ~/pyjvm-n0 ]; then
  git clone /mnt/c/Users/richa/workspace_local/pyjvm ~/pyjvm-n0
fi
cd ~/pyjvm-n0 || exit 1
git fetch origin n0-fds
git checkout n0-fds
git log --oneline -1
for v in 313 314 315; do
  d=${v:0:1}.${v:1}
  [ -d .venv$v ] || uv venv --managed-python --python $d .venv$v
  uv pip install --python .venv$v/bin/python pytest
  .venv$v/bin/python -VV
done
cd ~/pyjvm
[ -d .venv314 ] || uv venv --managed-python --python 3.14 .venv314
uv pip install --python .venv314/bin/python pytest
.venv314/bin/python -VV
git status --short | head
