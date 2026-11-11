# SPDX-License-Identifier: GPL-3.0-or-later
import _opcode
import opcode


def t(f, *a, **k):
    try:
        print(repr(f(*a, **k)))
    except BaseException as e:
        print(type(e).__name__, e)


names = {v: k for k, v in opcode.opmap.items()}
print(len(opcode.opmap), opcode.HAVE_ARGUMENT, opcode.EXTENDED_ARG, opcode.MIN_INSTRUMENTED_OPCODE)
print(opcode.__all__)
for lst in (opcode.hasarg, opcode.hasconst, opcode.hasname, opcode.hasjump, opcode.hasfree, opcode.haslocal, opcode.hasexc):
    print(len(lst), sorted(names[o] for o in lst if o in names)[:40])
print(opcode.cmp_op)
print(len(opcode._specialized_opmap), sorted(opcode._specializations)[:12])
print(_opcode.get_intrinsic1_descs())
print(_opcode.get_intrinsic2_descs())
print(_opcode.get_nb_ops())
print(hasattr(_opcode, 'get_special_method_names'))
if hasattr(_opcode, 'get_special_method_names'):
    print(_opcode.get_special_method_names())

# every stack effect for every opcode, with and without jump
for op in sorted(set(opcode.opmap.values())):
    row = []
    for oparg in (None, 0, 1, 2, 3, 5, 255, 256, 1000):
        for jump in (None, True, False):
            try:
                row.append(opcode.stack_effect(op, oparg, jump=jump))
            except ValueError:
                row.append('E')
    print(names[op], op, row)

# validity and flags across the whole opcode space
print([o for o in range(0, 600) if _opcode.is_valid(o)] == [o for o in range(0, 600) if _opcode.is_valid(o)])
print(sum(1 for o in range(-5, 600) if _opcode.is_valid(o)))
for fl in ('has_arg', 'has_const', 'has_name', 'has_jump', 'has_free', 'has_local', 'has_exc'):
    print(fl, [o for o in range(-3, 600) if getattr(_opcode, fl)(o)])

# argument handling
t(_opcode.stack_effect)
t(_opcode.stack_effect, 9999)
t(_opcode.stack_effect, -5)
t(_opcode.stack_effect, 1 << 40)
t(_opcode.stack_effect, 1.5)
t(_opcode.stack_effect, 'a')
t(_opcode.stack_effect, 1, 2, 3)
t(_opcode.stack_effect, 1, jump=7)
t(_opcode.stack_effect, 1, jump='x')
t(_opcode.stack_effect, 1, bogus=1)
t(_opcode.stack_effect, opcode.opmap['LOAD_CONST'], 1 << 70)
t(_opcode.is_valid, None)
t(_opcode.is_valid, 1 << 40)
t(_opcode.is_valid)
t(_opcode.is_valid, 1, 2)
t(_opcode.has_arg, True)
t(_opcode.has_arg, 'x')
t(_opcode.get_executor, None, 0)
print(_opcode.get_specialization_stats())
print(_opcode.__name__)

# the pure-Python side
import dis
print(dis.opname[opcode.opmap['LOAD_CONST']], dis.opmap is opcode.opmap, dis.stack_effect is opcode.stack_effect)
print(dis.HAVE_ARGUMENT, sorted(dis.COMPILER_FLAG_NAMES.items())[:3])
print(dis.hasjump == opcode.hasjump)
print(isinstance(dis.Instruction, type))
