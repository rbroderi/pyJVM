# Plan: `_blake2` and `_sha3` for pyJVM (HANDOFF open item 2a; written by a planning agent, 2026-11-16)

Scope: the two missing builtin digest modules on all three lanes (3.13 / 3.14 / 3.15), so that `hashlib.blake2b`, `blake2s`, `sha3_224/256/384/512`, `shake_128/256` exist and `import hashlib` stops logging "code for hash ... was not found" (3.13/3.14) or installing raising stubs (3.15). Not in scope: `_hashlib` (OpenSSL: `pbkdf2_hmac`, `scrypt`, `hashlib.new("ripemd160")`), `_hmac` (3.14+ accelerator; `hmac.py` falls back to pure Python when it is absent).

## 1. Goal
`import _blake2, _sha3` work on every lane with CPython's exact Python-visible surface, digests and error messages; `hashlib.algorithms_available == algorithms_guaranteed` (14 names); `hmac.new(key, msg, "blake2b"/"sha3_256")` and `hashlib.file_digest(f, "blake2s")` work through the existing pure-Python paths.

## 2. Current state (file:line)
- `runtime/pyjvm315/runtime/HashModule.java` (122 lines): `_md5`, `_sha1`, `_sha2` over `java.security.MessageDigest`. One `Algo` table (lines 26-33), state class `St` (line 37), `create(module, names...)` (39-59) defines one class per algorithm with `NativeTypes.define/method/finish`, properties through `PyRuntime.classAddProperty` (51-53), module-level constructor `fn(m, n, ...)` (56). `construct` (61-77) parses `data`/`string`/`usedforsecurity`. `update` (89-97) has the two CPython messages ("Strings must be encoded before hashing", "object supporting the buffer API required"). `copy` clones the MessageDigest (113-117).
- Registration: `ImportSupport.java:37` (`NATIVE_NAMES` has `_md5`, `_sha1`, `_sha2`), `ImportSupport.java:83` (the `createNative` switch), and the compiler's mirror `pyjvm315/compiler.py:57` (`NATIVE_MODULES`).
- Existing case: `tests/conformance/hash_builtin_modules.py` (md5/sha1/sha2 only).
- `STDLIB_STATUS.md:231` and `:510`: `hashlib` is `smoke-mismatch` (missing `pbkdf2_hmac`, `scrypt`); that stays (no `_hashlib`).
- Defects in the existing module found while probing (fold into this work, they share the constructor code):
  - `HashModule.java:69`: positional data plus `string=` raises "argument for md5() given by name ('string') and position (1)"; CPython (all three lanes) raises `TypeError: 'data' and 'string' are mutually exclusive and support for 'string' keyword parameter is slated for removal in a future version.` The same text is used for `data=` plus `string=`.
  - 3.15: `string=` emits `DeprecationWarning("the 'string' keyword parameter is deprecated since Python 3.15 and slated for removal in Python 3.19; use the 'data' keyword parameter or pass the data to hash as a positional argument instead")`. Not emitted here.
  - 3.14+: every hash module has the module attribute `_GIL_MINSIZE = 2048` (`_md5`, `_sha1`, `_sha2`, `_blake2`, `_sha3`); absent on 3.13. Not present here.

## 3. CPython reference behaviour (measured on C:\Python313 3.13.17, C:\Python314 3.14.10, C:\Python315 3.15.2; probe scripts and raw output in the session scratchpad, re-create with the snippets in section 7)
What the JDK gives: `MessageDigest` has `SHA3-224/256/384/512` (since 9); JDK 25 has only the fixed-output `SHAKE128-256`/`SHAKE256-512` digests (no variable-length XOF API), and no BLAKE2 at all. So SHAKE and BLAKE2 must be implemented.

