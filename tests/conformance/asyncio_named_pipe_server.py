# SPDX-License-Identifier: GPL-3.0-or-later
import os

# ProactorEventLoop.start_serving_pipe / create_pipe_connection: an echo server on a named pipe (Windows only).
print(os.name)

if os.name == "nt":
    import asyncio

    pipe = r"\\.\pipe\pyjvm-conf-asyncpipe-%d" % os.getpid()

    class Echo(asyncio.Protocol):
        def __init__(self, log):
            self.log = log
            self.transport = None

        def connection_made(self, transport):
            self.transport = transport
            self.log.append("server connection_made")

        def data_received(self, data):
            self.log.append(("server got", data))
            self.transport.write(data.upper())

        def connection_lost(self, exc):
            self.log.append(("server connection_lost", exc))

    class Client(asyncio.Protocol):
        def __init__(self, got, done):
            self.got = got
            self.done = done

        def connection_made(self, transport):
            self.transport = transport

        def data_received(self, data):
            self.got.append(data)
            if len(b"".join(self.got)) >= 11:
                self.transport.close()

        def connection_lost(self, exc):
            self.done.set_result(exc)

    async def main():
        loop = asyncio.get_running_loop()
        slog = []
        servers = await loop.start_serving_pipe(lambda: Echo(slog), pipe)
        print(len(servers), type(servers[0]).__name__)

        got = []
        done = loop.create_future()
        transport, protocol = await loop.create_pipe_connection(lambda: Client(got, done), pipe)
        print(type(transport).__name__, type(protocol).__name__)
        transport.write(b"hello ")
        await asyncio.sleep(0.1)
        transport.write(b"world")
        print(await asyncio.wait_for(done, 5))
        await asyncio.sleep(0.1)
        print(b"".join(got), slog[0], [x for x in slog[1:] if x[0] == "server got"] != [])
        print([x[0] if isinstance(x, tuple) else x for x in slog][-1])

        # a second client on the same server (the server re-arms its pipe)
        got2 = []
        done2 = loop.create_future()
        transport, _ = await loop.create_pipe_connection(lambda: Client(got2, done2), pipe)
        transport.write(b"abcdefghijk")
        await asyncio.wait_for(done2, 5)
        print(b"".join(got2))

        for s in servers:
            s.close()
        await asyncio.sleep(0.05)
        try:
            await loop.create_pipe_connection(lambda: Client([], loop.create_future()), pipe)
        except OSError as e:
            print(type(e).__name__, e.winerror)

    asyncio.run(main())
