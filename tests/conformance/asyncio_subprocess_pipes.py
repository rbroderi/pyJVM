# SPDX-License-Identifier: GPL-3.0-or-later
import os

# asyncio subprocesses with pipes on the Windows ProactorEventLoop (Windows only).
print(os.name)

if os.name == "nt":
    import asyncio
    import subprocess

    comspec = os.environ["ComSpec"]

    async def main():
        p = await asyncio.create_subprocess_exec(comspec, "/c", "echo", "hi", stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        out, err = await p.communicate()
        print(out, err, p.returncode, type(p.pid).__name__)

        # stdin through communicate
        p = await asyncio.create_subprocess_exec("sort", stdin=subprocess.PIPE, stdout=subprocess.PIPE)
        out, err = await p.communicate(b"pear\r\napple\r\nfig\r\n")
        print(out, err, p.returncode)

        # exit codes and stderr
        p = await asyncio.create_subprocess_exec(comspec, "/c", "echo e1>&2&exit 3", stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        out, err = await p.communicate()
        print(out, err, p.returncode)
        p = await asyncio.create_subprocess_exec(comspec, "/c", "exit 0")
        print(await p.wait(), p.returncode)

        # reading the stdout stream by line
        p = await asyncio.create_subprocess_exec(comspec, "/c", "echo one&echo two", stdout=subprocess.PIPE)
        lines = []
        while True:
            line = await p.stdout.readline()
            if not line:
                break
            lines.append(line)
        await p.wait()
        print(lines, p.returncode)

        # two children at once
        async def run(text):
            q = await asyncio.create_subprocess_exec(comspec, "/c", "echo", text, stdout=subprocess.PIPE)
            o, _ = await q.communicate()
            return o

        print(await asyncio.gather(run("first"), run("second")))

        # create_subprocess_shell
        p = await asyncio.create_subprocess_shell("echo shell", stdout=subprocess.PIPE)
        out, _ = await p.communicate()
        print(out, p.returncode)

        # terminate a long-running child
        p = await asyncio.create_subprocess_exec(comspec, "/c", "ping -n 30 127.0.0.1 >nul", stdout=subprocess.PIPE)
        p.terminate()
        print(await p.wait() != 0)
        try:
            await asyncio.create_subprocess_exec("pyjvm-no-such-program-xyz")
        except FileNotFoundError as e:
            print("FileNotFoundError", e.winerror)

    asyncio.run(main())