### 3.1 `_blake2`
- Module names: `blake2b`, `blake2s` (types, not functions; `_blake2.blake2b is hashlib.blake2b`), `BLAKE2B_SALT_SIZE 16`, `BLAKE2B_PERSON_SIZE 16`, `BLAKE2B_MAX_KEY_SIZE 64`, `BLAKE2B_MAX_DIGEST_SIZE 64`, `BLAKE2S_SALT_SIZE 8`, `BLAKE2S_PERSON_SIZE 8`, `BLAKE2S_MAX_KEY_SIZE 32`, `BLAKE2S_MAX_DIGEST_SIZE 32`; 3.14+ also `_GIL_MINSIZE 2048`.
- Types: `__module__ '_blake2'`, `__name__`/`__qualname__` `blake2b`/`blake2s`, mro `[blake2b, object]`, not subclassable (`TypeError: type '_blake2.blake2b' is not an acceptable base type`), immutable (`TypeError: cannot set 'x' attribute of immutable type '_blake2.blake2b'`), not picklable (`TypeError: cannot pickle '_blake2.blake2b' object`), `__doc__` "Return a new BLAKE2b hash object." / "...BLAKE2s...". Class attributes (ints): `SALT_SIZE`, `PERSON_SIZE`, `MAX_KEY_SIZE`, `MAX_DIGEST_SIZE`. Instance: `name` ('blake2b'/'blake2s'), `digest_size` (the requested size), `block_size` (128 / 64), methods `update(data, /)`, `digest()`, `hexdigest()`, `copy()`. Instances have no `__dict__`; `name` is read-only ("attribute 'name' of '_blake2.blake2b' objects is not writable").
- Signature (same on all lanes): `blake2b(data=b'', *, digest_size=MAX_DIGEST_SIZE, key=b'', salt=b'', person=b'', fanout=1, depth=1, leaf_size=0, node_offset=0, node_depth=0, inner_size=0, last_node=False, usedforsecurity=True, string=None)`. `data` may be positional or keyword; more than one positional: `TypeError: blake2b() takes at most 1 positional argument (2 given)`; unknown keyword: `blake2b() got an unexpected keyword argument 'foo'`. `last_node` is truth-tested (any object). `key`/`salt`/`person` take any bytes-like; `None` or `str`: `TypeError: a bytes-like object is required, not 'NoneType'`.
- Validation messages, per lane:

| Check | 3.13 | 3.14 | 3.15 |
| --- | --- | --- | --- |
| digest_size out of 1..N | `digest_size must be between 1 and 64 bytes` | `digest_size for Blake2b must be between 1 and 64 bytes, here it is 65` (`Blake2s`, 32) | `digest_size for blake2b must be between 1 and 64 bytes, got 65` |
| key too long | `maximum key length is 64 bytes` | same | `maximum key length is 64 bytes, got 65` |
| salt too long | `maximum salt length is 16 bytes` | same | `..., got 17` |
| person too long | `maximum person length is 16 bytes` | same | `..., got 17` |
| fanout not 0..255 | `fanout must be between 0 and 255` | same | `'fanout' must be between 0 and 255` |
| depth not 1..255 | `depth must be between 1 and 255` | same | `'depth' must be between 1 and 255` |
| node_depth not 0..255 | `node_depth must be between 0 and 255` | same | `'node_depth' must be between 0 and 255` |
| inner_size not 0..N | `inner_size must be between 0 and is 64` (sic) | same | `'inner_size' must be between 0 and 64` |
| leaf_size negative (ValueError) | `value must be positive` | `Cannot convert negative int` | same as 3.14 |
| leaf_size >= 2**32 (OverflowError) | `Python int too large to convert to C unsigned long` | `Python int too large for C unsigned long` | same as 3.14 |
| node_offset negative (ValueError) | `value must be positive` | `Cannot convert negative int` | same as 3.14 |
| node_offset >= 2**64 (OverflowError) | `int too big to convert` | `Python int too large for C unsigned long long` | same as 3.14 |
| blake2s node_offset >= 2**48 (OverflowError) | `node_offset is too large` | same | `'node_offset' is too large` |
| leaf_size / node_offset not an int (TypeError) | `an integer is required` | `'str' object cannot be interpreted as an integer` | same as 3.14 |
| digest_size/fanout/depth/... not an int | `'str' object cannot be interpreted as an integer` (all lanes; floats too) | | |

  All of the range errors are `ValueError` unless marked. Note: the `leaf_size` limits above are the Windows `unsigned long` (32 bit); the C field is `uint32` on every platform but the 3.13 converter is `unsigned long`, which is 64 bit on Linux. **Not verified on Linux**: there `leaf_size=2**32` may give a different error (or `OverflowError: leaf_size is too large`). Check in WSL before fixing the text; the conformance case must avoid that one input or be gated by platform.
