"""Verifier dataflow for the instruction subset emitted by this backend.

Frames describe JVM verification types, not inferred Python value types. New
instructions must gain an explicit transfer rule before modern emission admits
them. No host JVM, class loading, or Java source rewriting is involved.
"""
from __future__ import annotations

from collections import deque
from dataclasses import dataclass
import struct

# verification_type_info tags (JVMS 4.7.4)
TOP, INT, FLOAT, DOUBLE, LONG, NULL, UNINIT_THIS = [(n, None) for n in range(7)]
OBJECT = (7, "java/lang/Object")
THROWABLE = (7, "java/lang/Throwable")


class FrameError(ValueError):
    pass


def descriptor_types(desc):
    """Parse field/method descriptors into verification types, including arrays."""
    def one(i):
        c = desc[i]
        if c == 'L':
            end = desc.index(';', i)
            return (7, desc[i + 1:end]), end + 1
        if c == '[':
            end = i
            while desc[end] == '[':
                end += 1
            _, end = one(end)
            return (7, desc[i:end]), end
        types = {'B': INT, 'C': INT, 'S': INT, 'Z': INT, 'I': INT,
                 'F': FLOAT, 'D': DOUBLE, 'J': LONG, 'V': None}
        if c not in types:
            raise FrameError(f"Invalid descriptor {desc!r}")
        return types[c], i + 1

    try:
        if not desc.startswith('('):
            result, end = one(0)
            if end != len(desc) or result is None:
                raise FrameError(f"Invalid field descriptor {desc!r}")
            return [], result
        args, pos = [], 1
        while desc[pos] != ')':
            value, pos = one(pos)
            if value is None:
                raise FrameError("Void argument")
            args.append(value)
        result, end = one(pos + 1)
        if end != len(desc):
            raise FrameError(f"Invalid method descriptor {desc!r}")
        return args, result
    except (IndexError, ValueError) as exc:
        raise FrameError(f"Invalid descriptor {desc!r}") from exc


def width(value):
    return 2 if value in (LONG, DOUBLE) else 1


@dataclass(frozen=True)
class Instruction:
    pc: int
    opcode: int
    operand: int | None
    end: int


def decode(code):
    instructions = {}
    pc = 0
    one = {0x00, 0x01, 0x57, 0x59, 0xB0, 0xB1, 0xBF, *range(0x2A, 0x2E), *range(0x4B, 0x4F)}
    two = {0x12, 0x19, 0x3A}
    three = {0x13, 0x99, 0x9A, 0xA7, 0xB2, 0xB3, 0xB6, 0xB7, 0xB8, 0xBB, 0xC0}
    while pc < len(code):
        opcode = code[pc]
        operand = None
        size = 1
        if opcode == 0xC4:
            if pc + 4 > len(code) or code[pc + 1] not in (0x19, 0x3A):
                raise FrameError(f"Unsupported/truncated wide instruction at {pc}")
            opcode = code[pc + 1]
            size = 4
            operand = int.from_bytes(code[pc + 2:pc + 4], 'big')
        elif opcode in two:
            size = 2
        elif opcode in three:
            size = 3
        elif opcode not in one:
            raise FrameError(f"Unsupported JVM opcode 0x{opcode:02x} at {pc}")
        if pc + size > len(code):
            raise FrameError(f"Truncated instruction at {pc}")
        if operand is None and size > 1:
            operand = int.from_bytes(code[pc + 1:pc + size], 'big', signed=opcode in (0x99, 0x9A, 0xA7))
        instructions[pc] = Instruction(pc, opcode, operand, pc + size)
        pc += size
    return instructions


def merge_type(a, b, *, local):
    if a == b:
        return a
    if a == NULL and b[0] == 7:
        return b
    if b == NULL and a[0] == 7:
        return a
    if a[0] == b[0] == 7:
        # Emitted operations accept Object; typed uses require an explicit cast.
        # Do not probe/load classes to guess hierarchy relationships.
        return OBJECT
    if local:
        return TOP
    raise FrameError(f"Incompatible operand stack types: {a}, {b}")


