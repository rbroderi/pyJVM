# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
from __future__ import annotations


from .stackmap import FrameError, analyze, encode_frames

JAVA_TARGETS = (21,)
DEFAULT_TARGET = 21


def be(value, size):
    """Big-endian two's-complement bytes of ``value`` in ``size`` bytes (no struct module needed)."""
    value &= (1 << (8 * size)) - 1
    out = bytearray()
    shift = 8 * (size - 1)
    while shift >= 0:
        out.append((value >> shift) & 0xFF)
        shift -= 8
    return bytes(out)


def u1(v: int) -> bytes:
    return be(v, 1)


def u2(v: int) -> bytes:
    return be(v, 2)


def u4(v: int) -> bytes:
    return be(v, 4)


class Label:
    def __init__(self, ident):
        self.ident = ident

    def __eq__(self, other):
        return isinstance(other, Label) and (self.ident,) == (other.ident,)

    def __hash__(self):
        return hash((self.ident,))


class ConstantPool:
    def __init__(self) -> None:
        self.entries: list[bytes] = []
        self.cache: dict[tuple, int] = {}
        self.info: dict[int, tuple] = {}

    def _add(self, key: tuple, payload: bytes) -> int:
        if key in self.cache:
            return self.cache[key]
        self.entries.append(payload)
        idx = len(self.entries)
        self.cache[key] = idx
        self.info[idx] = key
        return idx

    def utf8(self, value: str) -> int:
        # CONSTANT_Utf8 uses JVM modified UTF-8: NUL is two bytes and
        # supplementary characters are encoded as UTF-16 surrogate pairs.
        units = value.encode("utf-16-be", errors="surrogatepass")
        raw = bytearray()
        for index in range(0, len(units), 2):
            unit = int.from_bytes(units[index:index + 2], "big")
            if 0 < unit < 0x80:
                raw.append(unit)
            elif unit < 0x800:
                raw.extend((0xC0 | (unit >> 6), 0x80 | (unit & 0x3F)))
            else:
                raw.extend((0xE0 | (unit >> 12), 0x80 | ((unit >> 6) & 0x3F), 0x80 | (unit & 0x3F)))
        if len(raw) > 65535:
            raise ValueError("JVM UTF8 constant exceeds 65535 bytes")
        return self._add(("utf8", value), u1(1) + u2(len(raw)) + raw)

    def class_(self, name: str) -> int:
        return self._add(("class", name), u1(7) + u2(self.utf8(name)))

    def string(self, value: str) -> int:
        return self._add(("string", value), u1(8) + u2(self.utf8(value)))

    def integer(self, value: int) -> int:
        return self._add(("int", value), u1(3) + be(value, 4))

    def long(self, value: int) -> int:
        # JVM long constants consume two constant-pool slots. We avoid them in
        # generated code by loading decimal strings and using Long.valueOf.
        raise NotImplementedError("Use boxed long construction instead")

    def name_and_type(self, name: str, desc: str) -> int:
        return self._add(
            ("nat", name, desc),
            u1(12) + u2(self.utf8(name)) + u2(self.utf8(desc)),
        )

    def methodref(self, owner: str, name: str, desc: str) -> int:
        return self._add(
            ("method", owner, name, desc),
            u1(10) + u2(self.class_(owner)) + u2(self.name_and_type(name, desc)),
        )

    def fieldref(self, owner: str, name: str, desc: str) -> int:
        return self._add(
            ("field", owner, name, desc),
            u1(9) + u2(self.class_(owner)) + u2(self.name_and_type(name, desc)),
        )

    def render(self) -> bytes:
        return u2(len(self.entries) + 1) + b"".join(self.entries)


