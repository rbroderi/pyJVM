# N0 ticket 1: Spike S1 (scratch, nothing committed)

## Environment
- JDK: Temurin OpenJDK 25.0.5.1 (2026-11-17 LTS), `~/jdk-25`
- Kernel: 6.6.87.2-microsoft-standard-WSL2 (WSL2, Ubuntu 24.04), x86-64
- glibc: 2.39 (Ubuntu GLIBC 2.39-0ubuntu8.12)
- All numbers measured inside WSL. Test file on the Linux filesystem (`/tmp`), 256 MiB random data, deleted afterwards.

## Exact command
```
javac -d out Spike.java
java --enable-native-access=ALL-UNNAMED -cp out Spike <items>
```
No other JVM options. Run under `timeout 600`.

## Conventions used (1.3)
- `fn(name, descriptor, extra...)` builds every handle as `downcallHandle(defaultLookup().findOrThrow(name), d, captureCallState("errno") + extra)`. The call-state segment is the first `invokeExact` argument.
- `open` and `fcntl` use `Linker.Option.firstVariadicArg(2)` (`ioctl` not used).
- `malloc` and `free` are built without capture.
- Per-thread state: `ThreadLocal<Tl>` with `cs = Arena.ofAuto().allocate(captureStateLayout())` and `buf = Arena.ofAuto().allocate(65536)`.
- No `Linker.Option.critical`.
- errno is read at the offset of `captureStateLayout()` field `errno`.

## Results

### Item 1: open/read/write/close/pipe2/poll/fcntl from two threads. PASS
Two threads started together on a barrier, each with its own thread-local state. Each ran 2000 iterations of:
- open O_CREAT, write 1000 bytes, close, reopen, read back, compare, delete.
- `pipe2(O_CLOEXEC)`, `fcntl(F_GETFD)` == 1, `F_SETFD` 0, `F_GETFD` == 0.
- `poll(0)` on the empty pipe == 0, write 64 bytes, `poll(1000)` gives POLLIN, read back and compare, close both ends.

No errors. The two threads' buffers had distinct addresses.

### Item 2: errors. PASS
- `open` of a missing file: -1, errno 2.
- `read` on an O_WRONLY descriptor: -1, errno 9.
- 200000 iterations of failing `open`, with allocation between the call and the errno read (4 KiB or 1 MiB `byte[]`, an `Arena.ofAuto` 8 KiB segment, `System.gc()` every 5000 iterations): 0 mismatches.
- 50000 alternations of `open` (ENOENT 2) and `read` on a bad fd (EBADF 9), with 100 KB allocation: 0 mismatches.

Observation: a successful call does not clear the captured errno. After the successful 16 MiB `read` in item 6 the call-state segment held a stale errno 22 (EINVAL, from earlier JVM or libc activity). So errno must be read only after a failing return value.

### Item 3: SIGPIPE. PASS (JVM survived)
- `pipe2`, then `close(rfd)` returned 0.
- Verified closed: `fcntl(rfd, F_GETFD)` returned -1 with errno 9.
- `write(wfd, 16 bytes)` returned -1, errno 32 (EPIPE). The JVM was not killed and printed afterwards.
- Repeated on a second thread: -1/EPIPE.
- No `signal(SIGPIPE, SIG_IGN)` step is needed. The JVM already ignores SIGPIPE.

### Item 4: poll. PASS
`poll` with a 50 ms timeout on an empty pipe returned 0 each time. Five runs: 50.80, 50.18, 50.11, 50.11, 50.12 ms.

`poll` on a closed fd returned 1 with revents 0x20 (POLLNVAL), in 0.076 ms.

### Item 5: throughput. No hard fail criterion, FFM is not slower
256 MiB read in 64 KiB chunks. FFM does the downcall into the thread buffer, then copies into a `byte[]`. FileChannel reads into a heap `ByteBuffer.allocate(65536)`. Page cache warm (file just created), so this measures call overhead plus copy, not disk. One untimed warm-up run of each came before the timed runs. Runs were interleaved FFM then FC.

| run | FFM MB/s | FileChannel MB/s |
|---|---|---|
| 1 | 12136.6 | 11528.1 |
| 2 | 11149.2 | 10258.2 |
| 3 | 12567.4 | 12223.3 |
| mean | 11951.1 | 11336.5 |

Ratio FFM/FileChannel = 1.054.

Repeat JVM (same procedure):

| run | FFM MB/s | FileChannel MB/s |
|---|---|---|
| 1 | 11985.7 | 11294.6 |
| 2 | 12790.4 | 11248.4 |
| 3 | 12689.0 | 12387.9 |
| mean | 12488.4 | 11643.6 |

Ratio = 1.073.

A reverse-order pass (FC first) in the first JVM gave FFM 12927, 12938 and 11620 MB/s against FC 12526, 12668 and 12600 MB/s.

FFM is not slower than 2x FileChannel (it is about equal to slightly faster), so **no line is needed in plan section 7**.

### Item 6: malloc/free 16 MiB single read. PASS
- `malloc(16 MiB)` was non-NULL, then `reinterpret(16 MiB)`.
- One `read(2)` returned 16777216 bytes.
- Contents equal to a `FileChannel` read of the first 16 MiB.
- `free` in `finally` ran.

## Files left in `~/pyjvm-work/n0/spike/`
- `Spike.java` (single source for all items; item chosen by argument)
- `out/Spike.class`, `out/Spike$Tl.class`

The spike's `/tmp` files (`spike_big.bin` and the item 1 and 2 temp files) were deleted. Nothing was added to any git repo apart from this report.