- Data errors: `str` data: `TypeError: Strings must be encoded before hashing`; other non-buffer (also `None`): `TypeError: object supporting the buffer API required`; `data` and `string` together (positional or keyword): the "mutually exclusive" TypeError of section 2; 3.15 warns on `string=` (section 2).
- Methods: `update()` returns None, `blake2b.update() takes exactly one argument (0 given)`, `blake2b.update() takes no keyword arguments`, `blake2b.digest() takes no arguments (1 given)`. Updating after `digest()` continues the stream. `copy()` keeps all parameters (digest_size, key state).
- Reference vectors (identical on the three lanes; hex):
  - `blake2b(b"")` 786a02f742015903c6c6fd852552d272912f4740e15847618a86e217f71f5419d25e1031afee585313896444934eb04b903a685b1448b755d56f701afe9be2ce
  - `blake2s(b"")` 69217a3079908094e11121d042354a7c1f55b6482ca1a51e1b250dfd1ed0eef9
  - `blake2b(b"abc")` ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d17d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923
  - `blake2s(b"abc")` 508c5e8c327c14e2e1a72ba34eeb452f37458b209ed63a294d999b4c86675982
  - `blake2b(b"abc", key=b"k", salt=b"s", person=b"p", digest_size=20)` 0c694d1f89100a1db6ed58b7322cc3f244ac769a; same call with `blake2s`: 5627c6bd9d46169384f5a5d096204f4c4b2676a3
  - `blake2b(b"abc", fanout=2, depth=2, leaf_size=4096, node_offset=1, node_depth=1, inner_size=64, last_node=True)` 03ee763a4ae424d5eca7e8f5765a96289833887bd5d7a0a14f6d6b6d0548a09a366e3b346c400c5b1b9f15122d9ca8abb448275398f0890958d09ebd0ed30182; `blake2s(..., inner_size=32, ...)` 68722e7723cf4d89c1c4758b960dca6bf6ca18ea317c021cb2ecb18e255cf290
  - `blake2b(b"a"*1000000)` 98fb3efb7206fd19ebf69b6f312cf7b64e3b94dbe1a17107913975a793f177e1d077609d7fba363cbba00d05f7aa4e4fa8715d6428104c0a75643b0ff3fd3eaf
  - Block-boundary prefixes (first 16 hex digits): `blake2b(b"x"*128)` 082b91ea2e15d155, `(b"x"*129)` 362a53bbe2ec0809, `blake2b(key=b"k"*64)` 5caefa9818315414, `blake2b(b"x"*129, key=b"k"*64)` 9c721c300ff7165e, 256 x's fed as 100+28+128: 26066ae992ec734e, `blake2s(b"x"*64)` c13eb20b85b1d6a7, `blake2s(b"x"*65)` 77f538c970521d7f.

### 3.2 `_sha3`
- Module names: `sha3_224`, `sha3_256`, `sha3_384`, `sha3_512`, `shake_128`, `shake_256` (types), `implementation = 'HACL'`; 3.14+ `_GIL_MINSIZE 2048`. No `keccakopt`.
- Types: `__module__ '_sha3'`, name = the algorithm name, not subclassable, immutable, not picklable. `__doc__`: `"sha3_256([data], *, usedforsecurity=True) -> SHA3 object\n\nReturn a new SHA3 hash object with a hashbit length of 32 bytes."` (28/32/48/64) and `"shake_128([data], *, usedforsecurity=True) -> SHAKE object\n\nReturn a new SHAKE hash object."`.
- Instance attributes:

| type | name | digest_size | block_size | `_capacity_bits` | `_rate_bits` | `_suffix` |
| --- | --- | --- | --- | --- | --- | --- |
| sha3_224 | sha3_224 | 28 | 144 | 448 | 1152 | `b'\x06'` |
| sha3_256 | sha3_256 | 32 | 136 | 512 | 1088 | `b'\x06'` |
| sha3_384 | sha3_384 | 48 | 104 | 768 | 832 | `b'\x06'` |
| sha3_512 | sha3_512 | 64 | 72 | 1024 | 576 | `b'\x06'` |
| shake_128 | shake_128 | 0 | 168 | 256 | 1344 | `b'\x1f'` |
| shake_256 | shake_256 | 0 | 136 | 512 | 1088 | `b'\x1f'` |