def analyze(cp, owner, method):
    code = method.code
    insns = decode(code)
    if not code or len(code) > 65535:
        raise FrameError("JVM method code must contain 1..65535 bytes")
    if not 0 <= method.max_locals <= 65535:
        raise FrameError("max_locals outside unsigned 16-bit range")
    args, result_type = descriptor_types(method.desc)
    initial = [] if method.access & 0x0008 else [UNINIT_THIS if method.name == '<init>' else (7, owner)]
    for arg in args:
        initial.append(arg)
        if width(arg) == 2:
            initial.append(TOP)
    if len(initial) > method.max_locals:
        raise FrameError("Method parameters exceed max_locals")
    initial += [TOP] * (method.max_locals - len(initial))
    exceptions = method.exception_table or []
    required = set()
    for ins in insns.values():
        if ins.opcode in (0x99, 0x9A, 0xA7):
            target = ins.pc + ins.operand
            if target not in insns:
                raise FrameError(f"Branch at {ins.pc} targets non-instruction {target}")
            required.add(target)
    for start, end, handler, catch in exceptions:
        if start not in insns or end not in {*insns, len(code)} or not start < end or handler not in insns:
            raise FrameError("Invalid exception-table boundary")
        required.add(handler)
    states = {0: (tuple(initial), ())}
    pending = deque([0])
    max_stack = 0

    def propagate(pc, locals_, stack):
        if pc not in insns:
            raise FrameError(f"Control flow falls outside method at {pc}")
        state = (tuple(locals_), tuple(stack))
        old = states.get(pc)
        if old is not None:
            if len(old[1]) != len(stack):
                raise FrameError(f"Inconsistent stack height at {pc}")
            state = (tuple(merge_type(a, b, local=True) for a, b in zip(old[0], locals_)),
                     tuple(merge_type(a, b, local=False) for a, b in zip(old[1], stack)))
        if state != old:
            states[pc] = state
            pending.append(pc)

    while pending:
        pc = pending.popleft()
        before_locals, before_stack = states[pc]
        locals_, stack = list(before_locals), list(before_stack)
        ins = insns[pc]
        op, operand = ins.opcode, ins.operand
        max_stack = max(max_stack, sum(map(width, stack)))

        def pop():
            if not stack:
                raise FrameError(f"Operand stack underflow at {pc}")
            return stack.pop()

        def ref(value, *, uninitialized=False):
            allowed = (6, 7, 8) if uninitialized else (7,)
            if value != NULL and value[0] not in allowed:
                raise FrameError(f"Expected reference at {pc}, got {value}")

        def slot(index):
            if not 0 <= index < len(locals_):
                raise FrameError(f"Local slot {index} exceeds max_locals at {pc}")
            return index

        if op == 0x01:
            stack.append(NULL)
        elif op in (0x19, *range(0x2A, 0x2E)):
            index = slot(operand if op == 0x19 else op - 0x2A)
            ref(locals_[index], uninitialized=True); stack.append(locals_[index])
        elif op in (0x3A, *range(0x4B, 0x4F)):
            index = slot(operand if op == 0x3A else op - 0x4B)
            value = pop(); ref(value, uninitialized=True); locals_[index] = value
        elif op in (0x12, 0x13):
            entry = cp.info[operand]
            if entry[0] == 'string': stack.append((7, 'java/lang/String'))
            elif entry[0] == 'int': stack.append(INT)
            else: raise FrameError(f"Unsupported ldc constant at {pc}")
        elif op == 0x57:
            if width(pop()) != 1: raise FrameError(f"pop of category-2 value at {pc}")
        elif op == 0x59:
            value = pop()
            if width(value) != 1: raise FrameError(f"dup of category-2 value at {pc}")
            stack.extend([value, value])
        elif op in (0xB2, 0xB3):
            _, _, _, desc = cp.info[operand]
            _, value = descriptor_types(desc)
            if op == 0xB2: stack.append(value)
            else: pop()
        elif op in (0xB6, 0xB7, 0xB8):
            _, call_owner, name, desc = cp.info[operand]
            arguments, returns = descriptor_types(desc)
            for argument in reversed(arguments):
                value = pop()
                if argument[0] == 7: ref(value)
                elif value != argument: raise FrameError(f"Call argument type mismatch at {pc}")
            receiver = pop() if op != 0xB8 else None
            if receiver is not None: ref(receiver, uninitialized=name == "<init>")
            if name == '<init>':
                if op != 0xB7 or receiver is None or receiver[0] not in (6, 8):
                    raise FrameError(f"Invalid constructor invocation at {pc}")
                initialized = (7, owner if receiver == UNINIT_THIS else cp.info[insns[receiver[1]].operand][1])
                locals_ = [initialized if v == receiver else v for v in locals_]
                stack = [initialized if v == receiver else v for v in stack]
            if returns is not None: stack.append(returns)
        elif op == 0xBB:
            stack.append((8, pc))
        elif op == 0xC0:
            ref(pop()); stack.append((7, cp.info[operand][1]))
        elif op in (0x99, 0x9A):
            if pop() != INT: raise FrameError(f"Conditional requires int at {pc}")
        elif op == 0xB0:
            ref(pop())
            if result_type is None or result_type[0] != 7: raise FrameError("Invalid areturn descriptor")
        elif op == 0xB1:
            if result_type is not None: raise FrameError("Invalid void return descriptor")
            if method.name == '<init>' and UNINIT_THIS in locals_: raise FrameError("Uninitialized constructor return")
        elif op == 0xBF:
            ref(pop())
        # nop and goto have no stack effect.
        max_stack = max(max_stack, sum(map(width, stack)))
        for start, end, handler, catch in exceptions:
            if start <= pc < end:
                propagate(handler, before_locals, [(7, catch or 'java/lang/Throwable')])
        if op in (0x99, 0x9A, 0xA7):
            propagate(pc + operand, locals_, stack)
        if op not in (0xA7, 0xB0, 0xB1, 0xBF):
            propagate(ins.end, locals_, stack)

    # Preserve offsets: dead blocks become nop ... athrow with a synthetic
    # Throwable frame. Remove their protected ranges so handler locals are not
    # polluted by synthetic dead-code frames (JVMS verifier checks dead blocks).
    patched = bytearray(code)
    live_ranges, dead_start = [], None
    live_start = None
    for pc, ins in insns.items():
        if pc in states:
            if dead_start is not None:
                patched[dead_start:pc] = bytes(pc - dead_start - 1) + b'\xbf'
                states[dead_start] = (tuple([TOP] * method.max_locals), (THROWABLE,))
                required.add(dead_start); max_stack = max(max_stack, 1); dead_start = None
            if live_start is None: live_start = pc
        else:
            if live_start is not None:
                live_ranges.append((live_start, pc)); live_start = None
            if dead_start is None: dead_start = pc
    if live_start is not None: live_ranges.append((live_start, len(code)))
    if dead_start is not None:
        patched[dead_start:] = bytes(len(code) - dead_start - 1) + b'\xbf'
        states[dead_start] = (tuple([TOP] * method.max_locals), (THROWABLE,))
        required.add(dead_start); max_stack = max(max_stack, 1)
    pruned = []
    for start, end, handler, catch in exceptions:
        for low, high in live_ranges:
            a, b = max(start, low), min(end, high)
            if a < b: pruned.append((a, b, handler, catch))
    if max_stack > 65535:
        raise FrameError("max_stack outside unsigned 16-bit range")
    frames = [(pc, states[pc]) for pc in sorted(required) if pc != 0 and pc in states]
    return bytes(patched), pruned, frames, max_stack


def encode_frames(cp, frames):
    """Use full_frame initially; compact encoding can be added without reanalysis."""
    def u2(n): return struct.pack('>H', n)
    def verification(value):
        tag, data = value
        return bytes([tag]) + (u2(cp.class_(data)) if tag == 7 else u2(data) if tag == 8 else b'')
    chunks, previous = [], -1
    for pc, (locals_, stack) in frames:
        values = list(locals_)
        while values and values[-1] == TOP: values.pop()
        # category-2 local values encode once, although they occupy two slots.
        encoded, index = [], 0
        while index < len(values):
            encoded.append(values[index]); index += width(values[index])
        delta = pc - previous - 1
        chunks.append(b'\xff' + u2(delta) + u2(len(encoded)) + b''.join(map(verification, encoded))
                      + u2(len(stack)) + b''.join(map(verification, stack)))
        previous = pc
    return u2(len(chunks)) + b''.join(chunks)
