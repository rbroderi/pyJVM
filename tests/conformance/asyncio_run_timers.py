# SPDX-License-Identifier: GPL-3.0-or-later
import os

# asyncio.run on the Windows ProactorEventLoop: timers, tasks, call_soon_threadsafe and executors (Windows only; the
# POSIX runtime has no sockets for the selector loop yet, so on POSIX both interpreters print just the first line).
print(os.name)

if os.name == "nt":
    import asyncio
    import threading
    import time

    print(asyncio.run(asyncio.sleep(0, "done")))

    log = []

    async def worker(name, delay):
        await asyncio.sleep(delay)
        log.append(name)
        return name

    async def main():
        loop = asyncio.get_running_loop()
        print(type(loop).__name__, loop.is_running(), loop.is_closed())

        # sleeps finish in time order, gather keeps argument order
        print(await asyncio.gather(worker("a", 0.12), worker("b", 0.04), worker("c", 0.08)), log)

        # call_later / call_at / call_soon ordering
        order = []
        done = loop.create_future()
        loop.call_later(0.10, order.append, "later 0.10")
        loop.call_later(0.05, order.append, "later 0.05")
        loop.call_at(loop.time() + 0.15, lambda: (order.append("call_at"), done.set_result(None)))
        loop.call_soon(order.append, "soon 1")
        loop.call_soon(order.append, "soon 2")
        await done
        print(order)

        # cancelled timer handles never run
        h = loop.call_later(0.05, order.append, "cancelled")
        h.cancel()
        await asyncio.sleep(0.12)
        print("cancelled" in order, h.cancelled())

        # wait_for: a timeout and a result
        t0 = time.monotonic()
        try:
            await asyncio.wait_for(asyncio.sleep(5), 0.1)
        except TimeoutError:
            print("TimeoutError", time.monotonic() - t0 < 2)
        print(await asyncio.wait_for(worker("w", 0.02), 2))

        # tasks: cancellation and exceptions
        async def boom():
            await asyncio.sleep(0.01)
            raise ValueError("boom")

        t = asyncio.create_task(boom())
        try:
            await t
        except ValueError as e:
            print("ValueError", e, t.done(), type(t.exception()).__name__)
        t = asyncio.create_task(asyncio.sleep(5))
        await asyncio.sleep(0)
        t.cancel()
        try:
            await t
        except asyncio.CancelledError:
            print("CancelledError", t.cancelled())
        res = await asyncio.gather(boom(), worker("g", 0.02), return_exceptions=True)
        print([type(r).__name__ if isinstance(r, BaseException) else r for r in res])

        # call_soon_threadsafe from another thread wakes the loop
        fut = loop.create_future()
        thread = threading.Thread(target=lambda: (time.sleep(0.05), loop.call_soon_threadsafe(fut.set_result, "from thread")))
        thread.start()
        print(await fut)
        thread.join()

        # run_in_executor
        print(await loop.run_in_executor(None, lambda x: x * 2, 21))
        print(await asyncio.to_thread(lambda: threading.current_thread() is not threading.main_thread()))

        # queues and events
        q = asyncio.Queue()
        ev = asyncio.Event()

        async def producer():
            for i in range(3):
                await q.put(i)
                await asyncio.sleep(0.01)
            ev.set()

        p = asyncio.create_task(producer())
        got = [await q.get() for _ in range(3)]
        await ev.wait()
        await p
        print(got, ev.is_set())

        async with asyncio.timeout(1):
            await asyncio.sleep(0.01)
        try:
            async with asyncio.timeout(0.05):
                await asyncio.sleep(1)
        except TimeoutError:
            print("timeout block")
        return "main done"

    print(asyncio.run(main()))

    # a second run gets a new loop; the loop is closed afterwards
    holder = []

    async def grab():
        holder.append(asyncio.get_running_loop())

    asyncio.run(grab())
    asyncio.run(grab())
    print(holder[0] is not holder[1], holder[0].is_closed(), holder[1].is_closed())
    loop = asyncio.new_event_loop()
    print(type(loop).__name__, loop.run_until_complete(worker("x", 0.01)))
    loop.close()
    print(loop.is_closed())
    coro = worker("y", 0)
    try:
        loop.run_until_complete(coro)
    except RuntimeError as e:
        print("RuntimeError", e)
    coro.close()