class CodeBuilder:
    def __init__(self, cp: ConstantPool) -> None:
        self.cp = cp
        self.code = bytearray()
        self._next_label = 0
        self._labels: dict[int, int] = {}
        self._fixups: list[tuple[int, int, int]] = []
        self._exception_specs: list[tuple[int, int, int, str | None]] = []
        self._resolved_exceptions: list[tuple[int, int, int, str | None]] = []

    @property
    def pos(self) -> int:
        return len(self.code)

    def label(self) -> Label:
        lab = Label(self._next_label)
        self._next_label += 1
        return lab

    def mark(self, label: Label) -> None:
        self._labels[label.ident] = self.pos

    def emit(self, *values: int) -> None:
        self.code.extend(v & 0xFF for v in values)

    def emit_u2(self, opcode: int, operand: int) -> None:
        self.emit(opcode)
        self.code.extend(u2(operand))

    def branch(self, opcode: int, label: Label) -> None:
        op_pos = self.pos
        self.emit(opcode, 0, 0)
        self._fixups.append((op_pos, op_pos + 1, label.ident))

    def add_exception_handler(self, start: Label, end: Label, handler: Label, catch_class: str | None) -> None:
        self._exception_specs.append((start.ident, end.ident, handler.ident, catch_class))

    # ifeq/ifne are the only conditional branches the compiler emits; a branch whose target is out of 16-bit
    # range becomes the inverted condition skipping a goto_w (and goto becomes goto_w).
    _INVERT = {0x99: 0x9A, 0x9A: 0x99}

    def finish(self) -> bytes:
        for _op, _patch, ident in self._fixups:
            if ident not in self._labels:
                raise ValueError(f"Unresolved label {ident}")
        long_branches: set[int] = set()          # op positions of branches rewritten to the long form
        growth = {0xA7: 2, 0x99: 5, 0x9A: 5}     # extra bytes of the long form of each opcode
        while True:
            ops = sorted(long_branches)
            def moved(pos: int) -> int:           # new position of an old code position
                return pos + sum(growth[self.code[o]] for o in ops if o < pos)
            changed = False
            for op_pos, _patch, ident in self._fixups:
                if op_pos in long_branches:
                    continue
                if not -32768 <= moved(self._labels[ident]) - moved(op_pos) <= 32767:
                    long_branches.add(op_pos); changed = True
            if not changed:
                break
        if not long_branches:
            out = bytearray(self.code)
            for op_pos, patch_pos, ident in self._fixups:
                out[patch_pos:patch_pos + 2] = be(self._labels[ident] - op_pos, 2)
            result = bytes(out)
            self._finish_exceptions(lambda p: p)
            return result
        ops = sorted(long_branches)
        def moved(pos: int) -> int:
            return pos + sum(growth[self.code[o]] for o in ops if o < pos)
        fixups = {op_pos: ident for op_pos, _patch, ident in self._fixups}
        out = bytearray()
        pos = 0
        while pos < len(self.code):
            if pos in fixups:
                opcode = self.code[pos]
                target = moved(self._labels[fixups[pos]])
                if pos in long_branches:
                    if opcode != 0xA7:   # conditional: jump over the goto_w when the original condition is false
                        out.append(self._INVERT[opcode]); out += be(8, 2)
                    out += be(0xC8, 1) + be(target - len(out), 4)
                else:
                    out.append(opcode); out += be(target - len(out) + 1, 2)
                pos += 3
            else:
                out.append(self.code[pos]); pos += 1
        self._finish_exceptions(moved)
        self._relaxed_labels = {ident: moved(p) for ident, p in self._labels.items()}
        return bytes(out)

    def _finish_exceptions(self, moved) -> None:
        self._resolved_exceptions = []
        for start, end, handler, catch_class in self._exception_specs:
            if start not in self._labels or end not in self._labels or handler not in self._labels:
                raise ValueError("Unresolved exception-table label")
            self._resolved_exceptions.append((moved(self._labels[start]), moved(self._labels[end]), moved(self._labels[handler]), catch_class))

    @property
    def exception_table(self) -> list[tuple[int, int, int, str | None]]:
        return list(self._resolved_exceptions)

    # Constants / locals
    def aconst_null(self): self.emit(0x01)
    def pop(self): self.emit(0x57)
    def dup(self): self.emit(0x59)
    def aload(self, slot: int):
        if 0 <= slot <= 3: self.emit(0x2A + slot)
        else: self._local(0x19, slot)
    def astore(self, slot: int):
        if 0 <= slot <= 3: self.emit(0x4B + slot)
        else: self._local(0x3A, slot)

    def _local(self, opcode: int, slot: int) -> None:
        if not 0 <= slot <= 65535:
            raise ValueError("JVM local slot outside unsigned 16-bit range")
        if slot <= 255:
            self.emit(opcode, slot)
        else:
            self.emit(0xC4, opcode)
            self.code.extend(u2(slot))

    def ldc_class(self, name: str) -> None:
        """Push the java.lang.Class constant for ``name``; for the class being defined this is the class itself (also when hidden)."""
        idx = self.cp.class_(name)
        if idx <= 0xFF:
            self.emit(0x12, idx)
        else:
            self.emit_u2(0x13, idx)

    def ldc_string(self, value: str) -> None:
        idx = self.cp.string(value)
        if idx <= 0xFF:
            self.emit(0x12, idx)
        else:
            self.emit_u2(0x13, idx)

    def getstatic(self, owner: str, name: str, desc: str) -> None:
        self.emit_u2(0xB2, self.cp.fieldref(owner, name, desc))

    def putstatic(self, owner: str, name: str, desc: str) -> None:
        self.emit_u2(0xB3, self.cp.fieldref(owner, name, desc))

    def invokespecial(self, owner: str, name: str, desc: str) -> None:
        self.emit_u2(0xB7, self.cp.methodref(owner, name, desc))

    def invokestatic(self, owner: str, name: str, desc: str) -> None:
        self.emit_u2(0xB8, self.cp.methodref(owner, name, desc))

    def invokevirtual(self, owner: str, name: str, desc: str) -> None:
        self.emit_u2(0xB6, self.cp.methodref(owner, name, desc))

    def new(self, owner: str) -> None:
        self.emit_u2(0xBB, self.cp.class_(owner))

    def checkcast(self, owner: str) -> None:
        self.emit_u2(0xC0, self.cp.class_(owner))

    def areturn(self): self.emit(0xB0)
    def return_(self): self.emit(0xB1)
    def athrow(self): self.emit(0xBF)
    def goto(self, label: Label): self.branch(0xA7, label)
    def ifeq(self, label: Label): self.branch(0x99, label)
    def ifne(self, label: Label): self.branch(0x9A, label)