- Constructor: `(data=b'', /?, *, usedforsecurity=True, string=None)`: `data` positional or keyword, `usedforsecurity` keyword-only. A CPython quirk to reproduce: argument errors of **all six** constructors name `sha3_224`: `TypeError: sha3_224() takes at most 1 positional argument (2 given)`, `sha3_224() got an unexpected keyword argument 'zz'` (all lanes). Data errors and the `data`/`string` rules are as for blake2.
- Methods: `update`, `copy`, `digest()`, `hexdigest()`; method argument errors use the real type name (`sha3_256.digest() takes no arguments (1 given)`, `sha3_256.update() takes exactly one argument (0 given)`).
- SHAKE: `digest(length)` / `hexdigest(length)`, `length` positional or keyword; missing: `TypeError: digest() missing required argument 'length' (pos 1)`. `digest(0)` is `b''`. Repeated calls restart the output (`digest(5)` is a prefix of `digest(8)`); update after digest continues absorbing.

| `length` | 3.13 | 3.14 | 3.15 |
| --- | --- | --- | --- |
| -1 | `ValueError: value must be positive` | `ValueError: Cannot convert negative int` | `ValueError: length cannot be negative` |
| 2**29 .. 2**32-1 | `ValueError: length is too large` | same | `OverflowError: digest length is too large` |
| 2**63, 2**64 | `OverflowError: Python int too large to convert to C unsigned long` | `OverflowError: Python int too large for C unsigned long` | `OverflowError: Python int too large to convert to C ssize_t` |
| `'a'`, `1.5` | `TypeError: an integer is required` | `TypeError: 'str' object cannot be interpreted as an integer` ('float') | same as 3.14 |

  Windows-measured: 2**32..2**63-1 hits the 32-bit `unsigned long` overflow on Windows for 3.13/3.14 but "length is too large" on Linux. Keep such values out of the conformance case.
- Vectors: `sha3_224(b"abc")` e642824c3f8cf24ad09234ee7d3c766fc9a3a5168d0c94ad73b46fdf; `sha3_256(b"abc")` 3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532; `sha3_384(b"abc")` ec01498288516fc926459f58e2c6ad8df9b473cb0fc08c2596da7cf0e49be4b298d88cea927ac7f539f1edf228376d25; `sha3_512(b"abc")` b751850b1a57168a5693cd924b6b096e08f621827444f70d884f5d0240d2712e10e116e9192af3c91a7ec57647e3934057340b4cf408d5a56592f8274eec53f0; `shake_128(b"abc").hexdigest(40)` 5881092dd818bf5cf8a3ddb793fbcba74097d5c526a6d35f97b83351940f2cc844c50af32acd3f2c; `shake_256(b"abc").hexdigest(40)` 483366601360a8771c6863080cc4114d8db44530f8f1e1ee4f94ea37e78b5739d5a15bef186a5386; `sha3_256(b"")` starts a7ffc6f8; `shake_128().digest(5)` is 7f9c2ba4e8; rate-boundary prefixes `sha3_256(b"x"*135)` c150125edc74b56f, `(b"x"*136)` 5bc276bac9c58250.