class Field:
    def __init__(self, name, desc='Ljava/lang/Object;', access=9):
        self.name = name
        self.desc = desc
        self.access = access


class Method:
    def __init__(self, name, desc, code, max_stack=128, max_locals=64, access=9, exception_table=None):
        self.name = name
        self.desc = desc
        self.code = code
        self.max_stack = max_stack
        self.max_locals = max_locals
        self.access = access
        self.exception_table = exception_table


class ClassFile:
    def __init__(self, name: str, super_name: str = "java/lang/Object", *,
                 target: int = DEFAULT_TARGET) -> None:
        if target not in JAVA_TARGETS:
            raise ValueError(f"Unsupported Java target {target}; choose {JAVA_TARGETS}")
        self.target = target
        self.name = name
        self.super_name = super_name
        self.cp = ConstantPool()
        self.methods: list[Method] = []
        self.fields: list[Field] = []

    def add_field(self, field: Field) -> None:
        self.fields.append(field)

    def add_method(self, method: Method) -> None:
        self.methods.append(method)

    def to_bytes(self) -> bytes:
        this_idx = self.cp.class_(self.name)
        super_idx = self.cp.class_(self.super_name)
        code_name = self.cp.utf8("Code")
        field_metadata = [(self.cp.utf8(f.name), self.cp.utf8(f.desc), f) for f in self.fields]
        method_metadata = []
        for m in self.methods:
            method_metadata.append((self.cp.utf8(m.name), self.cp.utf8(m.desc), m))

        header = b"\xCA\xFE\xBA\xBE" + u2(0) + u2(self.target + 44)
        prepared = {}
        stackmap_name = self.cp.utf8("StackMapTable")
        for _, _, m in method_metadata:
            try:
                code, exceptions, frames, max_stack = analyze(self.cp, self.name, m)
            except FrameError as exc:
                raise FrameError(f"{self.name}.{m.name}{m.desc}: {exc}") from exc
            frame_blob = encode_frames(self.cp, frames)
            attributes = u2(1) + u2(stackmap_name) + u4(len(frame_blob)) + frame_blob
            prepared[id(m)] = (code, exceptions, max_stack, attributes)
        for _, _, m in method_metadata:
            for _, _, _, catch_class in prepared[id(m)][1]:
                if catch_class is not None:
                    self.cp.class_(catch_class)
        cp_blob = self.cp.render()
        body = u2(0x0021) + u2(this_idx) + u2(super_idx)  # public + super
        body += u2(0)  # interfaces
        body += u2(len(field_metadata))
        for name_idx, desc_idx, f in field_metadata:
            body += u2(f.access) + u2(name_idx) + u2(desc_idx) + u2(0)
        body += u2(len(method_metadata))

        for name_idx, desc_idx, m in method_metadata:
            code, exc, max_stack, code_attributes = prepared[id(m)]
            exc_blob = u2(len(exc))
            for start_pc, end_pc, handler_pc, catch_class in exc:
                catch_type = 0 if catch_class is None else self.cp.class_(catch_class)
                exc_blob += u2(start_pc) + u2(end_pc) + u2(handler_pc) + u2(catch_type)
            code_attr = (
                u2(max_stack)
                + u2(m.max_locals)
                + u4(len(code))
                + code
                + exc_blob
                + code_attributes
            )
            body += (
                u2(m.access)
                + u2(name_idx)
                + u2(desc_idx)
                + u2(1)
                + u2(code_name)
                + u4(len(code_attr))
                + code_attr
            )

        body += u2(0)  # class attributes
        return header + cp_blob + body