### 3.3 `hashlib` on top
With no `_hashlib`, `hashlib.py` takes every constructor from `__get_builtin_constructor` (cpython `Lib/hashlib.py:82-129`), i.e. `hashlib.sha3_256 is _sha3.sha3_256`. (On the reference CPython with OpenSSL, `hashlib.sha3_256` is `_hashlib.openssl_sha3_256` and `hashlib.new("sha3_256")` returns a `_hashlib.HASH`. Cases must therefore never print the type or module of a `hashlib.sha3_*`/`shake_*` object or its repr; `hashlib.blake2b` is always `_blake2.blake2b` because OpenSSL's blake2 is blocked, `hashlib.py:78`.)

## 4. Design
Decision: **pure Java, no FFM, no Rust**. Both algorithms are small, public-domain specifications (RFC 7693; FIPS 202), need no OS services, and the project rule is pure Java where Java suffices.

New files in `runtime/pyjvm315/runtime/`:
- `Blake2.java` (~230 lines): the two compression functions and a small state object. `final class Blake2 { final boolean b; long[] h (b) / int[] h (s); long t0, t1; byte[] buf; int buflen; boolean lastNode; int outLen; ... }`. Parameter block built exactly as RFC 7693 section 2.5/2.8 (little-endian): digest_length, key_length, fanout, depth, leaf_length (4), node_offset (8 for b; 6 for s), node_depth, inner_length, [14 reserved for b], salt (16/8), personal (16/8); `h = IV xor param words`. A key is padded to one block and absorbed as the first block. Buffering rule: compress only when more input follows (the last block must be compressed with the final flag; `last_node` sets `f1`). 12 rounds / 64-bit words for b, 10 rounds / 32-bit for s. `copy()` clones the arrays. `digest()` works on a copy so the object stays updatable.
- `Keccak.java` (~120 lines): Keccak-f[1600] on `long[25]`, a byte-wise sponge with `rate` and `suffix` (0x06 or 0x1f), `absorb(byte[])`, `squeeze(n)` on a copy of the state (pad `suffix` at the write position, `0x80` at `rate-1`, permute, read `rate` bytes per permutation).
- `Hash3Module.java` (~330 lines; name to taste): `createBlake2()` and `createSha3()`. Classes through `NativeTypes.define(module, name, null)` like `HashModule.create`; state in the instance slot (`NativeTypes.newInstance/state`). Module-level names are the **classes themselves** (CPython exposes types, and `_sha3.sha3_256()` must return an instance whose `type(...) is _sha3.sha3_256`): give each class a `__new__` the way `SelectModule.pollNew` does (overlapped_plan.md section 3 points at the same pattern) instead of `HashModule`'s module-level function. Class constants via `PyRuntime.classAddAttr`.

Why one Keccak for all six rather than `MessageDigest("SHA3-256")` for the fixed four: one code path gives `copy()`, the three private attributes and SHAKE with the same state object, and avoids depending on a provider's `clone()` support. The JDK digest is used as an oracle instead (section 6). Speed is not a goal here (pure-Java Keccak is on the order of 100+ MB/s); if it ever matters, the four fixed digests can switch to `MessageDigest` behind the same class without a visible change.

Shared argument handling (new small helper, e.g. `HashArgs` or static methods in `HashModule`, used by all five hash modules):
- `dataArg(fname, args, kw)`: at most one positional (`"<fname>() takes at most 1 positional argument (N given)"`), `data`/`string` keywords, the "mutually exclusive" TypeError, the 3.15 `DeprecationWarning` for `string=` (through the runtime's `warnings.warn` call path, as `BuiltinsExtra.java:~245` does for the breakpoint warning), `usedforsecurity` accepted and ignored, unknown keyword error. For `_sha3` pass `fname = "sha3_224"` for all six (the quirk).
- Integer conversion helpers with the per-lane messages of section 3 (`LangVersion.atLeast(3, 14)` / `(3, 15)`, as `ArrayMethods.java:248` and `ArrayModule.java:111-125` do). The runtime surely has an `__index__` conversion helper already (`IoUtil.asLong` or similar); find the one that raises `'str' object cannot be interpreted as an integer` and reuse it.
- `_GIL_MINSIZE = 2048L` added to all five modules when `LangVersion.atLeast(3, 14)`.
- Not subclassable / immutable type / not picklable: check what `NativeTypes.define` classes do today for `_md5.md5` (unverified); match CPython where the class machinery already has a "final"/"immutable" flag, otherwise leave it and keep those three probes out of the case (list them under "known differences" in the class javadoc). Do not invent a mechanism for this module alone.

Registration: add `"_blake2", "_sha3"` to `ImportSupport.NATIVE_NAMES` (`ImportSupport.java:37`), two `case` lines in `createNative` (`:83`), and to `NATIVE_MODULES` in `pyjvm315/compiler.py:57`. Check `SysExtras.java:226-238` (the `sys.stdlib_module_names`/builtin-name lists) for whether `_blake2`/`_sha3` need adding to `sys.builtin_module_names`.

## 5. Ordered steps
1. `Keccak.java` + a scratch Java main (not committed) that checks SHA3-224/256/384/512 against `MessageDigest` for lengths 0..500 and random splits, and SHAKE against the vectors above.
2. `Blake2.java` + scratch main against the vectors in 3.1 (unkeyed, keyed, salted, tree parameters, boundaries 0/1/127/128/129 for b and 63/64/65 for s, 1 MB).
3. Shared argument helper; move `HashModule.construct` onto it (fixes the message, adds the 3.15 warning and `_GIL_MINSIZE`). Rerun `hash_builtin_modules` on the three lanes.
4. `_sha3` module and registration. Case `hash_sha3`.
5. `_blake2` module. Case `hash_blake2`.
6. Case `hashlib_guaranteed`; confirm `import hashlib` prints nothing on stderr on 3.13/3.14.
7. Docs: remove item 2a from HANDOFF, regenerate the `hashlib` row of STDLIB_STATUS (still smoke-mismatch because of `pbkdf2_hmac`/`scrypt`), one line in the HashModule javadoc.

## 6. Verification
New conformance cases (`tests/conformance/`; each prints only values that are equal on CPython with and without OpenSSL):
- `hash_sha3.py` (imports `_sha3` directly, like `hash_builtin_modules.py`): the attribute table of 3.2 for all six; vectors; update/copy/digest-twice; incremental feeding across the rate boundary (`b"x"*135`, `136`, `137`, fed in pieces of 1, 7, 200); SHAKE lengths 0, 1, rate-1, rate, rate+1, 1000 with `hexdigest(n) == digest(n).hex()` and the prefix property; errors: str/int/None data, `data`+`string`, two positionals, unknown keyword (the `sha3_224` quirk), `digest(1)` on sha3, missing `length`, `digest(-1)`, `digest("a")`; `module.implementation`; `hasattr(_sha3, "_GIL_MINSIZE")`. The lane-dependent messages are printed as they are: each lane's reference is its own CPython.
- `hash_blake2.py`: constants (module and class), defaults, every vector of 3.1, every row of the validation table except the two platform-dependent `leaf_size` overflow inputs, bytes-like `key` (memoryview, bytearray), `last_node` truthiness, `copy()` keeps parameters, `digest_size` 1 and max, `fanout=0, depth=255`, `node_offset` maxima (`2**64-1`, `2**48-1`).
- `hashlib_guaranteed.py`: `sorted(hashlib.algorithms_guaranteed)`; for each name `hashlib.new(name, b"abc")` hexdigest (shake with a length) equals the direct constructor's; `hashlib.blake2b(b"x", digest_size=16, key=b"k").hexdigest()`; `hmac.new(b"k", b"m", "blake2b"/"sha3_256").hexdigest()`; `hashlib.file_digest(io.BytesIO(b"abc"), "blake2s").hexdigest()`. Do not print `algorithms_available`, types or reprs (OpenSSL differences, section 3.3).
- A 3.15-only case or section (`# requires python >= 3.15`, the directive `pyjvm315/conformance.py:76-81` reads; copy the exact spelling from an existing gated case such as `binascii_base64_315.py`) for the `string=` DeprecationWarning via `warnings.catch_warnings(record=True)`.
- Run: the three new cases plus `hash_builtin_modules` and `native_stdlib_modules` with `pyjvm-work\run_cases.ps1 -CaseList hash_sha3,hash_blake2,hashlib_guaranteed,hash_builtin_modules -Lanes 313,314,315`, one lane at a time (memory). Then methodsize (`-m pyjvm315.methodsize --runtime`): the Keccak round and the BLAKE2 `G` sequence must stay under 8000 bytes per method; write the rounds as loops over tables, not unrolled.
- CPython's own tests once `tools/run_cpython_test.py` can host them: `test_hashlib` (large; expect `_hashlib`-dependent skips) and `Lib/test/test_hmac`.
- Linux (WSL) when the gates are unpaused: same three cases; this is where the `unsigned long` messages of 3.1/3.2 must be re-measured.

## 7. Risks and open questions
1. Per-lane message tables are large; a wrong lane gate shows up only on that lane. The wave-3 experience (HANDOFF item 1: many gates that treated 3.14 like 3.15) applies. Everything in section 3 was measured on the three local interpreters, patch levels 3.13.17 / 3.14.10 / 3.15.2; the lane venvs use uv's 3.13.18 etc. Re-run the probe under `.venv313/.venv314/.venv315` before coding against a message.
2. Windows-only measurement: the two `unsigned long`-sized checks (blake2 `leaf_size`, shake `length` in 3.13/3.14) differ on Linux (64-bit long). Unverified on Linux.
3. Whether `NativeTypes.define` classes can be made non-subclassable/immutable/unpicklable is not verified; see section 4.
4. BLAKE2 correctness for the tree parameters rests on one vector per algorithm from this plan plus what the case computes on the reference at run time; that is sufficient because the case is differential, but write the scratch check of step 2 with more parameter combinations (generate expected values with host CPython).
5. `usedforsecurity` is accepted and ignored (CPython's builtin modules do the same).
6. Size: about 700 lines of Java, 3 cases, 1-2 sessions. No native code, no platform split.
7. Re-creating the probes: `for n in dir(_blake2)/dir(_sha3)`; the `t(label, fn)` try/print helper over the inputs listed in the tables; run with `C:\Python31x\python.exe -I`.
