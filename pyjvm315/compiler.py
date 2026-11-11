# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
from __future__ import annotations

import ast
from .astutil import deepcopy_ast, shallow_copy_ast

from .classfile import DEFAULT_TARGET, ClassFile, CodeBuilder, Field, Method
from .stackmap import FrameError
from .versions import feature_version, deferred_annotations
from .patma import HELPERS as PATMA_HELPERS, PREFIX as PATMA_PREFIX, lower_match
from .annot import lower_annotations, ANNOTATION_MARKERS, CLASSDICT_INTRINSIC
from .carets import build_caret_table
from .excstar import check_except_star_flow, lower_try_star
from .typeparams import lower_type_params, TP_PREFIX, HELPERS as TYPEPARAM_HELPERS


def _preserve_codegen_state(compile_method):
    """Synthetic/nested methods must not consume the caller's control stacks."""
    def wrapped(self, *args, **kwargs):
        names = ("loop_stack", "exception_stack", "handler_depths", "cleanup_stack", "finally_stack",
                 "current_frame_name", "current_frame_firstlineno")
        saved = {name: getattr(self, name) for name in names}
        try:
            return compile_method(self, *args, **kwargs)
        finally:
            for name, value in saved.items():
                setattr(self, name, value)
    return wrapped


OBJ = "Ljava/lang/Object;"
RUNTIME = "pyjvm315/runtime/PyRuntime"
PATMA_RUNTIME = "pyjvm315/runtime/PatternMatch"
RUNTIME2 = "pyjvm315/runtime/BuiltinsExtra"


class CompileError(SyntaxError):
    pass


EXCEPTION_TYPES = {
    "BaseException", "Exception", "ArithmeticError", "LookupError", "ValueError", "TypeError",
    "ZeroDivisionError", "OverflowError", "FloatingPointError", "IndexError", "KeyError",
    "AssertionError", "RuntimeError", "NotImplementedError", "RecursionError", "NameError",
    "UnboundLocalError", "AttributeError", "StopIteration", "StopAsyncIteration", "GeneratorExit",
    "OSError", "ImportError", "ModuleNotFoundError", "MemoryError", "BufferError", "SyntaxError",
    "IndentationError", "TabError", "UnicodeError", "UnicodeEncodeError", "UnicodeDecodeError",
    "UnicodeTranslateError", "Warning", "UserWarning", "DeprecationWarning", "PendingDeprecationWarning",
    "SyntaxWarning", "RuntimeWarning", "FutureWarning", "ImportWarning", "UnicodeWarning",
    "BytesWarning", "ResourceWarning", "KeyboardInterrupt", "SystemExit",
    "SystemError", "EncodingWarning", "EOFError", "ReferenceError", "BaseExceptionGroup", "ExceptionGroup",
    "FileNotFoundError", "PermissionError", "TimeoutError", "ConnectionError", "BlockingIOError",
    "ChildProcessError", "InterruptedError", "IsADirectoryError", "NotADirectoryError", "ProcessLookupError",
    "FileExistsError", "BrokenPipeError", "ConnectionAbortedError", "ConnectionRefusedError", "ConnectionResetError",
}
# Modules implemented in Java (runtime/pyjvm315/runtime): imported by name, not compiled from source.
NATIVE_MODULES = {"ast", "_ast", "sys", "jvm_io", "builtins", "_imp", "_thread", "_warnings", "_weakref", "posix", "_io", "marshal", "itertools", "_collections", "errno", "math", "time", "_contextvars", "_string", "_codecs", "_locale", "gc", "atexit", "_struct", "binascii", "_random", "_md5", "_sha1", "_sha2", "array", "zlib", "_signal", "select", "_opcode"}
BUILTIN_FUNCTIONS = {
    "ord", "chr", "repr", "print", "hash", "id", "len", "iter", "next",
    "min", "max", "sorted", "hex", "oct", "bin", "format", "dir", "vars", "round",
    "reversed", "getattr", "hasattr", "setattr", "delattr", "callable",
    "isinstance", "issubclass", "pow", "abs", "any", "all", "sum",
    "aiter", "anext", "exec", "eval", "compile", "divmod", "ascii", "breakpoint", "input", "__import__", "globals", "locals", "open",
    "__pyjvm_annotate__", "__pyjvm_annotations__", "__pyjvm_class_annotate__", "__pyjvm_cond_add__", "__pyjvm_not_implemented__",
    "__pyjvm_global__", *TYPEPARAM_HELPERS,
}
MODULE_CHUNK_LIMIT = 20000   # bytes of bytecode after which module-level statements continue in a helper method
BUILTIN_TYPES = {
    "object", "int", "bool", "float", "complex", "classmethod", "staticmethod", "property", "str", "bytes", "bytearray", "memoryview",
    "list", "tuple", "dict", "set", "frozenset", "range", "type", "map", "zip", "enumerate", "filter", "slice", "super", "frozendict", "sentinel",
}
NAMESPACES = "pyjvm315/runtime/Namespaces"
IMPORT_SUPPORT = "pyjvm315/runtime/ImportSupport"


class FunctionInfo:
    def __init__(self, name, java_name, posonly, poskw, kwonly, vararg, kwarg, defaults, kw_defaults, function_field, top_level, env_mode, local_names, free_names, nonlocal_names, is_generator=False, is_async=False, is_async_generator=False, qualname=''):
        self.name = name
        self.java_name = java_name
        self.posonly = posonly
        self.poskw = poskw
        self.kwonly = kwonly
        self.vararg = vararg
        self.kwarg = kwarg
        self.defaults = defaults
        self.kw_defaults = kw_defaults
        self.function_field = function_field
        self.top_level = top_level
        self.env_mode = env_mode
        self.local_names = local_names
        self.free_names = free_names
        self.nonlocal_names = nonlocal_names
        self.is_generator = is_generator
        self.is_async = is_async
        self.is_async_generator = is_async_generator
        self.qualname = qualname

    @property
    def positional(self) -> list[str]:
        return self.posonly + self.poskw

    @property
    def bound_args(self) -> list[str]:
        out = self.positional + self.kwonly
        if self.vararg is not None:
            out.append(self.vararg)
        if self.kwarg is not None:
            out.append(self.kwarg)
        return out

    @property
    def descriptor(self) -> str:
        hidden = OBJ if self.env_mode else ""
        return "(" + hidden + (OBJ * len(self.bound_args)) + ")" + OBJ


class ClassInfo:
    def __init__(self, name, bases, class_field, metaclass=None, keywords=None, local=False, qualname=''):
        self.name = name
        self.bases = bases
        self.class_field = class_field
        self.metaclass = metaclass
        self.keywords = keywords
        self.local = local
        self.qualname = qualname


class FinallyContext:
    def __init__(self, finalbody, return_slot, return_entry, branch_entries, manager_slot=None, async_manager=False, loop_depth=0):
        self.finalbody = finalbody
        self.return_slot = return_slot
        self.return_entry = return_entry
        self.branch_entries = branch_entries
        self.manager_slot = manager_slot
        self.async_manager = async_manager
        self.loop_depth = loop_depth


class Scope:
    def __init__(self, initial: list[str] | None = None, start_slot: int = 0, *, module: bool = False,
                 parent: "Scope | None" = None, env_mode: bool = False, env_slot: int | None = None,
                 local_names: set[str] | None = None, free_names: set[str] | None = None,
                 nonlocal_names: set[str] | None = None) -> None:
        self.checked_names: set[str] = set()
        self.namespace_slot: int | None = None
        self.slots: dict[str, int] = {}
        self.next_slot = start_slot
        self.module = module
        self.parent = parent
        self.env_mode = env_mode
        self.env_slot = env_slot
        self.local_names = set(local_names or ())
        self.free_names = set(free_names or ())
        self.global_decl: set[str] = set()
        self.nonlocal_decl: set[str] = set(nonlocal_names or ())
        for name in initial or []:
            self.define(name)

    def define(self, name: str) -> int:
        if name not in self.slots:
            self.slots[name] = self.next_slot
            self.next_slot += 1
        return self.slots[name]

    def temp(self) -> int:
        slot = self.next_slot
        self.next_slot += 1
        return slot

    def has_local(self, name: str) -> bool:
        return name in self.slots or (self.env_mode and name in self.local_names)

    def has(self, name: str) -> bool:
        if self.namespace_slot is not None:
            return self.parent.has(name) if self.parent is not None else False
        if self.env_mode and (name in self.local_names or name in self.free_names or name in self.nonlocal_decl):
            return True
        return name in self.slots or (self.parent is not None and self.parent.has(name))

    def get(self, name: str) -> int:
        if name in self.slots:
            return self.slots[name]
        if self.parent is not None:
            return self.parent.get(name)
        raise CompileError(f"Name {name!r} referenced before assignment")


class Compiler:
    def __init__(self, class_name: str, import_map: dict[str, str] | None = None,
                 import_exports: dict[str, list[str] | None] | None = None, *,
                 module_name: str = "__main__", package_name: str | None = None,
                 target: int = DEFAULT_TARGET, dynamic: bool = False, mode: str = "exec", trace: bool = False,
                 optimize: int = 0, tree: ast.AST | None = None, feature_version_override: tuple[int, int] | None = None) -> None:
        # trace: instrumented mode (line/handler events for sys.settrace, setprofile and sys.monitoring).
        self.trace = trace
        self._line_emitted = None
        # dynamic: exec()/eval() compilation. Module-level names live in caller-supplied
        # namespace mappings (static fields __py_globals/__py_locals) instead of static fields.
        self.dynamic = dynamic
        self.mode = mode
        self.optimize = optimize                 # 0 keeps asserts, docstrings and __debug__; 1 drops asserts; 2 also docstrings
        self.preparsed_tree = tree               # compile(<ast.AST>): skip parsing
        self.feature_version_override = feature_version_override
        self.eval_expr: ast.expr | None = None
        self.declared_global: set[str] = set()
        self.has_implicit_exec = False   # static module with exec(x)/eval(x): unknown names resolve through __py_extras
        self.class_name = class_name.replace(".", "/")
        self.import_map = dict(import_map or {})
        self.import_exports = dict(import_exports or {})
        self.module_name = module_name
        self.package_name = module_name.rpartition(".")[0] if package_name is None else package_name
        self.cf = ClassFile(self.class_name, target=target)
        self.functions: dict[str, FunctionInfo] = {}
        self.function_infos: dict[int, FunctionInfo] = {}
        self.function_nodes: list[ast.FunctionDef | ast.AsyncFunctionDef] = []
        self.classes: dict[str, ClassInfo] = {}
        self.class_infos: dict[int, ClassInfo] = {}
        self.class_nodes: list[ast.ClassDef] = []
        self.current_class: ClassInfo | None = None
        self.global_names: set[str] = set()
        self.global_fields: dict[str, str] = {}
        self._method_counter = 0
        self._async_comp_counter = 0
        self._async_comp_nodes: dict[int, ast.ListComp | ast.SetComp | ast.DictComp] = {}
        self._async_comp_compiled: dict[int, str] = {}
        self.loop_stack: list[tuple[object, object]] = []  # (continue, break)
        self.exception_stack: list[int] = []
        self.handler_depths: list[tuple[int, int]] = []
        self.dynamic_reraise = False
        self.cleanup_stack: list[tuple[str, object]] = []
        self.finally_stack: list[FinallyContext] = []
        self.filename = "<string>"
        self.current_frame_name = "<module>"
        self.current_frame_firstlineno = 1
        self.main_builder = None

    def compile(self, source: str, filename: str = "<string>") -> bytes:
        self.filename = filename
        if self.preparsed_tree is not None:
            tree = deepcopy_ast(self.preparsed_tree)
        else:
            level = feature_version() if self.feature_version_override is None else self.feature_version_override
            tree = ast.parse(source, filename=filename, mode=self.mode, feature_version=level)
        self.caret_table = ""
        if not self.dynamic and not filename.startswith("<"):
            self.caret_table = build_caret_table(tree, source)
        if self.mode == "eval":
            self.eval_expr = tree.body
            tree = ast.Module(body=[ast.Expr(value=tree.body)], type_ignores=[])
        elif self.mode == "single":
            tree = ast.Module(body=tree.body, type_ignores=[])
        if self.optimize >= 2:
            strip_docstrings(tree)
        check_async_scopes(tree)
        check_except_star_flow(tree)
        tree = lower_try_star(tree)
        tree = lower_match(tree)
        tree = lower_type_params(tree, self.dynamic, self.module_name)
        tree = lower_annotations(tree, deferred_annotations(), self.dynamic)
        self.dynamic_reraise = self._bare_raise_outside_handler([tree], False)
        if not self.trace:
            # Code that installs a trace/profile/monitoring hook is compiled in instrumented mode automatically.
            self.trace = any(isinstance(n, ast.Attribute) and n.attr in ("settrace", "setprofile", "monitoring") for n in ast.walk(tree))
        # CPython binds a name declared `global` in any nested scope through the globals mapping, even at module level
        self.declared_global = {n for node in ast.walk(tree) if isinstance(node, ast.Global) for n in node.names}
        if not self.dynamic:
            self.has_implicit_exec = any(
                isinstance(n, ast.Call) and isinstance(n.func, ast.Name) and not n.keywords
                and ((n.func.id in ("exec", "eval") and len(n.args) == 1) or (n.func.id in ("globals", "locals", "vars") and not n.args))
                for n in ast.walk(tree))
            if self.has_implicit_exec:
                self.cf.add_field(Field("__py_extras"))
        self.deleted_names = {name for n in ast.walk(tree) if isinstance(n, ast.Delete)
                              for t in n.targets for name in self._target_names(t)}
        self.deleted_names.update(n.name for n in ast.walk(tree) if isinstance(n, ast.ExceptHandler) and n.name)
        self._register_module_globals(tree.body)
        self._register_definitions(tree.body, [], top_level=True)

        self._add_constructor()
        for node in self.function_nodes:
            self._compile_function(node)
        for node in self.class_nodes:
            self._compile_class_methods(node)

        if self.dynamic:
            self._compile_dynamic_main(tree.body)
        else:
            self._compile_main(tree.body)
        return self.cf.to_bytes()

    def _bare_raise_outside_handler(self, nodes, in_handler: bool) -> bool:
        """True when a bare ``raise`` may need the dynamically handled exception (it is not inside a handler of its own function)."""
        for node in nodes:
            if isinstance(node, ast.Raise) and node.exc is None and not in_handler:
                return True
            inner = in_handler
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda)): inner = False
            elif isinstance(node, ast.ExceptHandler): inner = True
            elif isinstance(node, ast.Try):
                if self._bare_raise_outside_handler(node.finalbody, True): return True
                if self._bare_raise_outside_handler(node.body + node.handlers + node.orelse, in_handler): return True
                continue
            if self._bare_raise_outside_handler(ast.iter_child_nodes(node), inner): return True
        return False

    def _register_module_globals(self, body: list[ast.stmt]) -> None:
        names: set[str] = {"__name__", "__package__", "__file__", "__loader__", "__spec__"}

        def bind_target(target: ast.expr) -> None:
            if isinstance(target, ast.Name):
                names.add(target.id)
            elif isinstance(target, (ast.Tuple, ast.List)):
                for elt in target.elts:
                    bind_target(elt)

        def visit_stmt(stmt: ast.stmt) -> None:
            if isinstance(stmt, ast.Assign):
                for target in stmt.targets: bind_target(target)
            elif isinstance(stmt, ast.AnnAssign):
                bind_target(stmt.target)
            elif isinstance(stmt, ast.AugAssign):
                bind_target(stmt.target)
            elif isinstance(stmt, ast.Delete):
                for target in stmt.targets: bind_target(target)
            elif isinstance(stmt, ast.Import):
                for alias in stmt.names:
                    names.add(alias.asname or alias.name.split('.')[0])
            elif isinstance(stmt, ast.ImportFrom):
                resolved=_resolve_import_name(
                    self.module_name, self.package_name == self.module_name,
                    stmt.module, stmt.level
                )
                for alias in stmt.names:
                    if alias.name == '*':
                        exports=self.import_exports.get(resolved or "")
                        if exports is None:
                            if self.dynamic or self.import_map.get(resolved or "") is None:
                                continue   # resolved when the statement runs (a static module reports this later)
                            raise CompileError(f"star import from {resolved!r} requires a statically analyzable __all__ or module exports")
                        names.update(exports)
                    else:
                        names.add(alias.asname or alias.name)
            elif isinstance(stmt, (ast.For, ast.AsyncFor)):
                bind_target(stmt.target)
                for child in stmt.body + stmt.orelse: visit_stmt(child)
            elif isinstance(stmt, (ast.If, ast.While)):
                for child in stmt.body + stmt.orelse: visit_stmt(child)
            elif isinstance(stmt, ast.Try):
                for child in stmt.body + stmt.orelse + stmt.finalbody: visit_stmt(child)
                for handler in stmt.handlers:
                    if handler.name: names.add(handler.name)
                    for child in handler.body: visit_stmt(child)
            elif isinstance(stmt, (ast.With, ast.AsyncWith)):
                for item in stmt.items:
                    if item.optional_vars is not None: bind_target(item.optional_vars)
                for child in stmt.body: visit_stmt(child)
            # Function/class bodies are separate namespaces.

        for stmt in body:
            if not isinstance(stmt, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                visit_stmt(stmt)
        self.global_names.update(names)
        for name in sorted(names):
            field_name = f"__py_global_{name}"
            self.global_fields[name] = field_name
            self.cf.add_field(Field(field_name))

    def _emit_namespace_call(self, fname: str, b: CodeBuilder, scope: Scope) -> None:
        """globals() / locals() / vars() with no arguments."""
        module_level = scope.module and scope.namespace_slot is None
        if fname == "globals" or module_level:
            if self.dynamic:
                b.getstatic(self.class_name, "__py_globals" if fname == "globals" else "__py_locals", OBJ)
            else:
                b.ldc_string(self.class_name.replace("/", ".")); b.invokestatic(NAMESPACES, "moduleNamespace", f"({OBJ}){OBJ}")
        else:
            b.invokestatic(NAMESPACES, "localsCall", f"(){OBJ}")

    def _emit_module_name(self, b: CodeBuilder) -> None:
        """Push the module name for __module__: exec'd code takes it from the globals it runs in."""
        if self.dynamic:
            b.getstatic(self.class_name, "__py_globals", OBJ); b.ldc_string(self.module_name)
            b.invokestatic(IMPORT_SUPPORT, "dynamicModuleName", f"({OBJ}{OBJ}){OBJ}")
        else:
            b.ldc_string(self.module_name)

    def _emit_run_time_import(self, name: str, fromlist: str | None, level: int, b: CodeBuilder, scope: Scope) -> None:
        """Leave the result of __import__(name, globals, None, fromlist, level) on the stack (ImportSupport.importName)."""
        b.ldc_string(name)
        if self.dynamic:
            b.getstatic(self.class_name, "__py_globals", OBJ)
        else:
            b.aconst_null()
        b.ldc_string(self.module_name); b.ldc_string(self.package_name or "")
        if fromlist is None:
            b.aconst_null()
        else:
            b.ldc_string(fromlist)
        b.ldc_string(str(level))
        b.invokestatic(IMPORT_SUPPORT, "importName", f"({OBJ*6}){OBJ}")

    def _emit_implicit_namespace_call(self, node: ast.Call, b: CodeBuilder, scope: Scope) -> None:
        """exec(x)/eval(x) in a statically compiled module: run in a snapshot of the caller's namespaces.

        Module level: the known module globals are snapshotted into one dict and copied back afterwards
        (assignments and deletions by the executed code persist). Inside a function the locals are a
        read-only snapshot, as for locals() since CPython 3.13."""
        g_slot = scope.temp()
        b.invokestatic(RUNTIME, "dict0", f"(){OBJ}"); b.astore(g_slot)
        for name in sorted(self.global_fields):
            b.aload(g_slot); b.getstatic(self.class_name, self.global_fields[name], OBJ); b.ldc_string(name)
            b.invokestatic(RUNTIME, "snapshotPut", f"({OBJ*3})V")
        b.aload(g_slot); b.getstatic(self.class_name, "__py_extras", OBJ)
        b.invokestatic(RUNTIME, "dictUpdate", f"({OBJ*2})V")
        if scope.module:
            l_slot = g_slot
        else:
            l_slot = scope.temp()
            b.invokestatic(RUNTIME, "snapshotLocals", f"(){OBJ}"); b.astore(l_slot)
        b.ldc_string(node.func.id)
        self._expr(node.args[0], b, scope)
        b.aload(g_slot); b.aload(l_slot)
        b.invokestatic(RUNTIME, "execBuiltin", f"({OBJ*4}){OBJ}")
        if scope.module:
            result_slot = scope.temp(); b.astore(result_slot)
            for name in sorted(self.global_fields):
                b.aload(g_slot); b.ldc_string(name); b.invokestatic(RUNTIME, "snapshotGet", f"({OBJ*2}){OBJ}")
                b.putstatic(self.class_name, self.global_fields[name], OBJ)
            b.getstatic(self.class_name, "__py_extras", OBJ); b.aload(g_slot); b.ldc_string(",".join(sorted(self.global_fields)))
            b.invokestatic(RUNTIME, "snapshotExtras", f"({OBJ*3})V")
            b.aload(result_slot)

    def _emit_owner(self, b: CodeBuilder) -> None:
        """The class holding compiled function bodies: its name, or (dynamic code) the Class itself."""
        if self.dynamic:
            b.ldc_class(self.class_name)
        else:
            b.ldc_string(self.class_name.replace("/", "."))

    def _ns_field(self, scope: Scope, name: str) -> str:
        """Module-level names live in the exec locals mapping; function-level globals in the globals mapping."""
        return "__py_locals" if scope.module and name not in scope.global_decl and name not in self.declared_global else "__py_globals"

    def _emit_ns_load(self, name: str, b: CodeBuilder, scope: Scope, via_function: bool) -> None:
        local_first = scope.module and not via_function and name not in scope.global_decl and name not in self.declared_global
        b.getstatic(self.class_name, "__py_locals" if local_first else "__py_globals", OBJ)
        b.getstatic(self.class_name, "__py_globals", OBJ)
        b.ldc_string(name); b.invokestatic(RUNTIME, "nsLoad", f"({OBJ*3}){OBJ}")

    def _load_name(self, name: str, b: CodeBuilder, scope: Scope, via_function: bool = False) -> None:
        if name == "__debug__":
            self._boxed_bool(self.optimize == 0, b); return
        if name == "__builtins__" and not self.dynamic and name not in self.global_names and not scope.has(name):
            b.ldc_string(self.module_name); b.invokestatic(NAMESPACES, "builtinsFor", f"({OBJ}){OBJ}"); return
        if name.startswith("$gen_hoist_"):
            # Operand evaluated before a suspension and persisted in the generator environment.
            self._gen_env_load(name, b, scope)
            return
        if scope.namespace_slot is not None and name not in scope.global_decl and name not in scope.nonlocal_decl:
            key = self._class_attr_name(name)
            missing, done = b.label(), b.label()
            b.aload(scope.namespace_slot); b.ldc_string(key)
            b.invokestatic(RUNTIME, "classNamespaceHas", f"({OBJ}{OBJ})Z")
            b.ifeq(missing)
            b.aload(scope.namespace_slot); b.ldc_string(key)
            b.invokestatic(RUNTIME, "getitem", f"({OBJ}{OBJ}){OBJ}")
            b.goto(done); b.mark(missing)
            parent = scope.parent
            while parent is not None and parent.namespace_slot is not None:
                parent = parent.parent
            if name in scope.local_names:
                parent = Scope(module=True)
            try:
                self._load_name(name, b, parent or Scope())
            except CompileError:
                b.invokestatic(RUNTIME, "unbound", f"(){OBJ}"); b.ldc_string(name)
                b.invokestatic(RUNTIME, "globalGet", f"({OBJ}{OBJ}){OBJ}")
            b.mark(done)
            return
        if name in scope.global_decl:
            pass
        elif scope.env_mode and scope.env_slot is not None:
            if name in scope.local_names:
                b.aload(scope.env_slot); b.ldc_string(name)
                b.invokestatic(RUNTIME, "envGetLocal", f"({OBJ}{OBJ}){OBJ}")
                return
            if name in scope.free_names or name in scope.nonlocal_decl:
                b.aload(scope.env_slot)
                if scope.namespace_slot is not None and name in scope.nonlocal_decl:
                    b.invokestatic(RUNTIME, "envParent", f"({OBJ}){OBJ}")
                b.ldc_string(name)
                b.invokestatic(RUNTIME, "envGet", f"({OBJ}{OBJ}){OBJ}")
                return
        elif scope.parent is not None and not scope.has_local(name):
            self._load_name(name, b, scope.parent, via_function or not scope.module)
            return
        elif scope.has(name):
            b.aload(scope.get(name))
            if name in scope.checked_names:
                b.ldc_string(name); b.invokestatic(RUNTIME, "requireLocal", f"({OBJ}{OBJ}){OBJ}")
            return
        if self.dynamic:
            self._emit_ns_load(name, b, scope, via_function); return
        if name in self.functions:
            info = self.functions[name]
            assert info.function_field is not None
            b.getstatic(self.class_name, info.function_field, OBJ)
            self._check_global_binding(name, b); return
        if name in self.classes:
            b.getstatic(self.class_name, self.classes[name].class_field, OBJ)
            self._check_global_binding(name, b); return
        if name in self.global_names:
            b.getstatic(self.class_name, self.global_fields[name], OBJ)
            self._check_global_binding(name, b); return
        if name in {"NotImplemented", "Ellipsis"}:
            b.invokestatic(RUNTIME, "notImplemented" if name == "NotImplemented" else "ellipsis", f"(){OBJ}"); return
        if name == "__debug__":
            self._boxed_bool(True, b); return
        if name in BUILTIN_FUNCTIONS:
            b.ldc_string(name); b.invokestatic(RUNTIME, "builtinFunction", f"({OBJ}){OBJ}"); return
        if name in BUILTIN_TYPES or name in EXCEPTION_TYPES:
            b.ldc_string(name); b.invokestatic(RUNTIME, "builtinType", f"({OBJ}){OBJ}"); return
        if self.has_implicit_exec:
            b.getstatic(self.class_name, "__py_extras", OBJ); b.getstatic(self.class_name, "__py_extras", OBJ)
            b.ldc_string(name); b.invokestatic(RUNTIME, "nsLoad", f"({OBJ*3}){OBJ}"); return
        raise CompileError(f"Name {name!r} referenced before assignment")

    def _store_name(self, name: str, b: CodeBuilder, scope: Scope, *, temp_scope: Scope | None = None) -> None:
        if name == "__debug__":
            raise CompileError("cannot assign to __debug__")
        if scope.module or name not in scope.global_decl:
            b.ldc_string(name); b.invokestatic(RUNTIME, "frameSetLocalValue", f"({OBJ}{OBJ}){OBJ}")
        if scope.namespace_slot is not None and name not in scope.global_decl and name not in scope.nonlocal_decl:
            value_slot = (temp_scope or scope).temp(); b.astore(value_slot)
            b.aload(scope.namespace_slot); b.ldc_string(self._class_attr_name(name)); b.aload(value_slot)
            b.invokestatic(RUNTIME, "classNamespacePut", f"({OBJ}{OBJ}{OBJ})V")
        elif self.dynamic and (scope.module or name in scope.global_decl):
            b.getstatic(self.class_name, self._ns_field(scope, name), OBJ); b.ldc_string(name)
            b.invokestatic(RUNTIME, "nsStore", f"({OBJ*3})V")
        elif scope.module or name in scope.global_decl:
            if name not in self.global_fields:
                self.global_names.add(name)
                field_name = f"__py_global_{name}"
                self.global_fields[name] = field_name
                self.cf.add_field(Field(field_name))
            b.putstatic(self.class_name, self._global_field(name), OBJ)
        elif scope.env_mode and scope.env_slot is not None:
            alloc_scope = temp_scope or scope
            value_slot = alloc_scope.temp(); b.astore(value_slot)
            b.aload(scope.env_slot); b.ldc_string(name); b.aload(value_slot)
            method = "envSetNonlocal" if name in scope.nonlocal_decl else "envSetLocal"
            b.invokestatic(RUNTIME, method, f"({OBJ}{OBJ}{OBJ})V")
        else:
            b.astore(scope.define(name))

    def _alias_global_field(self, name: str, field: str) -> None:
        previous = self.global_fields.get(name)
        if previous and previous != field:
            self.cf.fields = [f for f in self.cf.fields if f.name != previous]
        self.global_fields[name] = field
        self.global_names.add(name)

    def _global_field(self, name: str) -> str:
        return self.global_fields[name]

    def _check_global_binding(self, name: str, b: CodeBuilder) -> None:
        b.ldc_string(name); b.invokestatic(RUNTIME, "globalGet", f"({OBJ}{OBJ}){OBJ}")

    def _prepare_deletions(self, b: CodeBuilder, scope: Scope, local_names: set[str]) -> None:
        scope.checked_names = local_names & self.deleted_names
        if not scope.env_mode:
            for name in sorted(scope.checked_names):
                if not scope.has(name):
                    b.invokestatic(RUNTIME, "unbound", f"(){OBJ}"); b.astore(scope.define(name))

    def _delete_target(self, target: ast.expr, b: CodeBuilder, scope: Scope) -> None:
        if isinstance(target, (ast.Tuple, ast.List)):
            for element in target.elts: self._delete_target(element, b, scope)
        elif isinstance(target, ast.Name):
            name = target.id
            if scope.namespace_slot is not None and name not in scope.global_decl and name not in scope.nonlocal_decl:
                b.aload(scope.namespace_slot); b.ldc_string(self._class_attr_name(name))
                b.invokestatic(RUNTIME, "classNamespaceDelete", f"({OBJ}{OBJ})V")
            elif self.dynamic and (scope.module or name in scope.global_decl):
                b.getstatic(self.class_name, self._ns_field(scope, name), OBJ); b.ldc_string(name)
                b.invokestatic(RUNTIME, "nsDelete", f"({OBJ*2})V")
            elif scope.module or name in scope.global_decl:
                field = self._global_field(name)
                b.getstatic(self.class_name, field, OBJ); b.ldc_string(name)
                b.invokestatic(RUNTIME, "requireGlobal", f"({OBJ}{OBJ}){OBJ}"); b.pop()
                b.invokestatic(RUNTIME, "unbound", f"(){OBJ}"); b.putstatic(self.class_name, field, OBJ)
            elif scope.env_mode:
                b.aload(scope.env_slot); b.ldc_string(name)
                b.invokestatic(RUNTIME, "envDelNonlocal" if name in scope.nonlocal_decl else "envDelLocal", f"({OBJ}{OBJ})V")
            else:
                b.aload(scope.get(name)); b.ldc_string(name)
                b.invokestatic(RUNTIME, "requireLocal", f"({OBJ}{OBJ}){OBJ}"); b.pop()
                b.invokestatic(RUNTIME, "unbound", f"(){OBJ}"); b.astore(scope.get(name))
            b.ldc_string(name); b.invokestatic(RUNTIME, "frameDelLocal", f"({OBJ})V")
        elif isinstance(target, ast.Subscript):
            self._expr(target.value, b, scope); self._expr(target.slice, b, scope)
            b.invokestatic(RUNTIME, "delitem", f"({OBJ}{OBJ})V")
        elif isinstance(target, ast.Attribute):
            self._expr(target.value, b, scope); b.ldc_string(self._class_attr_name(target.attr))
            b.invokestatic(RUNTIME, "delattr", f"({OBJ}{OBJ})V")
        else:
            raise CompileError(f"Unsupported deletion target: {type(target).__name__}")

    @staticmethod
    def _target_names(target: ast.AST) -> set[str]:
        out: set[str] = set()
        if isinstance(target, ast.Name): out.add(target.id)
        elif isinstance(target, (ast.Tuple, ast.List)):
            for elt in target.elts: out.update(Compiler._target_names(elt))
        elif isinstance(target, ast.Starred): out.update(Compiler._target_names(target.value))
        return out

    def _function_locals(self, node: ast.FunctionDef | ast.AsyncFunctionDef | ast.ClassDef) -> tuple[set[str], set[str], set[str]]:
        locals_: set[str] = set()
        if not isinstance(node, ast.ClassDef):
            locals_.update(a.arg for a in node.args.posonlyargs + node.args.args + node.args.kwonlyargs)
            if node.args.vararg: locals_.add(node.args.vararg.arg)
            if node.args.kwarg: locals_.add(node.args.kwarg.arg)
        globals_: set[str] = set(); nonlocals: set[str] = set()

        class NamedAssignments(ast.NodeVisitor):
            def visit_NamedExpr(visitor, expression):
                locals_.update(self._target_names(expression.target))
                visitor.visit(expression.value)
            def visit_Lambda(visitor, expression):
                for default in expression.args.defaults + [x for x in expression.args.kw_defaults if x is not None]:
                    visitor.visit(default)
            def visit_FunctionDef(visitor, definition):
                for expression in definition.decorator_list + definition.args.defaults + [x for x in definition.args.kw_defaults if x is not None]:
                    visitor.visit(expression)
            visit_AsyncFunctionDef = visit_FunctionDef
            def visit_ClassDef(visitor, definition):
                for expression in definition.decorator_list + definition.bases + [x.value for x in definition.keywords]:
                    visitor.visit(expression)

        for statement in node.body:
            NamedAssignments().visit(statement)

        def visit_stmt(stmt: ast.stmt) -> None:
            if isinstance(stmt, ast.Global): globals_.update(stmt.names); return
            if isinstance(stmt, ast.Nonlocal): nonlocals.update(stmt.names); return
            if isinstance(stmt, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                locals_.add(stmt.name); return
            if isinstance(stmt, ast.Assign):
                for t in stmt.targets: locals_.update(self._target_names(t))
            elif isinstance(stmt, ast.AnnAssign): locals_.update(self._target_names(stmt.target))
            elif isinstance(stmt, ast.AugAssign): locals_.update(self._target_names(stmt.target))
            elif isinstance(stmt, ast.Delete):
                for target in stmt.targets: locals_.update(self._target_names(target))
            elif isinstance(stmt, ast.Import):
                locals_.update(alias.asname or alias.name.split('.')[0] for alias in stmt.names)
            elif isinstance(stmt, ast.ImportFrom):
                locals_.update(alias.asname or alias.name for alias in stmt.names if alias.name != '*')
            elif isinstance(stmt, (ast.For, ast.AsyncFor)):
                locals_.update(self._target_names(stmt.target))
                for x in stmt.body + stmt.orelse: visit_stmt(x)
                return
            elif isinstance(stmt, (ast.With, ast.AsyncWith)):
                for item in stmt.items:
                    if item.optional_vars: locals_.update(self._target_names(item.optional_vars))
                for x in stmt.body: visit_stmt(x)
                return
            elif isinstance(stmt, ast.Try):
                for x in stmt.body + stmt.orelse + stmt.finalbody: visit_stmt(x)
                for h in stmt.handlers:
                    if h.name: locals_.add(h.name)
                    for x in h.body: visit_stmt(x)
                return
            elif isinstance(stmt, (ast.If, ast.While)):
                for x in stmt.body + stmt.orelse: visit_stmt(x)
                return
            for child in ast.iter_child_nodes(stmt):
                if isinstance(child, ast.stmt): visit_stmt(child)

        for stmt in node.body: visit_stmt(stmt)
        locals_.difference_update(globals_); locals_.difference_update(nonlocals)
        return locals_, globals_, nonlocals

    @staticmethod
    def _needs_class_cell(node: ast.AST) -> bool:
        # Python creates the implicit cell for super references, including
        # nested function/lambda references, but nested classes are separate.
        class Visitor(ast.NodeVisitor):
            found = False
            def visit_Name(self, item):
                if isinstance(item.ctx, ast.Load) and item.id in {"super", "__class__"}:
                    self.found = True
            def visit_ClassDef(self, item): pass
        visitor = Visitor()
        if isinstance(node, ast.Lambda): visitor.visit(node.body)
        else:
            for item in node.body: visitor.visit(item)
        return visitor.found

    def _class_needs_cell(self, node: ast.ClassDef) -> bool:
        class Visitor(ast.NodeVisitor):
            needed = False
            def visit_ClassDef(visitor, item): pass
            def visit_FunctionDef(visitor, item):
                if "__class__" in self.function_infos[id(item)].free_names:
                    visitor.needed = True
            visit_AsyncFunctionDef = visit_FunctionDef
            def visit_Lambda(visitor, item):
                if "__class__" in self.function_infos[id(item)].free_names:
                    visitor.needed = True
        visitor = Visitor()
        for item in node.body: visitor.visit(item)
        return visitor.needed

    def _referenced_names_shallow(self, node: ast.FunctionDef | ast.AsyncFunctionDef) -> set[str]:
        refs: set[str] = set()
        class Visitor(ast.NodeVisitor):
            def visit_Name(self, n: ast.Name) -> None:
                if isinstance(n.ctx, ast.Load): refs.add(n.id)
            def visit_FunctionDef(self, n: ast.FunctionDef) -> None:
                if n is node:
                    # Decorator expressions execute in the enclosing scope, not
                    # inside the function body, so they are not function free refs.
                    for d in n.args.defaults: self.visit(d)
                    for d in n.args.kw_defaults:
                        if d is not None: self.visit(d)
                    for st in n.body: self.visit(st)
                else: self._visit_header(n)
                # nested body is a separate scope
            def visit_AsyncFunctionDef(self, n: ast.AsyncFunctionDef) -> None:
                if n is node:
                    for d in n.args.defaults: self.visit(d)
                    for d in n.args.kw_defaults:
                        if d is not None: self.visit(d)
                    for st in n.body: self.visit(st)
                else: self._visit_header(n)
            def _visit_header(self, n) -> None:
                # a nested definition's decorators and defaults run in this scope (typeparams.py relies on it)
                for d in n.decorator_list: self.visit(d)
                for d in n.args.defaults: self.visit(d)
                for d in n.args.kw_defaults:
                    if d is not None: self.visit(d)
            def visit_ClassDef(self, n: ast.ClassDef) -> None:
                for d in n.decorator_list + n.bases: self.visit(d)
                for k in n.keywords: self.visit(k.value)
                self._visit_body_names(n.body)
            def visit_Lambda(self, n: ast.Lambda) -> None:
                for d in n.args.defaults: self.visit(d)
                for d in n.args.kw_defaults:
                    if d is not None: self.visit(d)
                self._visit_body_names([n.body])
            def _visit_body_names(self, body) -> None:
                # a nested class or lambda body may use variables of scopes further out: this scope must carry them
                for st in body:
                    for x in ast.walk(st):
                        if isinstance(x, ast.Name) and isinstance(x.ctx, ast.Load): refs.add(x.id)
        Visitor().visit(node)
        return refs

    def _register_function_tree(self, node: ast.FunctionDef | ast.AsyncFunctionDef, enclosing_locals: list[set[str]], *, top_level: bool = False, qualname: str = "") -> None:
        local_names, global_names, nonlocal_names = self._function_locals(node)
        refs = self._referenced_names_shallow(node)
        if self._needs_class_cell(node): refs.add("__class__")
        free_names = {name for name in refs if name not in local_names and name not in global_names and any(name in s for s in reversed(enclosing_locals))}
        for name in nonlocal_names:
            if not any(name in s for s in reversed(enclosing_locals)):
                raise CompileError(f"no binding for nonlocal {name!r} found")
            free_names.add(name)
        has_nested = any(isinstance(x, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef, ast.Lambda, ast.GeneratorExp)) for st in node.body for x in ast.walk(st) if x is not node)
        class YieldFinder(ast.NodeVisitor):
            found = False
            def visit_Yield(self, n): self.found = True
            def visit_YieldFrom(self, n): self.found = True
            def visit_FunctionDef(self, n):
                if n is node:
                    for st in n.body: self.visit(st)
            def visit_AsyncFunctionDef(self, n):
                if n is node:
                    for st in n.body: self.visit(st)
            def visit_ClassDef(self, n): return
            def visit_Lambda(self, n): return
        yf = YieldFinder(); yf.visit(node)
        is_generator = yf.found and isinstance(node, ast.FunctionDef)
        is_async = isinstance(node, ast.AsyncFunctionDef)
        is_async_generator = is_async and yf.found
        env_mode = is_generator or is_async or bool(enclosing_locals) or has_nested or bool(nonlocal_names)

        posonly = [a.arg for a in node.args.posonlyargs]
        poskw = [a.arg for a in node.args.args]
        kwonly = [a.arg for a in node.args.kwonlyargs]
        positional = posonly + poskw
        defaults: dict[str, ast.expr] = {}
        if node.args.defaults:
            for name, expr in zip(positional[-len(node.args.defaults):], node.args.defaults): defaults[name] = expr
        kw_defaults = {a.arg: e for a, e in zip(node.args.kwonlyargs, node.args.kw_defaults) if e is not None}
        direct_name = top_level and node.name not in self.functions
        java_name = node.name if direct_name else f"__py_nested_{self._method_counter}_{node.name}"
        if not direct_name: self._method_counter += 1
        function_field = self.global_fields.get(node.name, f"__py_function_{node.name}") if top_level else None
        if function_field and not any(field.name == function_field for field in self.cf.fields):
            self.cf.add_field(Field(function_field))
        info = FunctionInfo(node.name, java_name, posonly, poskw, kwonly,
            node.args.vararg.arg if node.args.vararg else None,
            node.args.kwarg.arg if node.args.kwarg else None,
            defaults, kw_defaults, function_field, top_level, env_mode,
            local_names, free_names, nonlocal_names, is_generator, is_async, is_async_generator)
        info.qualname = qualname or node.name
        self.function_infos[id(node)] = info
        self.function_nodes.append(node)
        if top_level:
            self.functions[node.name] = info
            self._alias_global_field(node.name, function_field)

        new_enclosing = enclosing_locals + [local_names]
        inner_qualname = (qualname or node.name) + ".<locals>."
        if node.name.startswith(TP_PREFIX): inner_qualname = qualname[:len(qualname) - len(node.name)]  # implicit type-parameter scope (typeparams.py)
        self._register_definitions(node.body, new_enclosing, qualname=inner_qualname)

    def _register_definitions(self, body, enclosing_locals, *, top_level=False, qualname=""):
        for stmt in body:
            if isinstance(stmt, (ast.FunctionDef, ast.AsyncFunctionDef)):
                self._register_function_tree(stmt, enclosing_locals, top_level=top_level,
                                             qualname=qualname + stmt.name)
            elif isinstance(stmt, ast.ClassDef):
                self._register_class(stmt, enclosing_locals, top_level=top_level,
                                     qualname=qualname + stmt.name)
            else:
                # Control-flow suites keep their enclosing lexical scope.
                for child in ast.iter_child_nodes(stmt):
                    if isinstance(child, ast.stmt):
                        self._register_definitions([child], enclosing_locals,
                                                   top_level=top_level, qualname=qualname)
                    elif isinstance(child, ast.ExceptHandler):
                        self._register_definitions(child.body, enclosing_locals,
                                                   top_level=top_level, qualname=qualname)


    @staticmethod
    def _mangle_class_private(class_name: str, name: str) -> str:
        if not name.startswith("__") or name.endswith("__"):
            return name
        stripped = class_name.lstrip("_")
        if not stripped:
            return name
        return "_" + stripped + name

    def _class_attr_name(self, name: str) -> str:
        if self.current_class is None:
            return name
        return self._mangle_class_private(self.current_class.name, name)

    def _register_class(self, node: ast.ClassDef, enclosing_locals: list[set[str]], *,
                        top_level: bool, qualname: str) -> None:
        metaclass: ast.expr | None = None
        class_keywords: list[tuple[str, ast.expr]] = []
        for kw in node.keywords:
            if kw.arg == "metaclass":
                metaclass = kw.value
            class_keywords.append((kw.arg, kw.value))
        bases: list[ast.expr] = list(node.bases)
        local = not top_level
        class_field = self.global_fields.get(node.name, f"__py_class_{node.name}") if top_level else f"__py_local_class_{len(self.class_nodes)}"
        if top_level and not any(field.name == class_field for field in self.cf.fields):
            self.cf.add_field(Field(class_field))
        info = ClassInfo(node.name, bases, class_field,
                         metaclass, class_keywords, local, qualname)
        self.class_infos[id(node)] = info
        self.class_nodes.append(node)
        if top_level:
            self.classes[node.name] = info
            self._alias_global_field(node.name, class_field)
        # Class attributes are not a method lexical scope. Capture the
        # enclosing function plus a private cell for zero-argument super.
        _, globals_, nonlocals = self._function_locals(node)
        for name in nonlocals:
            if not any(name in names for names in enclosing_locals):
                raise CompileError(f"no binding for nonlocal {name!r} found")
        # Allocate declared globals before main initializes every field to UNBOUND.
        for name in globals_ - self.global_fields.keys():
            self.global_names.add(name)
            field = f"__py_global_{name}"
            self.global_fields[name] = field
            self.cf.add_field(Field(field))
        self._register_definitions(node.body, [*enclosing_locals, {"__class__"}],
                                   qualname=qualname + ".")
        for item in self._class_method_nodes(node.body):
            self.function_nodes.remove(item)

    def _class_method_nodes(self, body):
        """Methods in this class's suites, excluding nested lexical scopes."""
        for item in body:
            if isinstance(item, (ast.FunctionDef, ast.AsyncFunctionDef)):
                yield item
            elif not isinstance(item, ast.ClassDef):
                for child in ast.iter_child_nodes(item):
                    if isinstance(child, ast.stmt):
                        yield from self._class_method_nodes([child])
                    elif isinstance(child, ast.ExceptHandler):
                        yield from self._class_method_nodes(child.body)


    @_preserve_codegen_state
    def _compile_class_methods(self, node: ast.ClassDef) -> None:
        info = self.class_infos[id(node)]
        old_class = self.current_class
        self.current_class = info
        try:
            for item in self._class_method_nodes(node.body):
                self._compile_function(item)
        finally:
            self.current_class = old_class

    def _add_constructor(self) -> None:
        b = CodeBuilder(self.cf.cp)
        b.aload(0)
        b.invokespecial("java/lang/Object", "<init>", "()V")
        b.return_()
        code = b.finish()
        self.cf.add_method(Method("<init>", "()V", code, max_stack=2, max_locals=1, access=0x0001, exception_table=b.exception_table))

    def _emit_module_body(self, body: list[ast.stmt], b: CodeBuilder, scope: Scope) -> Scope:
        """Module-level statements. A method holds at most 64KB of bytecode, so once the entry method has grown past
        MODULE_CHUNK_LIMIT the remaining statements go into helper methods that the entry method calls in order
        (module-level names live in static fields, so no local state crosses a statement boundary).
        Returns the entry method's scope."""
        main_scope = scope
        part_builder = None
        part_number = 0
        for stmt in body:
            if b.pos > MODULE_CHUNK_LIMIT:
                if part_builder is not None:
                    self._finish_module_part(b, scope, part_number)
                part_number += 1
                part_builder = CodeBuilder(self.cf.cp)
                b = part_builder
                scope = Scope(start_slot=2, module=True)
                self.main_builder.invokestatic(self.class_name, f"__py_module_part{part_number}", "()V")
            self._stmt(stmt, b, scope, in_function=False)
        if part_builder is not None:
            self._finish_module_part(b, scope, part_number)
        return main_scope

    def _finish_module_part(self, b: CodeBuilder, scope: Scope, number: int) -> None:
        b.return_()
        code = b.finish()
        self.cf.add_method(Method(f"__py_module_part{number}", "()V", code, max_locals=max(8, scope.next_slot + 2),
                                  exception_table=b.exception_table))

    def _compile_main(self, body: list[ast.stmt]) -> None:
        self.current_frame_name,self.current_frame_firstlineno="<module>",1
        b = CodeBuilder(self.cf.cp)
        self.main_builder = b
        scope = Scope(start_slot=1, module=True)
        for name in sorted(self.global_fields):
            b.invokestatic(RUNTIME, "unbound", f"(){OBJ}"); b.putstatic(self.class_name, self._global_field(name), OBJ)
        if self.has_implicit_exec:
            b.invokestatic(RUNTIME, "dict0", f"(){OBJ}"); b.putstatic(self.class_name, "__py_extras", OBJ)
        self.loop_stack = []; self.exception_stack = []; self.handler_depths = []; self.cleanup_stack = []; self.finally_stack = []
        b.ldc_string("<module>"); b.ldc_string(self.filename); self._emit_int(1,b); self._emit_owner(b)
        b.invokestatic(RUNTIME,"pushModuleFrame",f"({OBJ*4})V")
        for chunk_start in range(0, len(self.caret_table), 16000):
            b.ldc_string(self.filename); b.ldc_string(self.caret_table[chunk_start:chunk_start + 16000])
            b.invokestatic(RUNTIME2, "registerCarets", f"({OBJ}{OBJ})V")
        b.ldc_string(self.module_name); self._store_name("__name__", b, scope)
        if self.module_name == "__main__":
            b.aconst_null()
        else:
            b.ldc_string(self.package_name)
        self._store_name("__package__", b, scope)
        b.ldc_string(self.filename); self._store_name("__file__", b, scope)
        if self.module_name == "__main__":
            b.aconst_null(); self._store_name("__loader__", b, scope)
            b.aconst_null(); self._store_name("__spec__", b, scope)
        else:
            b.ldc_string(self.module_name); b.ldc_string(self.filename)
            b.invokestatic(RUNTIME, "makeModuleLoader", f"({OBJ}{OBJ}){OBJ}")
            loader_slot=scope.temp(); b.astore(loader_slot)
            b.aload(loader_slot); self._store_name("__loader__", b, scope)
            b.ldc_string(self.module_name); b.ldc_string(self.filename); b.ldc_string(self.package_name)
            self._boxed_bool(self.module_name == self.package_name, b)
            b.aload(loader_slot)
            b.invokestatic(RUNTIME, "makeModuleSpec", f"({OBJ*5}){OBJ}")
            self._store_name("__spec__", b, scope)
        guard_start, guard_end, guard_handler = b.label(), b.label(), b.label()
        b.mark(guard_start)
        scope = self._emit_module_body(body, b, scope)
        b.mark(guard_end)
        b.invokestatic(RUNTIME,"popLogicalFrame",f"()V")
        b.return_()
        if self.module_name == "__main__":
            # an exception leaving the program is reported like CPython does (sys.excepthook) and sets the exit status
            guard_slot = scope.temp()
            b.mark(guard_handler); b.astore(guard_slot)
            b.aload(guard_slot); b.invokestatic("pyjvm315/runtime/ExceptionGroupSupport", "handleUncaught", f"({OBJ})V")
            b.return_()
            b.add_exception_handler(guard_start, guard_end, guard_handler, "java/lang/Throwable")
        code = b.finish()
        self.cf.add_method(Method("main", "([Ljava/lang/String;)V", code, max_locals=max(8, scope.next_slot + 2), exception_table=b.exception_table))

    def _compile_dynamic_main(self, body: list[ast.stmt]) -> None:
        """public static Object run(Object globals, Object locals): exec()/eval() entry point."""
        for name in ("__py_globals", "__py_locals"):
            self.cf.add_field(Field(name))
        self.current_frame_name, self.current_frame_firstlineno = "<module>", 1
        b = CodeBuilder(self.cf.cp)
        self.main_builder = b
        scope = Scope(start_slot=2, module=True)
        b.aload(0); b.putstatic(self.class_name, "__py_globals", OBJ)
        b.aload(1); b.putstatic(self.class_name, "__py_locals", OBJ)
        self.loop_stack = []; self.exception_stack = []; self.handler_depths = []; self.cleanup_stack = []; self.finally_stack = []
        b.ldc_string("<module>"); b.ldc_string(self.filename); self._emit_int(1, b); self._emit_owner(b)
        b.invokestatic(RUNTIME, "pushModuleFrame", f"({OBJ*4})V")
        b.ldc_class(self.class_name); b.aload(1)
        b.invokestatic(NAMESPACES, "frameSetModule", f"({OBJ*2})V")
        if self.mode == "eval":
            assert self.eval_expr is not None
            self._expr(self.eval_expr, b, scope)
        else:
            scope = self._emit_module_body(body, b, scope)
            b.aconst_null()
        b.invokestatic(RUNTIME, "popLogicalFrame", "()V")
        b.areturn()
        code = b.finish()
        self.cf.add_method(Method("run", f"({OBJ*2}){OBJ}", code, max_locals=max(8, scope.next_slot + 2), exception_table=b.exception_table))

    @_preserve_codegen_state
    def _compile_function(self, node: ast.FunctionDef | ast.AsyncFunctionDef) -> None:
        info = self.function_infos[id(node)]
        saved_frame=(self.current_frame_name,self.current_frame_firstlineno)
        self.current_frame_name,self.current_frame_firstlineno=info.name,node.lineno
        if info.is_generator:
            self._compile_generator_function(node, info)
            self.current_frame_name,self.current_frame_firstlineno=saved_frame
            return
        if info.is_async_generator:
            lowered = self._lower_async_function(node) if (
                self._contains_await(node)
                or any(isinstance(x, (ast.AsyncFor,ast.AsyncWith)) for x in ast.walk(node))
                or self._contains_async_comprehension(node)
            ) else node
            if lowered is not node:
                lowered_locals,_,_=self._function_locals(lowered)
                info.local_names.update(lowered_locals)
            self._compile_generator_function(lowered, info, as_async_generator=True)
            self.current_frame_name,self.current_frame_firstlineno=saved_frame
            return
        if info.is_async and (
            self._contains_await(node)
            or any(isinstance(x, (ast.AsyncFor,ast.AsyncWith)) for x in ast.walk(node))
            or self._contains_async_comprehension(node)
        ):
            lowered = self._lower_async_function(node)
            lowered_locals,_,_=self._function_locals(lowered)
            info.local_names.update(lowered_locals)
            self._compile_generator_function(lowered, info, as_coroutine=True)
            self.current_frame_name,self.current_frame_firstlineno=saved_frame
            return
        b = CodeBuilder(self.cf.cp)
        arg_start = 1 if info.env_mode else 0
        scope = Scope(info.bound_args, start_slot=arg_start, env_mode=info.env_mode,
                      local_names=info.local_names, free_names=info.free_names,
                      nonlocal_names=info.nonlocal_names)
        if info.env_mode:
            # slot 0 is the captured parent environment. Create this invocation's child environment.
            b.aload(0); b.invokestatic(RUNTIME, "envChild", f"({OBJ}){OBJ}")
            env_slot = scope.temp(); b.astore(env_slot); scope.env_slot = env_slot
            b.aload(env_slot); b.invokestatic(RUNTIME, "frameSetArgumentEnv", f"({OBJ})V")
            for name in sorted((info.local_names - set(info.bound_args)) | (info.local_names & self.deleted_names)):
                b.aload(env_slot); b.ldc_string(name); b.invokestatic(RUNTIME,"unbound",f"(){OBJ}")
                b.invokestatic(RUNTIME,"envSetLocal",f"({OBJ*3})V")
            for name in info.bound_args:
                b.aload(env_slot); b.ldc_string(name); b.aload(scope.get(name))
                b.invokestatic(RUNTIME, "envSetLocal", f"({OBJ}{OBJ}{OBJ})V")
        self._prepare_deletions(b, scope, info.local_names)
        if not info.env_mode:
            # JVM slots must have a reference type on every control-flow path.
            # UNBOUND also preserves Python's local-before-assignment behavior
            # when a branch, loop, or exception path skips the binding.
            for name in sorted(info.local_names - set(info.bound_args)):
                if not scope.has_local(name):
                    b.invokestatic(RUNTIME, "unbound", f"(){OBJ}"); b.astore(scope.define(name))
                scope.checked_names.add(name)
        self.loop_stack = []; self.exception_stack = []; self.handler_depths = []; self.cleanup_stack = []; self.finally_stack = []; self.cleanup_stack = []; self.finally_stack = []
        for stmt in node.body:
            self._stmt(stmt, b, scope, in_function=True)
        b.aconst_null(); b.areturn()
        code = b.finish()
        self.cf.add_method(Method(info.java_name, info.descriptor, code,
            max_locals=max(8, scope.next_slot + 2), exception_table=b.exception_table))
        self.current_frame_name,self.current_frame_firstlineno=saved_frame

    @staticmethod
    def _contains_async_comprehension(node: ast.AST) -> bool:
        return any(
            isinstance(x,(ast.ListComp,ast.SetComp,ast.DictComp)) and any(gen.is_async for gen in x.generators)
            for x in ast.walk(node)
        )

    @staticmethod
    def _comp_is_async(node: ast.AST) -> bool:
        """True for async comprehensions and for comprehensions that await.

        The outermost iterable runs in the enclosing scope, so an await there
        belongs to the enclosing coroutine and does not make the comprehension
        asynchronous.
        """
        if any(gen.is_async for gen in node.generators):
            return True
        class V(ast.NodeVisitor):
            found = False
            def visit_Await(self, n): self.found = True
            def visit_Lambda(self, n): return
            def visit_ListComp(self, n): self.nested(n)
            def visit_SetComp(self, n): self.nested(n)
            def visit_DictComp(self, n): self.nested(n)
            def visit_GeneratorExp(self, n):
                self.visit(n.generators[0].iter)
            def nested(self, n):
                if any(gen.is_async for gen in n.generators): self.found = True
                self.generic_visit(n)
        v = V()
        parts = [node.key, node.value] if isinstance(node, ast.DictComp) else [node.elt]
        for index, gen in enumerate(node.generators):
            if index > 0: parts.append(gen.iter)
            parts.extend(gen.ifs)
        for part in parts: v.visit(part)
        return v.found

    @staticmethod
    def _contains_await(node: ast.AST) -> bool:
        class V(ast.NodeVisitor):
            found = False
            root = None
            def visit_Await(self, n): self.found = True
            def visit_FunctionDef(self, n):
                if n is self.root:
                    for st in n.body: self.visit(st)
            def visit_AsyncFunctionDef(self, n):
                if n is self.root:
                    for st in n.body: self.visit(st)
            def visit_Lambda(self, n): return
        v=V(); v.root=node; v.visit(node); return v.found

    def _lower_async_function(self, node: ast.AsyncFunctionDef) -> ast.FunctionDef:
        # Nested lexical scopes have registration keyed by AST identity. The
        # async lowerer transforms only this function, so preserve those nodes.
        memo = {id(child): child for child in ast.walk(node)
                if child is not node and isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef,
                                                            ast.ClassDef, ast.Lambda))}
        original = deepcopy_ast(node, memo)

        compiler=self
        class Lower(ast.NodeTransformer):
            def __init__(self, root):
                self.root=root
                self.counter=0
            def temp(self, prefix: str) -> str:
                name=f"$async_{prefix}_{self.counter}"
                self.counter += 1
                return name
            def visit_stmts(self, stmts):
                out=[]
                for st in stmts:
                    value=self.visit(st)
                    if value is None: continue
                    if isinstance(value,list): out.extend(value)
                    else: out.append(value)
                return out
            def awaited(self, expr, source):
                return self.visit_Await(ast.copy_location(ast.Await(value=expr),source))
            def visit_FunctionDef(self, n):
                if n is self.root:
                    return self.generic_visit(n)
                return n
            def visit_AsyncFunctionDef(self, n):
                if n is self.root:
                    return self.generic_visit(n)
                return n
            def visit_Lambda(self, n):
                return n
            def visit_ClassDef(self, n):
                return n
            def visit_Await(self, n):
                awaited=self.visit(n.value)
                call=ast.Call(func=ast.Name(id="__py_await_iter_internal",ctx=ast.Load()),args=[awaited],keywords=[])
                return ast.copy_location(ast.YieldFrom(value=call),n)
            def _async_comp(self, n):
                if not compiler._comp_is_async(n):
                    return self.generic_visit(n)
                token=compiler._async_comp_counter
                compiler._async_comp_counter += 1
                compiler._async_comp_nodes[token]=deepcopy_ast(n)
                helper=ast.Call(
                    func=ast.Name(id="__py_async_comp_internal",ctx=ast.Load()),
                    args=[ast.Constant(token)],
                    keywords=[],
                )
                return self.visit_Await(ast.copy_location(ast.Await(value=helper),n))
            def visit_ListComp(self, n): return self._async_comp(n)
            def visit_SetComp(self, n): return self._async_comp(n)
            def visit_DictComp(self, n): return self._async_comp(n)
            def visit_GeneratorExp(self, n):
                if not compiler._comp_is_async(n):
                    return self.generic_visit(n)
                # An asynchronous generator expression keeps its body for its own
                # lowering; only the outermost iterable runs in this coroutine.
                n.generators[0].iter=self.visit(n.generators[0].iter)
                return n
            def visit_AsyncFor(self, n: ast.AsyncFor):
                iterator_name=self.temp("iter")
                exhausted_name=self.temp("exhausted")
                iterator_target=ast.Name(id=iterator_name,ctx=ast.Store())
                iterator_load=ast.Name(id=iterator_name,ctx=ast.Load())
                exhausted_target=ast.Name(id=exhausted_name,ctx=ast.Store())
                exhausted_load=ast.Name(id=exhausted_name,ctx=ast.Load())
                setup_iter=ast.Assign(
                    targets=[iterator_target],
                    value=ast.Call(func=ast.Name(id="aiter",ctx=ast.Load()),args=[self.visit(n.iter)],keywords=[]),
                )
                setup_exhausted=ast.Assign(targets=[exhausted_target],value=ast.Constant(False))
                next_value=ast.Await(value=ast.Call(func=ast.Name(id="anext",ctx=ast.Load()),args=[iterator_load],keywords=[]))
                assign_next=ast.Assign(targets=[self.visit(n.target)],value=self.visit(next_value))
                except_body=[
                    ast.Assign(targets=[ast.Name(id=exhausted_name,ctx=ast.Store())],value=ast.Constant(True)),
                    ast.Break(),
                ]
                try_next=ast.Try(
                    body=[assign_next],
                    handlers=[ast.ExceptHandler(type=ast.Name(id="StopAsyncIteration",ctx=ast.Load()),name=None,body=except_body)],
                    orelse=[],
                    finalbody=[],
                )
                body=[try_next] + self.visit_stmts(n.body)
                loop=ast.While(test=ast.Constant(True),body=body,orelse=[])
                tail=[]
                if n.orelse:
                    tail=[ast.If(test=exhausted_load,body=self.visit_stmts(n.orelse),orelse=[])]
                out=[setup_iter,setup_exhausted,loop,*tail]
                for st in out: ast.copy_location(st,n)
                return out
            def visit_AsyncWith(self, n: ast.AsyncWith):
                original_body=self.visit_stmts(n.body)

                def lower_items(items, body):
                    if not items:
                        return body
                    item=items[0]
                    mgr=self.temp("with_mgr")
                    exc=self.temp("with_exc")
                    caught=self.temp("with_caught")
                    mgr_store=ast.Name(id=mgr,ctx=ast.Store())
                    mgr_load=ast.Name(id=mgr,ctx=ast.Load())
                    exc_store=ast.Name(id=exc,ctx=ast.Store())
                    exc_load=ast.Name(id=exc,ctx=ast.Load())
                    caught_store=ast.Name(id=caught,ctx=ast.Store())
                    caught_load=ast.Name(id=caught,ctx=ast.Load())

                    setup_mgr=ast.Assign(targets=[mgr_store],value=self.visit(item.context_expr))
                    setup_exc=ast.Assign(targets=[exc_store],value=ast.Constant(None))
                    enter_call=ast.Call(
                        func=ast.Attribute(value=mgr_load,attr="__aenter__",ctx=ast.Load()),
                        args=[],keywords=[],
                    )
                    enter_value=self.awaited(enter_call,n)

                    inner=lower_items(items[1:],body)
                    protected=[]
                    if item.optional_vars is None:
                        protected.append(ast.Expr(value=enter_value))
                    else:
                        protected.append(ast.Assign(targets=[self.visit(item.optional_vars)],value=enter_value))
                    protected.extend(inner)

                    exit_exc_call=ast.Call(
                        func=ast.Attribute(value=ast.Name(id=mgr,ctx=ast.Load()),attr="__aexit__",ctx=ast.Load()),
                        args=[
                            ast.Call(func=ast.Name(id="type",ctx=ast.Load()),args=[caught_load],keywords=[]),
                            caught_load,
                            ast.Constant(None),
                        ],
                        keywords=[],
                    )
                    suppress_name=self.temp("with_suppress")
                    suppress=self.awaited(exit_exc_call,n)
                    handler_body=[
                        ast.Assign(targets=[ast.Name(id=exc,ctx=ast.Store())],value=caught_load),
                        ast.Assign(targets=[ast.Name(id=suppress_name,ctx=ast.Store())],value=suppress),
                        ast.If(
                            test=ast.UnaryOp(op=ast.Not(),operand=ast.Name(id=suppress_name,ctx=ast.Load())),
                            body=[ast.Raise(exc=None,cause=None)],
                            orelse=[],
                        ),
                    ]
                    handler=ast.ExceptHandler(
                        type=ast.Name(id="BaseException",ctx=ast.Load()),
                        name=caught,
                        body=handler_body,
                    )

                    exit_normal_call=ast.Call(
                        func=ast.Attribute(value=ast.Name(id=mgr,ctx=ast.Load()),attr="__aexit__",ctx=ast.Load()),
                        args=[ast.Constant(None),ast.Constant(None),ast.Constant(None)],
                        keywords=[],
                    )
                    normal_exit=ast.Expr(value=self.awaited(exit_normal_call,n))
                    finalbody=[
                        ast.If(
                            test=ast.Compare(left=ast.Name(id=exc,ctx=ast.Load()),ops=[ast.Is()],comparators=[ast.Constant(None)]),
                            body=[normal_exit],
                            orelse=[],
                        )
                    ]
                    wrapped=ast.Try(body=protected,handlers=[handler],orelse=[],finalbody=finalbody)
                    return [setup_mgr,setup_exc,wrapped]

                out=lower_items(list(n.items),original_body)
                for st in out: ast.copy_location(st,n)
                return out

        lowered_async=ast.fix_missing_locations(Lower(original).visit(original))
        lowered=ast.FunctionDef(
            name=lowered_async.name,
            args=lowered_async.args,
            body=lowered_async.body,
            decorator_list=[],
            returns=lowered_async.returns,
            type_comment=getattr(lowered_async,"type_comment",None),
            type_params=getattr(lowered_async,"type_params",[]),
        )
        return ast.copy_location(lowered,node)

    @staticmethod
    def _contains_yield(node: ast.AST) -> bool:
        class V(ast.NodeVisitor):
            found = False
            def visit_Yield(self, n): self.found = True
            def visit_YieldFrom(self, n): self.found = True
            def visit_FunctionDef(self, n): return
            def visit_AsyncFunctionDef(self, n):
                if n is node:
                    for st in n.body: self.visit(st)
            def visit_Lambda(self, n): return
        v=V()
        if isinstance(node, ast.FunctionDef):
            for st in node.body: v.visit(st)
        else:
            v.visit(node)
        return v.found

    def _generator_yields(self, node: ast.FunctionDef | ast.AsyncFunctionDef) -> list[ast.Yield | ast.YieldFrom]:
        out: list[ast.Yield | ast.YieldFrom] = []
        class V(ast.NodeVisitor):
            def visit_Yield(self, n):
                out.append(n); self.generic_visit(n)
            def visit_YieldFrom(self, n):
                out.append(n); self.generic_visit(n)
            def visit_FunctionDef(self, n):
                if n is node:
                    for st in n.body: self.visit(st)
            def visit_AsyncFunctionDef(self, n):
                if n is node:
                    for st in n.body: self.visit(st)
            def visit_Lambda(self, n): return
        V().visit(node)
        return out

    def _compile_generator_function(self, node: ast.FunctionDef | ast.AsyncFunctionDef, info: FunctionInfo, *, as_coroutine: bool = False, as_async_generator: bool = False) -> None:
        # The callable body creates a persistent frame and returns immediately.
        b=CodeBuilder(self.cf.cp)
        scope=Scope(info.bound_args,start_slot=1,env_mode=True,local_names=info.local_names,
                    free_names=info.free_names,nonlocal_names=info.nonlocal_names)
        b.aload(0); b.invokestatic(RUNTIME,"envChild",f"({OBJ}){OBJ}")
        env_slot=scope.temp(); b.astore(env_slot); scope.env_slot=env_slot
        for name in sorted((info.local_names - set(info.bound_args)) | (info.local_names & self.deleted_names)):
            b.aload(env_slot); b.ldc_string(name); b.invokestatic(RUNTIME,"unbound",f"(){OBJ}")
            b.invokestatic(RUNTIME,"envSetLocal",f"({OBJ*3})V")
        for name in info.bound_args:
            b.aload(env_slot); b.ldc_string(name); b.aload(scope.get(name))
            b.invokestatic(RUNTIME,"envSetLocal",f"({OBJ}{OBJ}{OBJ})V")
        resume_name=info.java_name+"$resume"
        self._emit_owner(b); b.ldc_string(resume_name); b.aload(env_slot)
        b.ldc_string(info.name); b.ldc_string(self.filename); self._emit_int(node.lineno,b)
        maker = "makeAsyncGeneratorEx" if as_async_generator else ("makeSuspendableCoroutineEx" if as_coroutine else "makeGeneratorEx")
        b.invokestatic(RUNTIME,maker,f"({OBJ*6}){OBJ}"); b.areturn()
        code=b.finish()
        self.cf.add_method(Method(info.java_name,info.descriptor,code,max_locals=max(8,scope.next_slot+2),exception_table=b.exception_table))
        self._compile_generator_resume(node, info, resume_name, async_generator_mode=as_async_generator)

    def _gen_env_store(self, name: str, value_slot: int, b: CodeBuilder, scope: Scope) -> None:
        b.aload(scope.env_slot); b.ldc_string(name); b.aload(value_slot)
        b.invokestatic(RUNTIME,"envSetLocal",f"({OBJ}{OBJ}{OBJ})V")

    def _gen_env_load(self, name: str, b: CodeBuilder, scope: Scope) -> None:
        b.aload(scope.env_slot); b.ldc_string(name)
        b.invokestatic(RUNTIME,"envGet",f"({OBJ}{OBJ}){OBJ}")

    @_preserve_codegen_state
    def _compile_generator_resume(self, node: ast.FunctionDef | ast.AsyncFunctionDef, info: FunctionInfo, resume_name: str, *, async_generator_mode: bool = False) -> None:
        yields=self._generator_yields(node)
        state_for={id(y):i+1 for i,y in enumerate(yields)}
        labels={i:b_label for i,b_label in []}  # populated below
        b=CodeBuilder(self.cf.cp)
        gen_slot=0; env_slot=1
        scope=Scope(start_slot=2,env_mode=True,env_slot=env_slot,local_names=info.local_names,
                    free_names=info.free_names,nonlocal_names=info.nonlocal_names)
        b.aload(gen_slot); b.invokestatic(RUNTIME,"generatorEnv",f"({OBJ}){OBJ}"); b.astore(env_slot)
        labels={i:b.label() for i in range(len(yields)+1)}
        invalid=b.label()
        # Dispatch on the persisted program counter.
        for state,label in labels.items():
            b.aload(gen_slot); b.invokestatic(RUNTIME,"generatorState",f"({OBJ}){OBJ}")
            self._emit_int(state,b); b.invokestatic(RUNTIME,"eq",f"({OBJ}{OBJ}){OBJ}")
            b.invokestatic(RUNTIME,"truth",f"({OBJ})Z"); b.ifne(label)
        b.goto(invalid)
        b.mark(labels[0])
        self.loop_stack=[]; self.exception_stack=[]; self.handler_depths=[]; self.cleanup_stack=[]; self.finally_stack=[]
        gen_finally_stack: list[list[ast.stmt]] = []
        gen_cleanup_order: list[tuple[str, object]] = []
        gen_active_exception_env: list[str] = []
        gen_finally_counter = [0]
        gen_loop_floors: list[int] = []

        def emit_generator_cleanups():
            # Inline non-yielding generator-finally suites from inner to outer.
            # Temporarily remove the suite being emitted so an abrupt completion
            # originating in finally does not recursively execute that same suite.
            original = list(gen_finally_stack)
            while gen_finally_stack:
                finalbody = gen_finally_stack.pop()
                for cleanup_stmt in finalbody:
                    gen_stmt(cleanup_stmt)
            gen_finally_stack[:] = original

        def _store_env_literal(name: str, value: str):
            b.ldc_string(value); slot=scope.temp(); b.astore(slot)
            self._gen_env_store(name,slot,b,scope)

        def _route_return(ret_slot: int):
            if gen_cleanup_order:
                kind,payload=gen_cleanup_order.pop()
                try:
                    if kind == "inline":
                        for cleanup_stmt in payload: gen_stmt(cleanup_stmt)
                        _route_return(ret_slot)
                    elif kind == "with":
                        emit_with_exit(payload)
                        _route_return(ret_slot)
                    else:
                        ctx=payload
                        self._gen_env_store(ctx["value_env"],ret_slot,b,scope)
                        _store_env_literal(ctx["kind_env"],"return")
                        b.goto(ctx["final_entry"])
                finally:
                    gen_cleanup_order.append((kind,payload))
                return
            b.aload(gen_slot); b.aload(ret_slot)
            b.invokestatic(RUNTIME,"generatorFinish",f"({OBJ}{OBJ}){OBJ}"); b.areturn()

        def emit_with_exit(payload):
            # The exit call runs outside its own with block's exception range, so a
            # failing __exit__ is not offered to the same manager a second time.
            hole_start,hole_end=b.label(),b.label()
            payload["holes"].append((hole_start,hole_end))
            b.mark(hole_start)
            self._gen_env_load(payload["manager"],b,scope); b.invokestatic(RUNTIME,"withExitNormal",f"({OBJ})V")
            b.mark(hole_end)

        def _route_branch(target, floor):
            # Only cleanups entered after the loop began are unwound by break/continue.
            if len(gen_cleanup_order) > floor:
                kind,payload=gen_cleanup_order.pop()
                try:
                    if kind == "inline":
                        for cleanup_stmt in payload: gen_stmt(cleanup_stmt)
                        _route_branch(target,floor)
                    elif kind == "with":
                        emit_with_exit(payload)
                        _route_branch(target,floor)
                    else:
                        ctx=payload
                        branch_kind=ctx["branches"].get(target)
                        if branch_kind is None:
                            branch_kind=f"branch_{len(ctx['branches'])}"
                            ctx["branches"][target]=branch_kind
                            ctx["floors"][target]=floor
                        _store_env_literal(ctx["kind_env"],branch_kind)
                        b.goto(ctx["final_entry"])
                finally:
                    gen_cleanup_order.append((kind,payload))
                return
            b.goto(target)

        def emit_yield(y: ast.Yield):
            value_slot=scope.temp()
            if y.value is None: b.aconst_null()
            else: gen_value(y.value)
            if async_generator_mode:
                b.invokestatic(RUNTIME,"asyncGeneratorYield",f"({OBJ}){OBJ}")
            b.astore(value_slot)
            next_state=state_for[id(y)]
            b.aload(gen_slot); self._emit_int(next_state,b)
            b.invokestatic(RUNTIME,"generatorSetState",f"({OBJ}{OBJ})V")
            b.aload(value_slot); b.areturn()
            b.mark(labels[next_state])
            b.aload(gen_slot); b.invokestatic(RUNTIME,"generatorResumeValue",f"({OBJ}){OBJ}")

        def emit_yield_from(y: ast.YieldFrom):
            # Persist the delegated iterator in the generator environment.  Each
            # resume forwards the value sent into this generator to the delegate.
            syn=f"$yieldfrom_{state_for[id(y)]}"
            gen_value(y.value); b.invokestatic(RUNTIME,"iter",f"({OBJ}){OBJ}")
            it_slot=scope.temp(); b.astore(it_slot); self._gen_env_store(syn,it_slot,b,scope)
            next_state=state_for[id(y)]
            done=b.label()

            def step(sent_from_generator: bool):
                self._gen_env_load(syn,b,scope)
                if sent_from_generator:
                    b.aload(gen_slot)
                    b.invokestatic(RUNTIME,"yieldFromResumeStep",f"({OBJ}{OBJ}){OBJ}")
                else:
                    b.aconst_null()
                    b.invokestatic(RUNTIME,"yieldFromStep",f"({OBJ}{OBJ}){OBJ}")
                result_slot=scope.temp(); b.astore(result_slot)
                b.aload(result_slot); b.invokestatic(RUNTIME,"yieldFromDone",f"({OBJ})Z"); b.ifne(done)
                b.aload(gen_slot); self._emit_int(next_state,b)
                b.invokestatic(RUNTIME,"generatorSetState",f"({OBJ}{OBJ})V")
                b.aload(result_slot); b.invokestatic(RUNTIME,"yieldFromValue",f"({OBJ}){OBJ}"); b.areturn()

            step(False)
            b.mark(labels[next_state])
            step(True)
            # A resumed delegation that yields returns above. If it completes,
            # continue with the delegate's StopIteration.value.
            b.mark(done)
            self._gen_env_load(syn,b,scope); b.invokestatic(RUNTIME,"yieldFromReturnValue",f"({OBJ}){OBJ}")

        synthetic_counter=[0]

        def persist_gen_value(prefix: str) -> str:
            name=f"${prefix}_{synthetic_counter[0]}"; synthetic_counter[0]+=1
            slot=scope.temp(); b.astore(slot); self._gen_env_store(name,slot,b,scope)
            return name

        def gen_value(expr: ast.expr):
            if isinstance(expr, ast.Yield):
                emit_yield(expr); return
            if isinstance(expr, ast.YieldFrom):
                emit_yield_from(expr); return
            if not self._contains_yield(expr):
                self._expr(expr,b,scope); return
            if isinstance(expr, ast.BinOp):
                gen_value(expr.left); left_name=persist_gen_value("expr_left")
                gen_value(expr.right); right_slot=scope.temp(); b.astore(right_slot)
                self._gen_env_load(left_name,b,scope); b.aload(right_slot)
                self._binary_runtime(expr.op,b); return
            if isinstance(expr, (ast.List, ast.Tuple, ast.Set)):
                if isinstance(expr,ast.List): create,add,extend="list0","listAppend","listExtend"
                elif isinstance(expr,ast.Tuple): create,add,extend="tuple0","tupleAppend","tupleExtend"
                else: create,add,extend="set0","setAdd","setUpdate"
                b.invokestatic(RUNTIME,create,f"(){OBJ}")
                container=persist_gen_value("expr_container")
                for elt in expr.elts:
                    target=elt.value if isinstance(elt,ast.Starred) else elt
                    gen_value(target); value_slot=scope.temp(); b.astore(value_slot)
                    self._gen_env_load(container,b,scope); b.aload(value_slot)
                    b.invokestatic(RUNTIME,extend if isinstance(elt,ast.Starred) else add,f"({OBJ}{OBJ})V")
                self._gen_env_load(container,b,scope); return
            if isinstance(expr, ast.Dict):
                b.invokestatic(RUNTIME,"dict0",f"(){OBJ}")
                container=persist_gen_value("expr_dict")
                for key,value in zip(expr.keys,expr.values):
                    if key is None:
                        gen_value(value); value_slot=scope.temp(); b.astore(value_slot)
                        self._gen_env_load(container,b,scope); b.aload(value_slot)
                        b.invokestatic(RUNTIME,"dictUpdate",f"({OBJ}{OBJ})V")
                    else:
                        gen_value(key); key_name=persist_gen_value("expr_key")
                        gen_value(value); value_slot=scope.temp(); b.astore(value_slot)
                        self._gen_env_load(container,b,scope); self._gen_env_load(key_name,b,scope); b.aload(value_slot)
                        b.invokestatic(RUNTIME,"dictPut",f"({OBJ}{OBJ}{OBJ})V")
                self._gen_env_load(container,b,scope); return
            if (isinstance(expr, ast.Call) and isinstance(expr.func, ast.Name)
                    and expr.func.id == "__py_await_iter_internal" and len(expr.args) == 1):
                gen_value(expr.args[0])
                b.invokestatic(RUNTIME, "awaitIterator", f"({OBJ}){OBJ}"); return
            if isinstance(expr, ast.Call):
                method_object_name = None
                method_attr = None
                if isinstance(expr.func, ast.Attribute):
                    method_attr=expr.func.attr
                    if self._contains_yield(expr.func.value):
                        gen_value(expr.func.value)
                    else:
                        self._expr(expr.func.value,b,scope)
                    method_object_name=persist_gen_value("method_object")
                else:
                    if self._contains_yield(expr.func):
                        gen_value(expr.func)
                    else:
                        self._expr(expr.func,b,scope)
                    callable_name=persist_gen_value("callable")

                b.invokestatic(RUNTIME,"list0",f"(){OBJ}")
                args_name=persist_gen_value("call_args")
                for arg in expr.args:
                    target=arg.value if isinstance(arg,ast.Starred) else arg
                    gen_value(target); value_slot=scope.temp(); b.astore(value_slot)
                    self._gen_env_load(args_name,b,scope); b.aload(value_slot)
                    method="listExtend" if isinstance(arg,ast.Starred) else "listAppend"
                    b.invokestatic(RUNTIME,method,f"({OBJ}{OBJ})V")

                b.invokestatic(RUNTIME,"dict0",f"(){OBJ}")
                kwargs_name=persist_gen_value("call_kwargs")
                for kw in expr.keywords:
                    gen_value(kw.value); value_slot=scope.temp(); b.astore(value_slot)
                    self._gen_env_load(kwargs_name,b,scope)
                    if kw.arg is None:
                        b.aload(value_slot); b.invokestatic(RUNTIME,"dictMergeUnique",f"({OBJ}{OBJ})V")
                    else:
                        b.ldc_string(kw.arg); b.aload(value_slot)
                        b.invokestatic(RUNTIME,"dictPutUnique",f"({OBJ}{OBJ}{OBJ})V")

                if method_object_name is not None:
                    self._gen_env_load(method_object_name,b,scope)
                    b.ldc_string(method_attr)
                    self._gen_env_load(args_name,b,scope)
                    self._gen_env_load(kwargs_name,b,scope)
                    b.invokestatic(RUNTIME,"callMethodDynamic",f"({OBJ}{OBJ}{OBJ}{OBJ}){OBJ}")
                else:
                    self._gen_env_load(callable_name,b,scope)
                    self._gen_env_load(args_name,b,scope)
                    self._gen_env_load(kwargs_name,b,scope)
                    b.invokestatic(RUNTIME,"callFunction",f"({OBJ}{OBJ}{OBJ}){OBJ}")
                return
            if isinstance(expr, ast.IfExp):
                no,end=b.label(),b.label()
                gen_truth(expr.test); b.ifeq(no)
                gen_value(expr.body); b.goto(end); b.mark(no)
                gen_value(expr.orelse); b.mark(end); return
            if isinstance(expr, ast.BoolOp):
                end=b.label(); is_and=isinstance(expr.op,ast.And)
                for index,value in enumerate(expr.values):
                    gen_value(value)
                    if index+1 < len(expr.values):
                        b.dup(); b.invokestatic(RUNTIME,"truth",f"({OBJ})Z")
                        if is_and: b.ifeq(end)
                        else: b.ifne(end)
                        b.pop()
                b.mark(end); return
            node=hoist_apply(expr)
            if node is None:
                raise CompileError(f"suspension inside {type(expr).__name__} is not implemented yet")
            self._expr(node,b,scope)

        def hoist_apply(expr: ast.expr):
            hoisted=hoist_operands(expr)
            if hoisted is None: return None
            node,slots=hoisted
            last=max(index for index,(holder,key) in enumerate(slots) if self._contains_yield(slot_get(holder,key)))
            for holder,key in slots[:last+1]:
                gen_value(slot_get(holder,key))
                name=persist_gen_value("gen_hoist")
                slot_set(holder,key,ast.copy_location(ast.Name(id=name,ctx=ast.Load()),expr))
            return node

        def slot_get(holder, key):
            return holder[key] if isinstance(holder,list) else getattr(holder,key)

        def slot_set(holder, key, value):
            if isinstance(holder,list): holder[key]=value
            else: setattr(holder,key,value)

        def gen_truth(test: ast.expr):
            if self._contains_yield(test):
                gen_value(test); b.invokestatic(RUNTIME,"truth",f"({OBJ})Z")
            else:
                self._truthy(test,b,scope)

        def hoist_operands(expr: ast.expr):
            # Copy the node and list its operand slots in evaluation order.  Operands up
            # to the last suspending one are evaluated first and persisted, then the
            # operation itself runs through the ordinary expression compiler.
            node=shallow_copy_ast(expr); slots=[]
            if isinstance(node,ast.UnaryOp): slots=[(node,"operand")]
            elif isinstance(node,ast.Attribute): slots=[(node,"value")]
            elif isinstance(node,ast.NamedExpr): slots=[(node,"value")]
            elif isinstance(node,ast.Subscript):
                slots=[(node,"value")]
                if isinstance(node.slice,ast.Slice):
                    node.slice=shallow_copy_ast(node.slice)
                    slots.extend((node.slice,key) for key in ("lower","upper","step") if getattr(node.slice,key) is not None)
                else:
                    slots.append((node,"slice"))
            elif isinstance(node,ast.Compare) and len(node.ops)==1:
                node.comparators=list(node.comparators)
                slots=[(node,"left"),(node.comparators,0)]
            elif isinstance(node,ast.JoinedStr):
                node.values=list(node.values)
                for index,value in enumerate(node.values):
                    if isinstance(value,ast.FormattedValue):
                        value=shallow_copy_ast(value); node.values[index]=value
                        slots.append((value,"value"))
                        if value.format_spec is not None: slots.append((value,"format_spec"))
            elif isinstance(node,(ast.ListComp,ast.SetComp,ast.DictComp,ast.GeneratorExp)):
                node.generators=list(node.generators)
                node.generators[0]=shallow_copy_ast(node.generators[0])
                slots=[(node.generators[0],"iter")]
            else:
                return None
            return node,slots

        def gen_try_except(body: list[ast.stmt], handlers: list[ast.ExceptHandler], orelse: list[ast.stmt]):
            start, protected_end, dispatch, done = b.label(), b.label(), b.label(), b.label()
            exc_slot = scope.temp()
            b.mark(start)
            for x in body: gen_stmt(x)
            b.mark(protected_end)
            for x in orelse: gen_stmt(x)
            b.goto(done)
            b.mark(dispatch); b.astore(exc_slot)
            b.add_exception_handler(start, protected_end, dispatch, "java/lang/Throwable")
            self._trace_handler_entry(None, exc_slot, b)
            for handler in handlers:
                next_handler = b.label()
                self._trace_handler_line(handler, b)
                if handler.type is not None:
                    self._emit_except_match(handler.type, exc_slot, next_handler, b, scope)
                if handler.name:
                    b.aload(exc_slot); b.invokestatic(RUNTIME, "exceptionInstance", f"({OBJ}){OBJ}")
                    self._store_name(handler.name, b, scope)
                # Persist the active Throwable in the generator environment. JVM locals
                # do not survive a resume jump in a verifier-safe way, but Python's bare
                # ``raise`` must keep referring to the exception being handled even after
                # one or more yields.
                exc_env = f"$active_exc_{synthetic_counter[0]}"; synthetic_counter[0] += 1
                self._gen_env_store(exc_env, exc_slot, b, scope)
                depth_env = f"$active_depth_{synthetic_counter[0]}"; synthetic_counter[0] += 1
                depth_tmp = scope.temp()
                b.aload(exc_slot); b.invokestatic(RUNTIME2, "excEnter", f"({OBJ}){OBJ}"); b.astore(depth_tmp)
                self._gen_env_store(depth_env, depth_tmp, b, scope)
                self.exception_stack.append(exc_slot)
                gen_active_exception_env.append(exc_env)
                for x in self._handler_body(handler): gen_stmt(x)
                gen_active_exception_env.pop()
                self.exception_stack.pop()
                self._gen_env_load(depth_env, b, scope); b.invokestatic(RUNTIME2, "excLeave", f"({OBJ})V")
                b.goto(done)
                b.mark(next_handler)
            b.aload(exc_slot); b.athrow()
            b.mark(done)

        def gen_stmt(stmt: ast.stmt):
            if hasattr(stmt, "lineno"):
                self._emit_stmt_line(stmt.lineno, b); self._line_emitted = stmt
            if isinstance(stmt,ast.Assign) and any(self._contains_yield(t) for t in stmt.targets):
                # The value is evaluated first, then each target's own operands.
                gen_value(stmt.value); value_name=persist_gen_value("gen_hoist")
                new_targets=[]
                for target in stmt.targets:
                    node=hoist_apply(target) if self._contains_yield(target) else target
                    if node is None:
                        raise CompileError(f"suspension inside assignment target {type(target).__name__} is not implemented yet")
                    new_targets.append(node)
                rewritten=ast.Assign(targets=new_targets,value=ast.Name(id=value_name,ctx=ast.Load()))
                ast.copy_location(rewritten,stmt); ast.fix_missing_locations(rewritten)
                self._stmt(rewritten,b,scope,in_function=True); return
            if isinstance(stmt,ast.Expr) and isinstance(stmt.value,ast.Yield):
                emit_yield(stmt.value); b.pop(); return
            if isinstance(stmt,ast.Expr) and isinstance(stmt.value,ast.YieldFrom):
                emit_yield_from(stmt.value); b.pop(); return
            if isinstance(stmt,ast.Assign) and isinstance(stmt.value,ast.Yield):
                y=stmt.value
                emit_yield(y)
                if len(stmt.targets)==1:
                    self._store_target(stmt.targets[0],b,scope)
                else:
                    tmp=scope.temp(); b.astore(tmp)
                    for target in stmt.targets:
                        b.aload(tmp); self._store_target(target,b,scope)
                return
            if isinstance(stmt,ast.Assign) and isinstance(stmt.value,ast.YieldFrom):
                emit_yield_from(stmt.value)
                if len(stmt.targets)==1:
                    self._store_target(stmt.targets[0],b,scope)
                else:
                    tmp=scope.temp(); b.astore(tmp)
                    for target in stmt.targets:
                        b.aload(tmp); self._store_target(target,b,scope)
                return
            if isinstance(stmt,ast.AnnAssign) and isinstance(stmt.value,ast.Yield):
                emit_yield(stmt.value)
                self._store_target(stmt.target,b,scope); return
            if isinstance(stmt,ast.Assign) and stmt.value is not None and self._contains_yield(stmt.value):
                gen_value(stmt.value)
                if len(stmt.targets)==1:
                    self._store_target(stmt.targets[0],b,scope)
                else:
                    tmp=scope.temp(); b.astore(tmp)
                    for target in stmt.targets:
                        b.aload(tmp); self._store_target(target,b,scope)
                return
            if isinstance(stmt,ast.AnnAssign) and stmt.value is not None and self._contains_yield(stmt.value):
                gen_value(stmt.value); self._store_target(stmt.target,b,scope); return
            if isinstance(stmt,ast.Expr) and self._contains_yield(stmt.value):
                gen_value(stmt.value); b.pop(); return
            if isinstance(stmt, ast.Raise) and stmt.exc is None:
                if gen_active_exception_env:
                    self._gen_env_load(gen_active_exception_env[-1], b, scope)
                    b.invokestatic(RUNTIME, "rethrowThrowable", f"({OBJ})V")
                    return
                if self.exception_stack:
                    b.aload(self.exception_stack[-1]); b.athrow(); return
                b.invokestatic(RUNTIME2, "reraiseCurrent", "()V"); return
            if isinstance(stmt,ast.Return):
                if stmt.value is None: b.aconst_null()
                else: gen_value(stmt.value)
                ret=scope.temp(); b.astore(ret)
                _route_return(ret); return
            if isinstance(stmt,ast.Break):
                if not self.loop_stack: raise CompileError("break outside loop")
                target=self.loop_stack[-1][1]
                _route_branch(target,gen_loop_floors[-1]); return
            if isinstance(stmt,ast.Continue):
                if not self.loop_stack: raise CompileError("continue outside loop")
                target=self.loop_stack[-1][0]
                _route_branch(target,gen_loop_floors[-1]); return
            if isinstance(stmt,ast.For):
                syn=f"$gen_iter_{synthetic_counter[0]}"; synthetic_counter[0]+=1
                gen_value(stmt.iter); b.invokestatic(RUNTIME,"iter",f"({OBJ}){OBJ}")
                it_slot=scope.temp(); b.astore(it_slot); self._gen_env_store(syn,it_slot,b,scope)
                start,normal_end,break_end=b.label(),b.label(),b.label(); self._loop_head(stmt,start,b)
                self._gen_env_load(syn,b,scope); b.invokestatic(RUNTIME,"iterHasNext",f"({OBJ})Z"); b.ifeq(normal_end)
                self._gen_env_load(syn,b,scope); b.invokestatic(RUNTIME,"iterNext",f"({OBJ}){OBJ}"); self._store_target(stmt.target,b,scope)
                self.loop_stack.append((start,break_end)); gen_loop_floors.append(len(gen_cleanup_order))
                for x in stmt.body: gen_stmt(x)
                gen_loop_floors.pop(); self.loop_stack.pop(); b.goto(start); b.mark(normal_end)
                for x in stmt.orelse: gen_stmt(x)
                b.mark(break_end); return
            if isinstance(stmt,ast.If):
                els,end=b.label(),b.label(); gen_truth(stmt.test); b.ifeq(els)
                for x in stmt.body: gen_stmt(x)
                b.goto(end); b.mark(els)
                for x in stmt.orelse: gen_stmt(x)
                b.mark(end); return
            if isinstance(stmt,ast.While):
                start,normal_end,break_end=b.label(),b.label(),b.label(); self._loop_head(stmt,start,b); gen_truth(stmt.test); b.ifeq(normal_end)
                self.loop_stack.append((start,break_end)); gen_loop_floors.append(len(gen_cleanup_order))
                for x in stmt.body: gen_stmt(x)
                gen_loop_floors.pop(); self.loop_stack.pop(); b.goto(start); b.mark(normal_end)
                for x in stmt.orelse: gen_stmt(x)
                b.mark(break_end); return
            if isinstance(stmt, ast.With) and self._contains_yield(stmt):
                def gen_with(items: list[ast.withitem], body: list[ast.stmt]):
                    if not items:
                        for x in body: gen_stmt(x)
                        return
                    item, rest = items[0], items[1:]
                    mgr_name = f"$with_mgr_{synthetic_counter[0]}"; synthetic_counter[0] += 1
                    self._expr(item.context_expr, b, scope)
                    mgr_slot = scope.temp(); b.astore(mgr_slot)
                    self._gen_env_store(mgr_name, mgr_slot, b, scope)
                    b.aload(mgr_slot); b.invokestatic(RUNTIME, "withEnter", f"({OBJ}){OBJ}")
                    if item.optional_vars is None: b.pop()
                    else: self._store_target(item.optional_vars, b, scope)

                    start, protected_end, handler, done = b.label(), b.label(), b.label(), b.label()
                    exc_slot = scope.temp()
                    holes: list = []
                    b.mark(start)
                    gen_cleanup_order.append(("with", {"manager": mgr_name, "holes": holes}))
                    gen_with(rest, body)
                    gen_cleanup_order.pop()
                    b.mark(protected_end)
                    self._gen_env_load(mgr_name, b, scope); b.invokestatic(RUNTIME, "withExitNormal", f"({OBJ})V")
                    b.goto(done)
                    b.mark(handler); b.astore(exc_slot)
                    self._gen_env_load(mgr_name, b, scope); b.aload(exc_slot)
                    b.invokestatic(RUNTIME, "withExitException", f"({OBJ}{OBJ})Z")
                    b.ifne(done); b.aload(exc_slot); b.athrow()
                    cursor = start
                    for hole_start, hole_end in holes:
                        b.add_exception_handler(cursor, hole_start, handler, "java/lang/Throwable")
                        cursor = hole_end
                    b.add_exception_handler(cursor, protected_end, handler, "java/lang/Throwable")
                    b.mark(done)

                gen_with(stmt.items, stmt.body)
                return
            if isinstance(stmt,ast.Try) and self._contains_yield(stmt):
                # A generator resume jumps directly back into labels physically located
                # inside these protected ranges. Therefore exceptions injected by
                # generatorResumeValue() participate in normal JVM exception dispatch.
                if stmt.finalbody:
                    yielding_finally = any(self._contains_yield(x) for x in stmt.finalbody)
                    if yielding_finally:
                        idx = gen_finally_counter[0]; gen_finally_counter[0] += 1
                        kind_env=f"$finally_kind_{idx}"
                        value_env=f"$finally_value_{idx}"
                        outer_start, outer_end = b.label(), b.label()
                        outer_handler, final_entry, outer_done = b.label(), b.label(), b.label()
                        ctx={
                            "kind_env":kind_env,
                            "value_env":value_env,
                            "final_entry":final_entry,
                            "branches":{},
                            "floors":{},
                        }
                        outer_exc=scope.temp()

                        b.mark(outer_start)
                        gen_cleanup_order.append(("yielding",ctx))
                        if stmt.handlers:
                            gen_try_except(stmt.body, stmt.handlers, stmt.orelse)
                        else:
                            for x in stmt.body: gen_stmt(x)
                        gen_cleanup_order.pop()
                        b.mark(outer_end)
                        _store_env_literal(kind_env,"normal")
                        b.goto(final_entry)

                        b.mark(outer_handler); b.astore(outer_exc)
                        self._gen_env_store(value_env,outer_exc,b,scope)
                        _store_env_literal(kind_env,"exception")
                        b.goto(final_entry)
                        b.add_exception_handler(outer_start,outer_end,outer_handler,"java/lang/Throwable")

                        # Compile the suspending cleanup exactly once. The current
                        # cleanup context is already popped so an abrupt completion
                        # originating inside finally overrides the pending transfer.
                        b.mark(final_entry)
                        for x in stmt.finalbody: gen_stmt(x)

                        dispatch_return=b.label()
                        dispatch_exception=b.label()
                        dispatch_normal=b.label()
                        branch_labels={target:b.label() for target in ctx["branches"]}

                        self._gen_env_load(kind_env,b,scope); b.ldc_string("return")
                        b.invokestatic(RUNTIME,"eq",f"({OBJ}{OBJ}){OBJ}")
                        b.invokestatic(RUNTIME,"truth",f"({OBJ})Z"); b.ifne(dispatch_return)

                        self._gen_env_load(kind_env,b,scope); b.ldc_string("exception")
                        b.invokestatic(RUNTIME,"eq",f"({OBJ}{OBJ}){OBJ}")
                        b.invokestatic(RUNTIME,"truth",f"({OBJ})Z"); b.ifne(dispatch_exception)

                        for target,branch_kind in ctx["branches"].items():
                            self._gen_env_load(kind_env,b,scope); b.ldc_string(branch_kind)
                            b.invokestatic(RUNTIME,"eq",f"({OBJ}{OBJ}){OBJ}")
                            b.invokestatic(RUNTIME,"truth",f"({OBJ})Z"); b.ifne(branch_labels[target])

                        b.goto(dispatch_normal)

                        b.mark(dispatch_return)
                        self._gen_env_load(value_env,b,scope)
                        pending_ret=scope.temp(); b.astore(pending_ret)
                        _route_return(pending_ret)

                        b.mark(dispatch_exception)
                        self._gen_env_load(value_env,b,scope)
                        b.invokestatic(RUNTIME,"rethrowThrowable",f"({OBJ})V")

                        for target,label in branch_labels.items():
                            b.mark(label); _route_branch(target,ctx["floors"][target])

                        b.mark(dispatch_normal); b.goto(outer_done)
                        b.mark(outer_done)
                    else:
                        # Compile try/except as the protected body of an outer finally.
                        outer_start, outer_end, outer_handler, outer_done = b.label(), b.label(), b.label(), b.label()
                        outer_exc = scope.temp()
                        b.mark(outer_start)
                        gen_finally_stack.append(stmt.finalbody)
                        gen_cleanup_order.append(("inline",stmt.finalbody))
                        if stmt.handlers:
                            gen_try_except(stmt.body, stmt.handlers, stmt.orelse)
                        else:
                            for x in stmt.body: gen_stmt(x)
                        gen_cleanup_order.pop()
                        gen_finally_stack.pop()
                        b.mark(outer_end)
                        for x in stmt.finalbody: gen_stmt(x)
                        b.goto(outer_done)
                        b.mark(outer_handler); b.astore(outer_exc)
                        # The exception path executes this suite now; do not leave this
                        # same cleanup active while compiling it.
                        for x in stmt.finalbody: gen_stmt(x)
                        b.aload(outer_exc); b.athrow()
                        b.add_exception_handler(outer_start, outer_end, outer_handler, "java/lang/Throwable")
                        b.mark(outer_done)
                else:
                    gen_try_except(stmt.body, stmt.handlers, stmt.orelse)
                return
            if self._contains_yield(stmt):
                raise CompileError(f"yield inside {type(stmt).__name__} is not implemented yet")
            self._stmt(stmt,b,scope,in_function=True)

        for st in node.body: gen_stmt(st)
        b.aconst_null(); ret=scope.temp(); b.astore(ret); b.aload(gen_slot); b.aload(ret)
        b.invokestatic(RUNTIME,"generatorFinish",f"({OBJ}{OBJ}){OBJ}"); b.areturn()
        b.mark(invalid); b.aload(gen_slot); b.aconst_null(); b.invokestatic(RUNTIME,"generatorFinish",f"({OBJ}{OBJ}){OBJ}"); b.areturn()
        code=b.finish()
        self.cf.add_method(Method(resume_name,f"({OBJ}){OBJ}",code,max_locals=max(12,scope.next_slot+3),exception_table=b.exception_table))

    def _store_target(self, target: ast.expr, b: CodeBuilder, scope: Scope) -> None:
        if isinstance(target, ast.Name):
            self._store_name(target.id, b, scope); return
        if isinstance(target, (ast.Tuple, ast.List)):
            tmp = scope.temp(); b.astore(tmp)
            starred = [i for i, elt in enumerate(target.elts) if isinstance(elt, ast.Starred)]
            if len(starred) > 1:
                raise CompileError("multiple starred expressions in assignment")
            b.aload(tmp)
            if starred:
                index = starred[0]
                self._emit_int(index, b); self._emit_int(len(target.elts) - index - 1, b)
                b.invokestatic(RUNTIME, "unpackEx", f"({OBJ}{OBJ}{OBJ}){OBJ}")
            else:
                self._emit_int(len(target.elts), b)
                b.invokestatic(RUNTIME, "unpack", f"({OBJ}{OBJ}){OBJ}")
            unpacked = scope.temp(); b.astore(unpacked)
            for i, elt in enumerate(target.elts):
                b.aload(unpacked); self._emit_int(i, b)
                b.invokestatic(RUNTIME, "getitem", f"({OBJ}{OBJ}){OBJ}")
                self._store_target(elt.value if isinstance(elt, ast.Starred) else elt, b, scope)
            return
        if isinstance(target, ast.Attribute):
            tmp = scope.temp(); b.astore(tmp)
            self._expr(target.value, b, scope); b.ldc_string(self._class_attr_name(target.attr)); b.aload(tmp)
            b.invokestatic(RUNTIME, "setattr", f"({OBJ}{OBJ}{OBJ})V")
            return
        if isinstance(target, ast.Subscript):
            tmp = scope.temp(); b.astore(tmp)
            self._expr(target.value, b, scope); self._expr(target.slice, b, scope); b.aload(tmp)
            b.invokestatic(RUNTIME, "setitem", f"({OBJ}{OBJ}{OBJ})V")
            return
        raise CompileError(f"Unsupported assignment target: {type(target).__name__}")

    def _emit_int(self, value: int, b: CodeBuilder) -> None:
        b.ldc_string(str(value)); b.invokestatic(RUNTIME, "pyInt", "(Ljava/lang/String;)Ljava/lang/Object;")

    @staticmethod
    def _is_annotation_marker(node: ast.expr) -> bool:
        return isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id in ANNOTATION_MARKERS

    def _emit_function_definition(self, node: ast.FunctionDef | ast.AsyncFunctionDef, b: CodeBuilder, scope: Scope) -> None:
        info = self.function_infos[id(node)]
        # Python evaluates decorator expressions in source order in the enclosing
        # scope, then applies them from the bottom upward to the function object.
        decorator_slots: list[int] = []
        decorators = list(node.decorator_list)
        marker = decorators.pop() if decorators and self._is_annotation_marker(decorators[-1]) else None
        for decorator in decorators:
            self._expr(decorator, b, scope)
            slot = scope.temp(); b.astore(slot); decorator_slots.append(slot)

        # Defaults are evaluated exactly once at execution of this def statement and retained by the function object.
        b.invokestatic(RUNTIME, "dict0", f"(){OBJ}")
        defaults_slot = scope.temp(); b.astore(defaults_slot)
        for param, expr in [*info.defaults.items(), *info.kw_defaults.items()]:
            b.aload(defaults_slot); b.ldc_string(param); self._expr(expr, b, scope)
            b.invokestatic(RUNTIME, "dictPut", f"({OBJ}{OBJ}{OBJ})V")

        self._emit_owner(b)
        b.ldc_string(info.java_name)
        b.ldc_string(",".join(info.posonly)); b.ldc_string(",".join(info.poskw)); b.ldc_string(",".join(info.kwonly))
        if info.vararg is None: b.aconst_null()
        else: b.ldc_string(info.vararg)
        if info.kwarg is None: b.aconst_null()
        else: b.ldc_string(info.kwarg)
        b.aload(defaults_slot)
        if info.env_mode and scope.env_mode and scope.env_slot is not None: b.aload(scope.env_slot)
        else: b.aconst_null()
        self._boxed_bool(info.env_mode, b)
        b.invokestatic(RUNTIME, "makeFunctionEx", f"({OBJ * 10}){OBJ}")
        b.dup(); b.ldc_string(info.name); b.ldc_string(self.filename); self._emit_int(node.lineno,b)
        b.invokestatic(RUNTIME,"setFunctionMeta",f"({OBJ*4})V")

        b.dup(); self._emit_module_name(b); b.ldc_string(info.qualname or info.name)
        if node.body and isinstance(node.body[0], ast.Expr) and isinstance(node.body[0].value, ast.Constant) and isinstance(node.body[0].value.value, str):
            b.ldc_string(node.body[0].value.value)
        else: b.aconst_null()
        b.invokestatic(RUNTIME, "setFunctionAttributes", f"({OBJ * 4})V")
        b.dup(); self._boxed_bool("__class__" in info.free_names, b)
        b.invokestatic(RUNTIME, "setFunctionClassCell", f"({OBJ * 2})V")
        b.dup(); b.ldc_string(",".join(sorted(info.free_names)))
        b.invokestatic(RUNTIME, "setFunctionFreeNames", f"({OBJ * 2})V")
        if marker is not None:
            # annotation lowering (annot.py): the last "decorator" attaches the annotations / __annotate__ to the new function
            marked_slot = scope.temp(); b.astore(marked_slot)
            self._expr(marker, b, scope)
            b.invokestatic(RUNTIME, "list0", f"(){OBJ}")
            b.dup(); b.aload(marked_slot); b.invokestatic(RUNTIME, "listAppend", f"({OBJ}{OBJ})V")
            b.invokestatic(RUNTIME, "dict0", f"(){OBJ}")
            b.invokestatic(RUNTIME, "callFunction", f"({OBJ}{OBJ}{OBJ}){OBJ}")
            b.pop(); b.aload(marked_slot)
        if self.trace and (info.is_generator or info.is_async or info.is_async_generator):
            b.dup(); b.invokestatic(RUNTIME, "setFunctionGenerator", f"({OBJ})V")
        if info.is_async_generator:
            b.dup(); b.invokestatic(RUNTIME, "setFunctionAsyncGenerator", f"({OBJ})V")
        elif info.is_async:
            b.dup(); b.invokestatic(RUNTIME, "setFunctionAsync", f"({OBJ})V")
        if scope.namespace_slot is not None and not decorators and node.name in {"__new__", "__init_subclass__", "__class_getitem__"}:
            b.ldc_string("static" if node.name == "__new__" else "class")
            b.invokestatic(RUNTIME, "methodDescriptor", f"({OBJ}{OBJ}){OBJ}")

        if decorator_slots:
            fn_slot = scope.temp(); b.astore(fn_slot)
            for decorator_slot in reversed(decorator_slots):
                b.aload(decorator_slot)
                b.invokestatic(RUNTIME, "list0", f"(){OBJ}")
                b.dup(); b.aload(fn_slot); b.invokestatic(RUNTIME, "listAppend", f"({OBJ}{OBJ})V")
                b.invokestatic(RUNTIME, "dict0", f"(){OBJ}")
                b.invokestatic(RUNTIME, "callFunction", f"({OBJ}{OBJ}{OBJ}){OBJ}")
                b.astore(fn_slot)
            b.aload(fn_slot)

        if info.top_level and not self.dynamic:
            assert info.function_field is not None
            b.dup(); b.putstatic(self.class_name, info.function_field, OBJ)
            if scope.module: b.pop()
            else: self._store_name(info.name, b, scope)
        else:
            self._store_name(info.name, b, scope)

    def _lambda_info(self, node: ast.Lambda, scope: Scope) -> FunctionInfo:
        existing = self.function_infos.get(id(node))
        if existing is not None: return existing
        args_obj = node.args
        posonly=[a.arg for a in args_obj.posonlyargs]; poskw=[a.arg for a in args_obj.args]; kwonly=[a.arg for a in args_obj.kwonlyargs]
        positional=posonly+poskw
        defaults={name: expr for name, expr in zip(positional[-len(args_obj.defaults):], args_obj.defaults)} if args_obj.defaults else {}
        kw_defaults={a.arg:e for a,e in zip(args_obj.kwonlyargs,args_obj.kw_defaults) if e is not None}
        local_names=set(positional+kwonly)
        if args_obj.vararg: local_names.add(args_obj.vararg.arg)
        if args_obj.kwarg: local_names.add(args_obj.kwarg.arg)
        refs={n.id for n in ast.walk(node.body) if isinstance(n,ast.Name) and isinstance(n.ctx,ast.Load)}
        if self._needs_class_cell(node): refs.add("__class__")
        free_names={name for name in refs if name not in local_names and scope.has(name) and name not in scope.global_decl}
        if "__class__" in refs and "__class__" not in local_names and scope.namespace_slot is not None:
            free_names.add("__class__")
        java_name=f"__py_lambda_{self._method_counter}"; self._method_counter += 1
        is_generator=self._contains_yield(node.body)
        info=FunctionInfo("<lambda>", java_name, posonly,poskw,kwonly,
            args_obj.vararg.arg if args_obj.vararg else None,
            args_obj.kwarg.arg if args_obj.kwarg else None,
            defaults,kw_defaults,None,False,bool(scope.env_mode) or is_generator,local_names,free_names,set(),is_generator,False)
        self.function_infos[id(node)]=info
        if is_generator:
            # A lambda whose body yields is a generator function returning its body value.
            synthetic=ast.FunctionDef(name="<lambda>",args=node.args,body=[ast.Return(value=node.body)],
                                      decorator_list=[],returns=None,type_params=[])
            ast.copy_location(synthetic,node); ast.fix_missing_locations(synthetic)
            saved_frame=(self.current_frame_name,self.current_frame_firstlineno)
            self.current_frame_name,self.current_frame_firstlineno="<lambda>",node.lineno
            self._compile_generator_function(synthetic, info)
            self.current_frame_name,self.current_frame_firstlineno=saved_frame
        else:
            self._compile_lambda_method(node, info)
        return info

    @_preserve_codegen_state
    def _compile_lambda_method(self, node: ast.Lambda, info: FunctionInfo) -> None:
        saved_frame=(self.current_frame_name,self.current_frame_firstlineno)
        self.current_frame_name,self.current_frame_firstlineno="<lambda>",node.lineno
        b=CodeBuilder(self.cf.cp); arg_start=1 if info.env_mode else 0
        scope=Scope(info.bound_args,start_slot=arg_start,env_mode=info.env_mode,
                    local_names=info.local_names,free_names=info.free_names)
        if info.env_mode:
            b.aload(0); b.invokestatic(RUNTIME,"envChild",f"({OBJ}){OBJ}")
            env_slot=scope.temp(); b.astore(env_slot); scope.env_slot=env_slot
            b.aload(env_slot); b.invokestatic(RUNTIME, "frameSetArgumentEnv", f"({OBJ})V")
            for name in info.bound_args:
                b.aload(env_slot); b.ldc_string(name); b.aload(scope.get(name)); b.invokestatic(RUNTIME,"envSetLocal",f"({OBJ}{OBJ}{OBJ})V")
        saved_loop,saved_exc,saved_finally=self.loop_stack,self.exception_stack,self.finally_stack
        self.loop_stack=[]; self.exception_stack=[]; self.cleanup_stack=[]; self.finally_stack=[]
        self._expr(node.body,b,scope); b.areturn()
        self.loop_stack,self.exception_stack,self.finally_stack=saved_loop,saved_exc,saved_finally
        code=b.finish(); self.cf.add_method(Method(info.java_name,info.descriptor,code,max_locals=max(8,scope.next_slot+2),exception_table=b.exception_table))
        self.current_frame_name,self.current_frame_firstlineno=saved_frame

    def _emit_lambda(self, node: ast.Lambda, b: CodeBuilder, scope: Scope) -> None:
        info=self._lambda_info(node,scope)
        b.invokestatic(RUNTIME,"dict0",f"(){OBJ}"); defaults_slot=scope.temp(); b.astore(defaults_slot)
        for param,expr in [*info.defaults.items(),*info.kw_defaults.items()]:
            b.aload(defaults_slot); b.ldc_string(param); self._expr(expr,b,scope); b.invokestatic(RUNTIME,"dictPut",f"({OBJ}{OBJ}{OBJ})V")
        self._emit_owner(b); b.ldc_string(info.java_name)
        b.ldc_string(",".join(info.posonly)); b.ldc_string(",".join(info.poskw)); b.ldc_string(",".join(info.kwonly))
        if info.vararg is None: b.aconst_null()
        else: b.ldc_string(info.vararg)
        if info.kwarg is None: b.aconst_null()
        else: b.ldc_string(info.kwarg)
        b.aload(defaults_slot)
        if info.env_mode and scope.env_mode and scope.env_slot is not None: b.aload(scope.env_slot)
        else: b.aconst_null()
        self._boxed_bool(info.env_mode,b)
        b.invokestatic(RUNTIME,"makeFunctionEx",f"({OBJ*10}){OBJ}")
        b.dup(); b.ldc_string("<lambda>"); b.ldc_string(self.filename); self._emit_int(node.lineno,b)
        b.invokestatic(RUNTIME,"setFunctionMeta",f"({OBJ*4})V")
        b.dup(); self._emit_module_name(b); b.ldc_string("<lambda>"); b.aconst_null()
        b.invokestatic(RUNTIME,"setFunctionAttributes",f"({OBJ*4})V")
        b.dup(); self._boxed_bool("__class__" in info.free_names, b)
        b.invokestatic(RUNTIME, "setFunctionClassCell", f"({OBJ * 2})V")
        b.dup(); b.ldc_string(",".join(sorted(info.free_names)))
        b.invokestatic(RUNTIME, "setFunctionFreeNames", f"({OBJ * 2})V")

    def _emit_call_parts(self, args: list[ast.expr], keywords: list[ast.keyword], b: CodeBuilder, scope: Scope) -> None:
        b.invokestatic(RUNTIME, "list0", f"(){OBJ}")
        for arg in args:
            if isinstance(arg, ast.Starred):
                b.dup(); self._expr(arg.value, b, scope); b.invokestatic(RUNTIME, "listExtend", f"({OBJ}{OBJ})V")
            else:
                b.dup(); self._expr(arg, b, scope); b.invokestatic(RUNTIME, "listAppend", f"({OBJ}{OBJ})V")
        b.invokestatic(RUNTIME, "dict0", f"(){OBJ}")
        for kw in keywords:
            if kw.arg is None:
                b.dup(); self._expr(kw.value, b, scope); b.invokestatic(RUNTIME, "dictMergeUnique", f"({OBJ}{OBJ})V")
            else:
                b.dup(); b.ldc_string(kw.arg); self._expr(kw.value, b, scope); b.invokestatic(RUNTIME, "dictPutUnique", f"({OBJ}{OBJ}{OBJ})V")

    def _emit_dynamic_call(self, func: ast.expr, args: list[ast.expr], keywords: list[ast.keyword], b: CodeBuilder, scope: Scope) -> None:
        self._expr(func, b, scope)
        self._emit_call_parts(args, keywords, b, scope)
        b.invokestatic(RUNTIME, "callFunction", f"({OBJ}{OBJ}{OBJ}){OBJ}")

    def _emit_generator_expression(self, node: ast.GeneratorExp, b: CodeBuilder, scope: Scope) -> None:
        is_async = self._comp_is_async(node)
        # Python evaluates the outermost iterable expression immediately when the
        # generator expression is created. The actual iteration remains lazy.
        outer_name=f"$genexpr_outer_{self._method_counter}"
        java_name=f"__py_genexpr_{self._method_counter}"; self._method_counter += 1
        target_names: set[str] = {outer_name}
        for gen in node.generators: target_names.update(self._target_names(gen.target))
        refs={n.id for n in ast.walk(node) if isinstance(n,ast.Name) and isinstance(n.ctx,ast.Load)}
        free_names={name for name in refs if name not in target_names and scope.has(name) and name not in scope.global_decl}

        body: list[ast.stmt]=[ast.Expr(value=ast.Yield(value=node.elt))]
        generators=list(node.generators)
        for index in range(len(generators)-1,-1,-1):
            gen=generators[index]
            inner=body
            for cond in reversed(gen.ifs): inner=[ast.If(test=cond,body=inner,orelse=[])]
            iterable=ast.Name(id=outer_name,ctx=ast.Load()) if index==0 else gen.iter
            if gen.is_async:
                body=[ast.AsyncFor(target=gen.target,iter=iterable,body=inner,orelse=[])]
            else:
                body=[ast.For(target=gen.target,iter=iterable,body=inner,orelse=[])]

        if is_async:
            synthetic_async=ast.AsyncFunctionDef(
                name="<asyncgenexpr>",
                args=ast.arguments(posonlyargs=[],args=[],kwonlyargs=[],kw_defaults=[],defaults=[]),
                body=body,decorator_list=[],
            )
            synthetic_async=ast.fix_missing_locations(synthetic_async)
            lowered=self._lower_async_function(synthetic_async)
            local_names,_,_=self._function_locals(lowered)
            local_names.update(target_names)
            info=FunctionInfo("<asyncgenexpr>",java_name,[],[],[],None,None,{}, {},None,False,True,
                              local_names,free_names,set(),False,True,True)
            self.function_infos[id(lowered)]=info
            resume_name=java_name+"$resume"
            self._compile_generator_resume(lowered,info,resume_name,async_generator_mode=True)
        else:
            info=FunctionInfo("<genexpr>",java_name,[],[],[],None,None,{}, {},None,False,True,
                              target_names,free_names,set(),True,False,False)
            synthetic=ast.FunctionDef(
                name="<genexpr>",
                args=ast.arguments(posonlyargs=[],args=[],kwonlyargs=[],kw_defaults=[],defaults=[]),
                body=body,decorator_list=[],
            )
            synthetic=ast.fix_missing_locations(synthetic)
            self.function_infos[id(synthetic)]=info
            resume_name=java_name+"$resume"
            self._compile_generator_resume(synthetic,info,resume_name)

        if scope.env_mode and scope.env_slot is not None: b.aload(scope.env_slot)
        else: b.aconst_null()
        b.invokestatic(RUNTIME,"envChild",f"({OBJ}){OBJ}")
        env_slot=scope.temp(); b.astore(env_slot)
        self._expr(node.generators[0].iter,b,scope)
        value_slot=scope.temp(); b.astore(value_slot)
        b.aload(env_slot); b.ldc_string(outer_name); b.aload(value_slot)
        b.invokestatic(RUNTIME,"envSetLocal",f"({OBJ}{OBJ}{OBJ})V")
        self._emit_owner(b); b.ldc_string(resume_name); b.aload(env_slot)
        if is_async:
            b.ldc_string("<asyncgenexpr>"); b.ldc_string(self.filename); self._emit_int(getattr(node,"lineno",1),b)
            b.invokestatic(RUNTIME,"makeAsyncGeneratorEx",f"({OBJ*6}){OBJ}")
        else:
            b.invokestatic(RUNTIME,"makeGenerator",f"({OBJ}{OBJ}{OBJ}){OBJ}")
        b.dup(); b.ldc_string(outer_name)
        b.invokestatic(RUNTIME, "setGeneratorFirstArgument", f"({OBJ * 2})V")

    def _emit_async_comprehension_coroutine(self, token: int, b: CodeBuilder, scope: Scope) -> None:
        node=self._async_comp_nodes[token]
        java_name=self._async_comp_compiled.get(token)
        if java_name is None:
            java_name=f"__py_asynccomp_{self._method_counter}"; self._method_counter += 1
            self._async_comp_compiled[token]=java_name
            out_name=f"$asynccomp_out_{token}"
            targets={out_name}
            for gen in node.generators: targets.update(self._target_names(gen.target))
            refs={n.id for n in ast.walk(node) if isinstance(n,ast.Name) and isinstance(n.ctx,ast.Load)}
            free_names={name for name in refs if name not in targets and scope.has(name) and name not in scope.global_decl}

            if isinstance(node, ast.ListComp):
                init_expr=ast.List(elts=[],ctx=ast.Load())
                action: ast.stmt=ast.Expr(value=ast.Call(
                    func=ast.Attribute(value=ast.Name(id=out_name,ctx=ast.Load()),attr="append",ctx=ast.Load()),
                    args=[node.elt],keywords=[],
                ))
            elif isinstance(node, ast.SetComp):
                init_expr=ast.Call(func=ast.Name(id="set",ctx=ast.Load()),args=[],keywords=[])
                action=ast.Expr(value=ast.Call(
                    func=ast.Attribute(value=ast.Name(id=out_name,ctx=ast.Load()),attr="add",ctx=ast.Load()),
                    args=[node.elt],keywords=[],
                ))
            else:
                init_expr=ast.Dict(keys=[],values=[])
                action=ast.Assign(
                    targets=[ast.Subscript(value=ast.Name(id=out_name,ctx=ast.Load()),slice=node.key,ctx=ast.Store())],
                    value=node.value,
                )

            body: list[ast.stmt]=[action]
            for gen in reversed(node.generators):
                inner=body
                for cond in reversed(gen.ifs):
                    inner=[ast.If(test=cond,body=inner,orelse=[])]
                loop_cls=ast.AsyncFor if gen.is_async else ast.For
                body=[loop_cls(target=gen.target,iter=gen.iter,body=inner,orelse=[])]
            synthetic=ast.AsyncFunctionDef(
                name="<asynccomp>",
                args=ast.arguments(posonlyargs=[],args=[],kwonlyargs=[],kw_defaults=[],defaults=[]),
                body=[
                    ast.Assign(targets=[ast.Name(id=out_name,ctx=ast.Store())],value=init_expr),
                    *body,
                    ast.Return(value=ast.Name(id=out_name,ctx=ast.Load())),
                ],
                decorator_list=[],
            )
            synthetic=ast.fix_missing_locations(synthetic)
            lowered=self._lower_async_function(synthetic)
            local_names,_,_=self._function_locals(lowered)
            local_names.update(targets)
            info=FunctionInfo(
                "<asynccomp>",java_name,[],[],[],None,None,{}, {},None,False,True,
                local_names,free_names,set(),False,True,False,
            )
            self.function_infos[id(lowered)]=info
            resume_name=java_name+"$resume"
            self._compile_generator_resume(lowered,info,resume_name)
        else:
            resume_name=java_name+"$resume"

        if scope.env_mode and scope.env_slot is not None: b.aload(scope.env_slot)
        else: b.aconst_null()
        b.invokestatic(RUNTIME,"envChild",f"({OBJ}){OBJ}")
        env_slot=scope.temp(); b.astore(env_slot)
        self._emit_owner(b); b.ldc_string(resume_name); b.aload(env_slot)
        b.ldc_string("<asynccomp>"); b.ldc_string(self.filename); self._emit_int(getattr(node,"lineno",1),b)
        b.invokestatic(RUNTIME,"makeSuspendableCoroutineEx",f"({OBJ*6}){OBJ}")

    def _emit_comprehension(self, node: ast.ListComp | ast.SetComp | ast.DictComp, b: CodeBuilder, scope: Scope) -> None:
        if any(gen.is_async for gen in node.generators):
            raise CompileError("async comprehensions are not implemented yet")
        if scope.env_mode and any(isinstance(inner, (ast.Lambda, ast.GeneratorExp)) for inner in ast.walk(node) if inner is not node):
            # Inline comprehensions keep their loop variables in JVM locals, which nested closures (lambdas,
            # generator expressions) cannot capture. When the comprehension contains such a closure, build the
            # result from a generator expression instead: it is a real scope, so captures resolve through its
            # environment. (Other comprehensions stay inline, as in CPython, so e.g. zero-argument super() works.)
            elt = ast.Tuple(elts=[node.key, node.value], ctx=ast.Load()) if isinstance(node, ast.DictComp) else node.elt
            generator = ast.copy_location(ast.GeneratorExp(elt=elt, generators=node.generators), node)
            self._expr(generator, b, scope)
            convert = {ast.ListComp: "listFrom", ast.SetComp: "setFrom", ast.DictComp: "dictFrom"}[type(node)]
            b.invokestatic(RUNTIME, convert, f"({OBJ}){OBJ}")
            return
        if isinstance(node, ast.ListComp):
            b.invokestatic(RUNTIME, "list0", f"(){OBJ}")
            kind = "list"
        elif isinstance(node, ast.SetComp):
            b.invokestatic(RUNTIME, "set0", f"(){OBJ}")
            kind = "set"
        else:
            b.invokestatic(RUNTIME, "dict0", f"(){OBJ}")
            kind = "dict"
        out_slot = scope.temp(); b.astore(out_slot)
        self._expr(node.generators[0].iter, b, scope)
        b.invokestatic(RUNTIME, "iter", f"({OBJ}){OBJ}")
        outer_iterator_slot = scope.temp(); b.astore(outer_iterator_slot)
        child = Scope(start_slot=scope.next_slot, parent=scope)
        if scope.namespace_slot is not None:
            child.parent = scope.parent
            while child.parent is not None and child.parent.namespace_slot is not None:
                child.parent = child.parent.parent
        child.comprehension_outer = scope

        def emit_generator(index: int) -> None:
            gen = node.generators[index]
            if index == 0:
                iterator_slot = outer_iterator_slot
            else:
                iterator_slot = child.temp()
                self._expr(gen.iter, b, child); b.invokestatic(RUNTIME, "iter", f"({OBJ}){OBJ}"); b.astore(iterator_slot)
            start, end = b.label(), b.label()
            b.mark(start); b.aload(iterator_slot); b.invokestatic(RUNTIME, "iterHasNext", f"({OBJ})Z"); b.ifeq(end)
            b.aload(iterator_slot); b.invokestatic(RUNTIME, "iterNext", f"({OBJ}){OBJ}"); self._store_target(gen.target, b, child)
            for condition in gen.ifs:
                self._truthy(condition, b, child); b.ifeq(start)
            if index + 1 < len(node.generators):
                emit_generator(index + 1)
            else:
                b.aload(out_slot)
                if kind == "dict":
                    assert isinstance(node, ast.DictComp)
                    self._expr(node.key, b, child); self._expr(node.value, b, child)
                    b.invokestatic(RUNTIME, "dictPut", f"({OBJ}{OBJ}{OBJ})V")
                else:
                    elt = node.elt  # type: ignore[attr-defined]
                    self._expr(elt, b, child)
                    method = "listAppend" if kind == "list" else "setAdd"
                    b.invokestatic(RUNTIME, method, f"({OBJ}{OBJ})V")
            b.goto(start); b.mark(end)

        emit_generator(0)
        scope.next_slot = max(scope.next_slot, child.next_slot)
        b.aload(out_slot)

    @staticmethod
    def _has_abrupt_control(stmts: list[ast.stmt]) -> bool:
        class V(ast.NodeVisitor):
            found = False
            def visit_Return(self, node): self.found = True
            def visit_Break(self, node): self.found = True
            def visit_Continue(self, node): self.found = True
            def visit_FunctionDef(self, node): return
            def visit_AsyncFunctionDef(self, node): return
            def visit_ClassDef(self, node): return
            def visit_Lambda(self, node): return
        v=V()
        for stmt in stmts:
            v.visit(stmt)
        return v.found

    def _forward_return_after_cleanup(self, ctx: FinallyContext, b: CodeBuilder) -> None:
        b.aload(ctx.return_slot)
        if self.finally_stack:
            outer = self.finally_stack[-1]
            b.astore(outer.return_slot); b.goto(outer.return_entry)
        else:
            b.areturn()

    def _branch_cleanup_entry(self, ctx: FinallyContext, target: object, b: CodeBuilder) -> object:
        # A loop nested inside this context does not leave it on break/continue.
        for depth, destinations in enumerate(self.loop_stack):
            if target in destinations and depth >= ctx.loop_depth:
                return target
        if target not in ctx.branch_entries:
            ctx.branch_entries[target] = b.label()
        return ctx.branch_entries[target]

    def _forward_branch_after_cleanup(self, target: object, b: CodeBuilder) -> None:
        if self.finally_stack:
            outer = self.finally_stack[-1]
            b.goto(self._branch_cleanup_entry(outer, target, b))
        else:
            b.goto(target)

    def _compile_finalbody_copy(self, finalbody: list[ast.stmt], b: CodeBuilder, scope: Scope, in_function: bool) -> None:
        for stmt in finalbody: self._stmt(stmt, b, scope, in_function)

    def _emit_context_cleanup(self, ctx: FinallyContext, b: CodeBuilder, scope: Scope, in_function: bool) -> None:
        if ctx.manager_slot is not None:
            b.aload(ctx.manager_slot)
            method = "asyncWithExitNormal" if ctx.async_manager else "withExitNormal"
            b.invokestatic(RUNTIME, method, f"({OBJ})V")
        else:
            self._compile_finalbody_copy(ctx.finalbody, b, scope, in_function)

    def _compile_try_finally(self, body: list[ast.stmt], handlers: list[ast.ExceptHandler], orelse: list[ast.stmt],
                             finalbody: list[ast.stmt], b: CodeBuilder, scope: Scope, in_function: bool) -> None:
        start, protected_end, handler, done = b.label(), b.label(), b.label(), b.label()
        exc_slot = scope.temp(); return_slot = scope.temp(); return_entry = b.label()
        ctx = FinallyContext(finalbody, return_slot, return_entry, {}, loop_depth=len(self.loop_stack))
        b.mark(start)
        self.finally_stack.append(ctx)
        if handlers:
            self._compile_try_except(body, handlers, orelse, b, scope, in_function)
        else:
            for stmt in body: self._stmt(stmt, b, scope, in_function)
        self.finally_stack.pop()
        b.mark(protected_end)

        # Normal completion.
        self._emit_context_cleanup(ctx, b, scope, in_function)
        b.goto(done)

        # Exceptional completion. This handler range ends before any copy of finally,
        # so an exception raised by finally itself is not accidentally handled twice.
        b.mark(handler); b.astore(exc_slot)
        self.exception_stack.append(exc_slot)  # a bare ``raise`` in the cleanup re-raises the in-flight exception
        self._emit_context_cleanup(ctx, b, scope, in_function)
        self.exception_stack.pop()
        b.aload(exc_slot); b.athrow()
        b.add_exception_handler(start, protected_end, handler, "java/lang/Throwable")

        # Return completion. The body stored the return value before jumping here.
        b.mark(return_entry)
        self._emit_context_cleanup(ctx, b, scope, in_function)
        self._forward_return_after_cleanup(ctx, b)

        # break/continue completions. Each distinct JVM destination gets a cleanup copy.
        for target, entry in list(ctx.branch_entries.items()):
            b.mark(entry)
            self._emit_context_cleanup(ctx, b, scope, in_function)
            self._forward_branch_after_cleanup(target, b)

        b.mark(done)

    def _compile_with(self, items: list[ast.withitem], body: list[ast.stmt], b: CodeBuilder, scope: Scope, in_function: bool, *, is_async: bool = False) -> None:
        if not items:
            for stmt in body: self._stmt(stmt, b, scope, in_function)
            return
        item, rest = items[0], items[1:]
        manager_slot, exc_slot = scope.temp(), scope.temp()
        return_slot = scope.temp(); return_entry = b.label()
        ctx = FinallyContext([], return_slot, return_entry, {}, manager_slot=manager_slot, async_manager=is_async, loop_depth=len(self.loop_stack))
        self._expr(item.context_expr, b, scope); b.astore(manager_slot)
        b.aload(manager_slot); b.invokestatic(RUNTIME, "asyncWithEnter" if is_async else "withEnter", f"({OBJ}){OBJ}")
        if item.optional_vars is None: b.pop()
        else: self._store_target(item.optional_vars, b, scope)
        start, protected_end, handler, done = b.label(), b.label(), b.label(), b.label()
        b.mark(start)
        self.finally_stack.append(ctx)
        self._compile_with(rest, body, b, scope, in_function, is_async=is_async)
        self.finally_stack.pop()
        b.mark(protected_end)
        b.aload(manager_slot); b.invokestatic(RUNTIME, "asyncWithExitNormal" if is_async else "withExitNormal", f"({OBJ})V")
        b.goto(done)
        b.mark(handler); b.astore(exc_slot)
        b.aload(manager_slot); b.aload(exc_slot); b.invokestatic(RUNTIME, "asyncWithExitException" if is_async else "withExitException", f"({OBJ}{OBJ})Z")
        b.ifne(done); b.aload(exc_slot); b.athrow()
        b.add_exception_handler(start, protected_end, handler, "java/lang/Throwable")

        # Return/break/continue are normal context-manager exits. Route them
        # through __exit__(None, None, None), then continue unwinding through
        # any outer with/finally context.
        b.mark(return_entry)
        self._emit_context_cleanup(ctx, b, scope, in_function)
        self._forward_return_after_cleanup(ctx, b)
        for target, entry in list(ctx.branch_entries.items()):
            b.mark(entry)
            self._emit_context_cleanup(ctx, b, scope, in_function)
            self._forward_branch_after_cleanup(target, b)
        b.mark(done)

    def _emit_stmt_line(self, lineno: int, b: CodeBuilder) -> None:
        self._emit_int(lineno, b)
        b.invokestatic(RUNTIME, "traceLine" if self.trace else "setCurrentLine", f"({OBJ})V")

    def _loop_head(self, node, start, b: CodeBuilder) -> None:
        """Mark a loop's back-edge target. Instrumented code reports the header line on every re-entry."""
        if not self.trace:
            b.mark(start); return
        check = b.label()
        b.goto(check); b.mark(start); self._emit_stmt_line(node.lineno, b); b.mark(check)

    def _trace_handler_entry(self, handler, exc_slot: int, b: CodeBuilder) -> None:
        if self.trace:
            b.aload(exc_slot); b.invokestatic(RUNTIME, "traceHandler", f"({OBJ})V")

    def _trace_handler_line(self, handler, b: CodeBuilder) -> None:
        if self.trace:
            self._emit_stmt_line(handler.lineno, b)

    def _stmt(self, node: ast.stmt, b: CodeBuilder, scope: Scope, in_function: bool) -> None:
        if hasattr(node, "lineno"):
            if self._line_emitted is node: self._line_emitted = None
            else: self._emit_stmt_line(node.lineno, b)
        if self._stmt_part0(node, b, scope, in_function):
            return
        if self._stmt_part1(node, b, scope, in_function):
            return
        if self._stmt_part2(node, b, scope, in_function):
            return
        if self._stmt_part3(node, b, scope, in_function):
            return

    def _stmt_part0(self, node, b, scope, in_function):
        match node:
                case ast.Pass():
                    return True
                case ast.Expr(value=value):
                    self._expr(value, b, scope)
                    if self.mode == "single" and scope.module and scope.namespace_slot is None and not in_function:
                        b.invokestatic(NAMESPACES, "display", f"({OBJ})V")   # interactive mode: sys.displayhook(value)
                    else:
                        b.pop()
                    return True
                case ast.Assign(targets=targets, value=value):
                    self._expr(value, b, scope)
                    if len(targets) == 1:
                        self._store_target(targets[0], b, scope)
                    else:
                        tmp = scope.temp(); b.astore(tmp)
                        for target in targets:
                            b.aload(tmp); self._store_target(target, b, scope)
                    return True
                case ast.Assign(targets=[ast.Subscript(value=obj, slice=key)], value=value):
                    self._expr(obj, b, scope)
                    self._expr(key, b, scope)
                    self._expr(value, b, scope)
                    b.invokestatic(RUNTIME, "setitem", f"({OBJ}{OBJ}{OBJ})V")
                    return True
                case ast.Assign(targets=[ast.Attribute(value=obj, attr=attr)], value=value):
                    self._expr(obj, b, scope); b.ldc_string(self._class_attr_name(attr)); self._expr(value, b, scope)
                    b.invokestatic(RUNTIME, "setattr", f"({OBJ}{OBJ}{OBJ})V")
                    return True
                case ast.FunctionDef() | ast.AsyncFunctionDef():
                    self._emit_function_definition(node, b, scope)
                    return True
                case ast.Import(names=names):
                    for alias in names:
                        module_name = alias.name
                        if module_name in NATIVE_MODULES:
                            b.ldc_string(module_name); b.invokestatic(RUNTIME, "importNative", f"({OBJ}){OBJ}")
                            self._store_name(alias.asname or module_name, b, scope)
                            continue
                        jvm_name = self.import_map.get(module_name)
                        if jvm_name is None:
                            # not known at compile time: sys.meta_path decides when the statement runs
                            self._emit_run_time_import(module_name, None, 0, b, scope)
                            if alias.asname:
                                for part in module_name.split('.')[1:]:
                                    b.ldc_string(part); b.invokestatic(IMPORT_SUPPORT, "importFrom", f"({OBJ}{OBJ}){OBJ}")
                                self._store_name(alias.asname, b, scope)
                            else:
                                self._store_name(module_name.split('.')[0], b, scope)
                            continue
                        b.ldc_string(module_name); b.ldc_string(jvm_name.replace('/', '.'))
                        b.invokestatic(RUNTIME, "importModule", f"({OBJ}{OBJ}){OBJ}")
                        if alias.asname:
                            self._store_name(alias.asname, b, scope)
                        elif '.' in module_name:
                            b.pop()
                            top=module_name.split('.')[0]; top_jvm=self.import_map.get(top)
                            if top_jvm is None: raise CompileError(f"package {top!r} is not available")
                            b.ldc_string(top); b.ldc_string(top_jvm.replace('/', '.'))
                            b.invokestatic(RUNTIME, "importModule", f"({OBJ}{OBJ}){OBJ}")
                            self._store_name(top, b, scope)
                        else:
                            self._store_name(module_name, b, scope)
                    return True
                case ast.ImportFrom(module="__future__", level=0, names=names):
                    known = {"annotations", "generators", "nested_scopes", "division", "absolute_import", "with_statement",
                             "print_function", "unicode_literals", "generator_stop", "barry_as_FLUFL"}
                    for alias in names:
                        if alias.name not in known:
                            raise CompileError(f"future feature {alias.name} is not defined")
                    return True
                case _:
                    return False

    def _stmt_part1(self, node, b, scope, in_function):
        match node:
                case ast.ImportFrom(module=module, names=names, level=level):
                    resolved = module
                    if level:
                        parts = self.package_name.split('.') if self.package_name else []
                        up = level - 1
                        if up > len(parts):
                            resolved = None
                        else:
                            base = parts[:len(parts)-up] if up else parts
                            if module:
                                base += module.split('.')
                            resolved = '.'.join(base)
                    if not resolved or (resolved not in NATIVE_MODULES and self.import_map.get(resolved) is None):
                        # not known at compile time (or a relative import that only fails when it runs)
                        self._emit_run_time_import(module or '', ','.join(alias.name for alias in names), level, b, scope)
                        module_slot = scope.temp(); b.astore(module_slot)
                        for alias in names:
                            if alias.name == '*':
                                if not (self.dynamic and scope.module):
                                    raise CompileError(f"star import from {module or '.'!r} is resolved when the program runs; that is only supported in exec'd or imported-at-run-time code")
                                b.aload(module_slot); self._emit_namespace_call("locals", b, scope)
                                b.invokestatic(IMPORT_SUPPORT, "importStar", f"({OBJ}{OBJ}){OBJ}"); b.pop()
                                continue
                            b.aload(module_slot); b.ldc_string(alias.name)
                            b.invokestatic(IMPORT_SUPPORT, "importFrom", f"({OBJ}{OBJ}){OBJ}")
                            self._store_name(alias.asname or alias.name, b, scope)
                        return True
                    if resolved in NATIVE_MODULES and not level:
                        has_star = any(alias.name == '*' for alias in names)
                        if has_star and not (self.dynamic and scope.module):
                            raise CompileError(f"star import from native module {resolved!r} is only supported in exec'd or imported-at-run-time code")
                        b.ldc_string(resolved); b.invokestatic(RUNTIME, "importNative", f"({OBJ}){OBJ}")
                        module_slot = scope.temp(); b.astore(module_slot)
                        for alias in names:
                            if alias.name == '*':
                                b.aload(module_slot); self._emit_namespace_call("locals", b, scope)
                                b.invokestatic(IMPORT_SUPPORT, "importStar", f"({OBJ}{OBJ}){OBJ}"); b.pop()
                                continue
                            b.aload(module_slot); b.ldc_string(alias.name)
                            b.invokestatic(IMPORT_SUPPORT, "importFrom", f"({OBJ}{OBJ}){OBJ}")
                            self._store_name(alias.asname or alias.name, b, scope)
                        return True
                    jvm_name = self.import_map.get(resolved)
                    if jvm_name is None:
                        raise CompileError(f"import from module {resolved!r} is not available")
                    b.ldc_string(resolved); b.ldc_string(jvm_name.replace('/', '.'))
                    b.invokestatic(RUNTIME, "importModule", f"({OBJ}{OBJ}){OBJ}")
                    module_slot = scope.temp(); b.astore(module_slot)
                    for alias in names:
                        if alias.name == '*':
                            exports = self.import_exports.get(resolved)
                            if exports is None:
                                raise CompileError(f"star import from {resolved!r} requires a statically analyzable __all__ or module exports")
                            for export_name in exports:
                                b.aload(module_slot); b.ldc_string(export_name)
                                b.invokestatic(RUNTIME, "moduleGetattr", f"({OBJ}{OBJ}){OBJ}")
                                self._store_name(export_name, b, scope)
                            continue
                        submodule = resolved + '.' + alias.name
                        sub_jvm = self.import_map.get(submodule)
                        if sub_jvm is not None:
                            b.ldc_string(submodule); b.ldc_string(sub_jvm.replace('/', '.'))
                            b.invokestatic(RUNTIME, "importModule", f"({OBJ}{OBJ}){OBJ}")
                        else:
                            b.aload(module_slot); b.ldc_string(alias.name)
                            b.invokestatic(RUNTIME, "moduleGetattr", f"({OBJ}{OBJ}){OBJ}")
                        self._store_name(alias.asname or alias.name, b, scope)
                    return True
                case ast.ClassDef(name=name, decorator_list=decorators):
                    info = self.class_infos[id(node)]
                    decorator_slots: list[int] = []
                    for decorator in decorators:
                        self._expr(decorator, b, scope)
                        slot = scope.temp(); b.astore(slot); decorator_slots.append(slot)
                    b.ldc_string(name); self._emit_module_name(b)
                    b.invokestatic(RUNTIME, "classCreate", f"({OBJ}{OBJ}){OBJ}")
                    class_slot = scope.temp(); b.astore(class_slot)
                    b.aload(class_slot); b.ldc_string(info.qualname)
                    b.invokestatic(RUNTIME, "classSetQualname", f"({OBJ}{OBJ})V")
                    if scope.env_mode and scope.env_slot is not None: b.aload(scope.env_slot)
                    else: b.aconst_null()
                    b.invokestatic(RUNTIME, "envChild", f"({OBJ}){OBJ}")
                    closure_slot = scope.temp(); b.astore(closure_slot)
                    b.aload(closure_slot); b.ldc_string("__class__")
                    b.invokestatic(RUNTIME, "unbound", f"(){OBJ}")
                    b.invokestatic(RUNTIME, "envSetLocal", f"({OBJ * 3})V")
                    for base in info.bases:
                        b.aload(class_slot)
                        if isinstance(base, ast.Starred):
                            self._expr(base.value, b, scope); b.invokestatic(RUNTIME2, "classAddBases", f"({OBJ}{OBJ})V")
                        else:
                            self._expr(base, b, scope); b.invokestatic(RUNTIME, "classAddBase", f"({OBJ}{OBJ})V")

                    b.invokestatic(RUNTIME, "dict0", f"(){OBJ}")
                    class_kw_slot = scope.temp(); b.astore(class_kw_slot)
                    metaclass_slot = scope.temp(); b.aconst_null(); b.astore(metaclass_slot)
                    for kw_name, kw_value in (info.keywords or []):
                        if kw_name is None:
                            b.aload(class_kw_slot); self._expr(kw_value, b, scope)
                            b.invokestatic(RUNTIME2, "classKwUpdate", f"({OBJ}{OBJ})V")
                        elif kw_name == "metaclass" and not any(name is None for name, _ in info.keywords):
                            self._expr(kw_value, b, scope); b.astore(metaclass_slot)
                        else:
                            b.aload(class_kw_slot); b.ldc_string(kw_name); self._expr(kw_value, b, scope)
                            b.invokestatic(RUNTIME, "dictPut", f"({OBJ}{OBJ}{OBJ})V")

                    if any(name is None for name, _ in (info.keywords or [])):
                        b.aload(class_kw_slot); b.invokestatic(RUNTIME2, "classTakeMetaclass", f"({OBJ}){OBJ}"); b.astore(metaclass_slot)
                    b.aload(class_slot)
                    b.aload(metaclass_slot)
                    b.aload(class_kw_slot)
                    b.invokestatic(RUNTIME, "classPrepare", f"({OBJ}{OBJ}{OBJ}){OBJ}")
                    namespace_slot = scope.temp(); b.astore(namespace_slot)
                    body_scope = Scope(start_slot=scope.next_slot, parent=scope,
                                       env_mode=True, env_slot=closure_slot)
                    body_scope.free_names = scope.local_names | scope.free_names
                    body_scope.local_names, body_scope.global_decl, body_scope.nonlocal_decl = self._function_locals(node)
                    body_scope.namespace_slot = namespace_slot
                    old_class = self.current_class
                    self.current_class = info
                    saved_control = self.loop_stack, self.cleanup_stack, self.finally_stack
                    self.loop_stack, self.cleanup_stack, self.finally_stack = [], [], []
                    b.ldc_string(name); b.ldc_string(self.filename); self._emit_int(node.lineno, b)
                    b.invokestatic(RUNTIME, "pushLogicalFrame", f"({OBJ * 3})V")
                    b.aload(namespace_slot); b.invokestatic(RUNTIME, "frameSetNamespace", f"({OBJ})V")
                    body_start, body_end, body_handler, body_done = b.label(), b.label(), b.label(), b.label()
                    body_exception = body_scope.temp()
                    b.mark(body_start); b.emit(0x00)  # non-empty protected range for literal-only suites
                    try:
                        for item in node.body:
                            if item is node.body[0] and isinstance(item, ast.Expr) and isinstance(item.value, ast.Constant) and isinstance(item.value.value, str):
                                self._expr(item.value, b, body_scope)
                                self._store_name("__doc__", b, body_scope)
                            elif not (isinstance(item, ast.Expr) and isinstance(item.value, ast.Constant)):
                                self._stmt(item, b, body_scope, in_function=False)
                    finally:
                        self.current_class = old_class
                        self.loop_stack, self.cleanup_stack, self.finally_stack = saved_control
                    b.mark(body_end)
                    b.invokestatic(RUNTIME, "popLogicalFrame", "()V"); b.goto(body_done)
                    b.mark(body_handler); b.astore(body_exception)
                    b.aload(body_exception); b.invokestatic(RUNTIME, "tracebackAddCurrentFrame", f"({OBJ})V")
                    b.invokestatic(RUNTIME, "popLogicalFrame", "()V")
                    b.aload(body_exception); b.athrow()
                    b.add_exception_handler(body_start, body_end, body_handler, "java/lang/Throwable")
                    b.mark(body_done)
                    scope.next_slot = max(scope.next_slot, body_scope.next_slot)

                    # Publish only an actual implicit class cell, after suite execution.
                    # Nested class cells belong to their own class construction.
                    needs_cell = self._class_needs_cell(node)
                    if needs_cell:
                        b.aload(namespace_slot); b.aload(closure_slot)
                        b.invokestatic(RUNTIME, "classPublishCell", f"({OBJ * 2})V")
                    b.aload(class_slot)
                    b.aload(metaclass_slot)
                    b.aload(namespace_slot); b.aload(class_kw_slot)
                    b.invokestatic(RUNTIME, "classFinish", f"({OBJ}{OBJ}{OBJ}{OBJ}){OBJ}")
                    b.astore(class_slot)
                    b.aload(closure_slot); b.aload(class_slot); self._boxed_bool(needs_cell, b)
                    b.invokestatic(RUNTIME, "classValidateCell", f"({OBJ * 3})V")
                    for decorator_slot in reversed(decorator_slots):
                        b.aload(decorator_slot)
                        b.invokestatic(RUNTIME, "list0", f"(){OBJ}")
                        b.dup(); b.aload(class_slot); b.invokestatic(RUNTIME, "listAppend", f"({OBJ}{OBJ})V")
                        b.invokestatic(RUNTIME, "dict0", f"(){OBJ}")
                        b.invokestatic(RUNTIME, "callFunction", f"({OBJ}{OBJ}{OBJ}){OBJ}")
                        b.astore(class_slot)
                    b.aload(class_slot)
                    if info.local or self.dynamic: self._store_name(name, b, scope)
                    else: b.putstatic(self.class_name, info.class_field, OBJ)
                    return True
                case ast.AnnAssign(target=ast.Name(id=name), value=value):
                    if value is None:
                        raise CompileError("annotation-only assignments are not implemented yet")
                    self._expr(value, b, scope)
                    self._store_name(name, b, scope)
                    return True
                case ast.AugAssign(target=ast.Name(id=name), op=op, value=value):
                    self._load_name(name, b, scope)
                    self._expr(value, b, scope)
                    self._binary_runtime(op, b, inplace=True)
                    self._store_name(name, b, scope)
                    return True
                case ast.AugAssign(target=ast.Attribute(value=obj, attr=attr), op=op, value=value):
                    obj_slot, result_slot = scope.temp(), scope.temp()
                    self._expr(obj, b, scope); b.astore(obj_slot)
                    b.aload(obj_slot); b.ldc_string(self._class_attr_name(attr)); b.invokestatic(RUNTIME, "getattr", f"({OBJ}{OBJ}){OBJ}")
                    self._expr(value, b, scope); self._binary_runtime(op, b, inplace=True); b.astore(result_slot)
                    b.aload(obj_slot); b.ldc_string(self._class_attr_name(attr)); b.aload(result_slot)
                    b.invokestatic(RUNTIME, "setattr", f"({OBJ}{OBJ}{OBJ})V")
                    return True
                case ast.AugAssign(target=ast.Subscript(value=obj, slice=key), op=op, value=value):
                    obj_slot, key_slot, result_slot = scope.temp(), scope.temp(), scope.temp()
                    self._expr(obj, b, scope); b.astore(obj_slot)
                    self._expr(key, b, scope); b.astore(key_slot)
                    b.aload(obj_slot); b.aload(key_slot); b.invokestatic(RUNTIME, "getitem", f"({OBJ}{OBJ}){OBJ}")
                    self._expr(value, b, scope); self._binary_runtime(op, b, inplace=True); b.astore(result_slot)
                    b.aload(obj_slot); b.aload(key_slot); b.aload(result_slot)
                    b.invokestatic(RUNTIME, "setitem", f"({OBJ}{OBJ}{OBJ})V")
                    return True
                case ast.Global(names=names):
                    scope.global_decl.update(names)
                    return True
                case ast.Nonlocal(names=names):
                    scope.nonlocal_decl.update(names)
                    return True
                case _:
                    return False

    def _stmt_part2(self, node, b, scope, in_function):
        match node:
                case ast.Return(value=value):
                    if not in_function: raise CompileError("return outside function")
                    if value is None: b.aconst_null()
                    else: self._expr(value, b, scope)
                    if self.finally_stack:
                        ctx = self.finally_stack[-1]; b.astore(ctx.return_slot); b.goto(ctx.return_entry)
                    else:
                        b.areturn()
                    return True
                case ast.If(test=test, body=body, orelse=orelse):
                    else_label, end_label = b.label(), b.label()
                    self._truthy(test, b, scope)
                    b.ifeq(else_label)
                    for s in body: self._stmt(s, b, scope, in_function)
                    b.goto(end_label)
                    b.mark(else_label)
                    for s in orelse: self._stmt(s, b, scope, in_function)
                    b.mark(end_label)
                    return True
                case ast.While(test=test, body=body, orelse=orelse):
                    start, normal_end, break_end = b.label(), b.label(), b.label()
                    self._loop_head(node, start, b)
                    self._truthy(test, b, scope); b.ifeq(normal_end)
                    self.loop_stack.append((start, break_end))
                    for s in body: self._stmt(s, b, scope, in_function)
                    self.loop_stack.pop(); b.goto(start)
                    b.mark(normal_end)
                    for s in orelse: self._stmt(s, b, scope, in_function)
                    b.mark(break_end)
                    return True
                case ast.For(target=target, iter=iterable, body=body, orelse=orelse):
                    iterator_slot = scope.temp()
                    self._expr(iterable, b, scope); b.invokestatic(RUNTIME, "iter", f"({OBJ}){OBJ}"); b.astore(iterator_slot)
                    start, normal_end, break_end = b.label(), b.label(), b.label()
                    self._loop_head(node, start, b); b.aload(iterator_slot); b.invokestatic(RUNTIME, "iterHasNext", f"({OBJ})Z"); b.ifeq(normal_end)
                    b.aload(iterator_slot); b.invokestatic(RUNTIME, "iterNext", f"({OBJ}){OBJ}"); self._store_target(target, b, scope)
                    self.loop_stack.append((start, break_end))
                    for s in body: self._stmt(s, b, scope, in_function)
                    self.loop_stack.pop(); b.goto(start)
                    b.mark(normal_end)
                    for s in orelse: self._stmt(s, b, scope, in_function)
                    b.mark(break_end)
                    return True
                case ast.AsyncFor(target=target, iter=iterable, body=body, orelse=orelse):
                    iterator_slot, result_slot = scope.temp(), scope.temp()
                    self._expr(iterable, b, scope); b.invokestatic(RUNTIME, "aiter", f"({OBJ}){OBJ}"); b.astore(iterator_slot)
                    start, normal_end, break_end = b.label(), b.label(), b.label()
                    b.mark(start)
                    b.aload(iterator_slot); b.invokestatic(RUNTIME, "asyncIterNext", f"({OBJ}){OBJ}"); b.astore(result_slot)
                    b.aload(result_slot); b.invokestatic(RUNTIME, "asyncNextDone", f"({OBJ})Z"); b.ifne(normal_end)
                    b.aload(result_slot); b.invokestatic(RUNTIME, "asyncNextValue", f"({OBJ}){OBJ}"); self._store_target(target, b, scope)
                    self.loop_stack.append((start, break_end))
                    for s in body: self._stmt(s, b, scope, in_function)
                    self.loop_stack.pop(); b.goto(start)
                    b.mark(normal_end)
                    for s in orelse: self._stmt(s, b, scope, in_function)
                    b.mark(break_end)
                    return True
                case ast.Break():
                    if not self.loop_stack: raise CompileError("break outside loop")
                    target = self.loop_stack[-1][1]
                    self._leave_handlers_for_branch(b)
                    if self.finally_stack: b.goto(self._branch_cleanup_entry(self.finally_stack[-1], target, b))
                    else: b.goto(target)
                    return True
                case ast.Continue():
                    if not self.loop_stack: raise CompileError("continue outside loop")
                    target = self.loop_stack[-1][0]
                    self._leave_handlers_for_branch(b)
                    if self.finally_stack: b.goto(self._branch_cleanup_entry(self.finally_stack[-1], target, b))
                    else: b.goto(target)
                    return True
                case ast.Delete(targets=targets):
                    for target in targets: self._delete_target(target, b, scope)
                    return True
                case _:
                    return False

    def _stmt_part3(self, node, b, scope, in_function):
        match node:
                case ast.Assert(test=test, msg=msg):
                    if self.optimize > 0:
                        return True
                    ok = b.label(); self._truthy(test, b, scope); b.ifne(ok)
                    if msg is None: b.aconst_null()
                    else: self._expr(msg, b, scope)
                    b.invokestatic(RUNTIME, "assertFail", f"({OBJ})V")
                    b.mark(ok)
                    return True
                case ast.Raise(exc=exc, cause=cause):
                    if exc is None:
                        if not self.exception_stack:
                            b.invokestatic(RUNTIME2, "reraiseCurrent", "()V")
                            return True
                        b.aload(self.exception_stack[-1]); b.athrow()
                    else:
                        self._expr(exc, b, scope)
                        if cause is None:
                            if self.exception_stack:
                                b.aload(self.exception_stack[-1])
                                b.invokestatic(RUNTIME, "raiseObjectWithContext", f"({OBJ}{OBJ})V")
                            else:
                                b.invokestatic(RUNTIME, "raiseObject", f"({OBJ})V")
                        else:
                            self._expr(cause, b, scope)
                            if self.exception_stack:
                                b.aload(self.exception_stack[-1])
                                b.invokestatic(RUNTIME, "raiseObjectFromWithContext", f"({OBJ}{OBJ}{OBJ})V")
                            else:
                                b.invokestatic(RUNTIME, "raiseObjectFrom", f"({OBJ}{OBJ})V")
                    return True
                case ast.Try(body=body, handlers=handlers, orelse=orelse, finalbody=[]):
                    self._compile_try_except(body, handlers, orelse, b, scope, in_function)
                    return True
                case ast.Try(body=body, handlers=handlers, orelse=orelse, finalbody=finalbody):
                    self._compile_try_finally(body, handlers, orelse, finalbody, b, scope, in_function)
                    return True
                case ast.With(items=items, body=body):
                    self._compile_with(items, body, b, scope, in_function)
                    return True
                case ast.AsyncWith(items=items, body=body):
                    self._compile_with(items, body, b, scope, in_function, is_async=True)
                    return True
                case _:
                    raise CompileError(f"Unsupported statement: {type(node).__name__} at line {getattr(node, 'lineno', '?')}")

    def _expr(self, node: ast.expr, b: CodeBuilder, scope: Scope) -> None:
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
            fname = node.func.id
            bound_here = (scope.has(fname) or fname in scope.local_names or fname in scope.free_names or fname in self.global_names
                          or fname in self.functions or fname in self.classes or fname in scope.global_decl)
            if not bound_here and not node.keywords:
                if fname in ("globals", "locals", "vars") and not node.args:
                    self._emit_namespace_call(fname, b, scope)
                    return
                if self.dynamic and fname in ("exec", "eval") and len(node.args) == 1:
                    # exec(x) / eval(x) run in the calling code's own namespaces
                    namespaced = ast.Call(func=node.func, keywords=[], args=[
                        node.args[0],
                        ast.Call(func=ast.Name(id="globals", ctx=ast.Load()), args=[], keywords=[]),
                        ast.Call(func=ast.Name(id="locals", ctx=ast.Load()), args=[], keywords=[])])
                    ast.copy_location(namespaced, node)
                    self._expr(namespaced, b, scope)
                    return
            if self.dynamic and not bound_here and (fname in BUILTIN_FUNCTIONS or fname in BUILTIN_TYPES or fname in EXCEPTION_TYPES):
                # builtins resolve through the namespace's __builtins__, which exec'd code may replace
                self._emit_dynamic_call(node.func, node.args, node.keywords, b, scope)
                return
        if (not self.dynamic and isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and not node.keywords
                and node.func.id in ("exec", "eval") and len(node.args) == 1 and not node.func.id in self.global_names
                and not scope.has(node.func.id) and node.func.id not in scope.local_names and node.func.id not in scope.free_names):
            self._emit_implicit_namespace_call(node, b, scope)
            return
        if (isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == CLASSDICT_INTRINSIC
                and scope.namespace_slot is not None):
            b.aload(scope.namespace_slot)   # the namespace of the class body being executed (annot.py)
            return
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
            name = node.func.id
            if name in {"min", "max", "sorted", "hex", "oct", "bin", "super"} or any(isinstance(a, ast.Starred) for a in node.args) or any(k.arg is None for k in node.keywords) or scope.namespace_slot is not None or name in (self.global_names | self.functions.keys() | self.classes.keys() | scope.local_names | scope.free_names | scope.global_decl) or scope.has(name):
                self._emit_dynamic_call(node.func, node.args, node.keywords, b, scope)
                return
        if self._expr_part0(node, b, scope):
            return
        if self._expr_part1(node, b, scope):
            return
        if self._expr_part2(node, b, scope):
            return
        if self._expr_part3(node, b, scope):
            return
        if self._expr_part4(node, b, scope):
            return
        if self._expr_part5(node, b, scope):
            return
        if self._expr_part6(node, b, scope):
            return

    def _expr_part0(self, node, b, scope):
        match node:
                case ast.Constant(value=value) if value is Ellipsis:
                    b.invokestatic(RUNTIME, "ellipsis", f"(){OBJ}")
                    return True
                case ast.Constant(value=None):
                    b.aconst_null()
                    return True
                case ast.Constant(value=True):
                    self._boxed_bool(True, b)
                    return True
                case ast.Constant(value=False):
                    self._boxed_bool(False, b)
                    return True
                case ast.Constant(value=int() as value) if not isinstance(value, bool):
                    self._emit_int(value, b)
                    return True
                case ast.Constant(value=float() as value):
                    b.ldc_string({"inf": "Infinity", "-inf": "-Infinity", "nan": "NaN"}.get(repr(value), repr(value)))
                    b.invokestatic("java/lang/Double", "valueOf", "(Ljava/lang/String;)Ljava/lang/Double;")
                    return True
                case ast.Constant(value=complex() as value):
                    for part in (value.real, value.imag):
                        text = repr(part)
                        b.ldc_string({"inf": "Infinity", "-inf": "-Infinity", "nan": "NaN"}.get(text, text))
                        b.invokestatic("java/lang/Double", "valueOf", "(Ljava/lang/String;)Ljava/lang/Double;")
                    b.invokestatic(RUNTIME, "complexLiteral", f"({OBJ}{OBJ}){OBJ}")
                    return True
                case ast.Constant(value=str() as value):
                    b.ldc_string(value)
                    return True
                case ast.Constant(value=bytes() as value):
                    b.ldc_string(value.hex())
                    b.invokestatic(RUNTIME, "bytesFromHexLiteral", f"({OBJ}){OBJ}")
                    return True
                case ast.Name(id=name):
                    self._load_name(name, b, scope)
                    return True
                case ast.NamedExpr(target=ast.Name(id=name), value=value):
                    self._expr(value, b, scope); b.dup()
                    target_scope = getattr(scope, "comprehension_outer", None) or scope
                    self._store_name(name, b, target_scope, temp_scope=scope)
                    return True
                case ast.Lambda():
                    self._emit_lambda(node, b, scope)
                    return True
                case _:
                    return False

    def _expr_part1(self, node, b, scope):
        match node:
                case ast.ListComp() | ast.SetComp() | ast.DictComp():
                    self._emit_comprehension(node, b, scope)
                    return True
                case ast.GeneratorExp():
                    self._emit_generator_expression(node, b, scope)
                    return True
                case ast.Await(value=value):
                    self._expr(value, b, scope)
                    b.invokestatic(RUNTIME, "awaitValue", f"({OBJ}){OBJ}")
                    return True
                case ast.List(elts=elts):
                    b.invokestatic(RUNTIME, "list0", f"(){OBJ}")
                    for elt in elts:
                        b.dup()
                        if isinstance(elt, ast.Starred):
                            self._expr(elt.value, b, scope); b.invokestatic(RUNTIME, "listExtend", f"({OBJ}{OBJ})V")
                        else:
                            self._expr(elt, b, scope); b.invokestatic(RUNTIME, "listAppend", f"({OBJ}{OBJ})V")
                    return True
                case ast.Tuple(elts=elts):
                    b.invokestatic(RUNTIME, "tuple0", f"(){OBJ}")
                    for elt in elts:
                        b.dup()
                        if isinstance(elt, ast.Starred):
                            self._expr(elt.value, b, scope); b.invokestatic(RUNTIME, "tupleExtend", f"({OBJ}{OBJ})V")
                        else:
                            self._expr(elt, b, scope); b.invokestatic(RUNTIME, "tupleAppend", f"({OBJ}{OBJ})V")
                    return True
                case ast.Set(elts=elts):
                    b.invokestatic(RUNTIME, "set0", f"(){OBJ}")
                    for elt in elts:
                        b.dup()
                        if isinstance(elt, ast.Starred):
                            self._expr(elt.value, b, scope); b.invokestatic(RUNTIME, "setUpdate", f"({OBJ}{OBJ})V")
                        else:
                            self._expr(elt, b, scope); b.invokestatic(RUNTIME, "setAdd", f"({OBJ}{OBJ})V")
                    return True
                case ast.Dict(keys=keys, values=values):
                    b.invokestatic(RUNTIME, "dict0", f"(){OBJ}")
                    for key, value in zip(keys, values):
                        b.dup()
                        if key is None:
                            self._expr(value, b, scope); b.invokestatic(RUNTIME, "dictUpdate", f"({OBJ}{OBJ})V")
                        else:
                            self._expr(key, b, scope); self._expr(value, b, scope)
                            b.invokestatic(RUNTIME, "dictPut", f"({OBJ}{OBJ}{OBJ})V")
                    return True
                case ast.Subscript(value=value, slice=key):
                    self._expr(value, b, scope); self._expr(key, b, scope)
                    b.invokestatic(RUNTIME, "getitem", f"({OBJ}{OBJ}){OBJ}")
                    return True
                case ast.Attribute(value=value, attr=attr):
                    self._expr(value, b, scope); b.ldc_string(self._class_attr_name(attr))
                    b.invokestatic(RUNTIME, "getattr", f"({OBJ}{OBJ}){OBJ}")
                    return True
                case ast.Slice(lower=lower, upper=upper, step=step):
                    (b.aconst_null() if lower is None else self._expr(lower, b, scope))
                    (b.aconst_null() if upper is None else self._expr(upper, b, scope))
                    (b.aconst_null() if step is None else self._expr(step, b, scope))
                    b.invokestatic(RUNTIME, "slice", f"({OBJ}{OBJ}{OBJ}){OBJ}")
                    return True
                case ast.IfExp(test=test, body=body, orelse=orelse):
                    no, end = b.label(), b.label(); self._truthy(test, b, scope); b.ifeq(no)
                    self._expr(body, b, scope); b.goto(end); b.mark(no); self._expr(orelse, b, scope); b.mark(end)
                    return True
                case ast.JoinedStr(values=values):
                    b.ldc_string("")
                    for value in values:
                        if isinstance(value, ast.Constant): self._expr(value, b, scope)
                        elif isinstance(value, ast.FormattedValue):
                            self._expr(value.value, b, scope)
                            if value.conversion != -1:
                                method = {ord('r'): "repr_", ord('s'): "str_", ord('a'): "ascii_"}[value.conversion]
                                b.invokestatic(RUNTIME, method, f"({OBJ}){OBJ}")
                            if value.format_spec is None: b.ldc_string("")
                            else: self._expr(value.format_spec, b, scope)
                            b.invokestatic(RUNTIME, "formatValue", f"({OBJ * 2}){OBJ}")
                        else: raise CompileError("unsupported f-string component")
                        b.invokestatic(RUNTIME, "add", f"({OBJ}{OBJ}){OBJ}")
                    return True
                case _:
                    return False

    def _expr_part2(self, node, b, scope):
        match node:
                case ast.BinOp(left=left, op=op, right=right):
                    self._expr(left, b, scope); self._expr(right, b, scope); self._binary_runtime(op, b)
                    return True
                case ast.UnaryOp(op=ast.USub(), operand=operand):
                    self._expr(operand, b, scope); b.invokestatic(RUNTIME, "neg", f"({OBJ}){OBJ}")
                    return True
                case ast.UnaryOp(op=ast.Not(), operand=operand):
                    self._expr(operand, b, scope); b.invokestatic(RUNTIME, "not_", f"({OBJ}){OBJ}")
                    return True
                case ast.UnaryOp(op=ast.UAdd(), operand=operand):
                    self._expr(operand, b, scope); b.invokestatic(RUNTIME, "pos", f"({OBJ}){OBJ}")
                    return True
                case ast.UnaryOp(op=ast.Invert(), operand=operand):
                    self._expr(operand, b, scope); b.invokestatic(RUNTIME, "invert", f"({OBJ}){OBJ}")
                    return True
                case ast.BoolOp(op=ast.And(), values=values):
                    self._boolop(values, True, b, scope)
                    return True
                case ast.BoolOp(op=ast.Or(), values=values):
                    self._boolop(values, False, b, scope)
                    return True
                case ast.Compare(left=left, ops=ops, comparators=comparators):
                    self._compare_chain(left, ops, comparators, b, scope)
                    return True
                case ast.Call(func=ast.Name(id="print"), args=args, keywords=keywords) if all(kw.arg in {"sep", "end", "file", "flush"} for kw in keywords):
                    b.invokestatic(RUNTIME, "list0", f"(){OBJ}")
                    for arg in args:
                        b.dup(); self._expr(arg, b, scope); b.invokestatic(RUNTIME, "listAppend", f"({OBJ}{OBJ})V")
                    keyword_slots = {}
                    for kw in keywords:
                        self._expr(kw.value, b, scope)
                        keyword_slots[kw.arg] = scope.temp(); b.astore(keyword_slots[kw.arg])
                    for keyword_name in ("sep", "end", "file", "flush"):
                        if keyword_name in keyword_slots: b.aload(keyword_slots[keyword_name])
                        else: b.aconst_null()
                    b.invokestatic(RUNTIME2, "printCall", f"({OBJ * 5}){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id=pm_name), args=pm_args, keywords=[]) if (
                        pm_name.startswith(PATMA_PREFIX) and PATMA_HELPERS.get(pm_name[len(PATMA_PREFIX):]) == len(pm_args)):
                    for pm_arg in pm_args: self._expr(pm_arg, b, scope)
                    b.invokestatic(PATMA_RUNTIME, pm_name[len(PATMA_PREFIX):], f"({OBJ * len(pm_args)}){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id="len"), args=[arg], keywords=[]):
                    self._expr(arg, b, scope); b.invokestatic(RUNTIME, "len", f"({OBJ}){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id=name), args=[arg], keywords=[]) if name in {"abs", "bool", "str", "repr", "int", "float", "sum", "min", "max", "any", "all"}:
                    self._expr(arg, b, scope)
                    runtime_name = {"bool":"bool_", "str":"str_", "repr":"repr_", "int":"int_", "float":"float_"}.get(name, name)
                    b.invokestatic(RUNTIME, runtime_name, f"({OBJ}){OBJ}")
                    return True
                case _:
                    return False

    def _expr_part3(self, node, b, scope):
        match node:
                case ast.Call(func=ast.Name(id="bytes"), args=[], keywords=[]):
                    b.invokestatic(RUNTIME, "bytes0", f"(){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id="bytes"), args=[arg], keywords=[]):
                    self._expr(arg,b,scope); b.invokestatic(RUNTIME, "bytes1", f"({OBJ}){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id="bytes"), args=[arg, enc], keywords=[]):
                    self._expr(arg,b,scope); self._expr(enc,b,scope); b.invokestatic(RUNTIME, "bytes2", f"({OBJ}{OBJ}){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id="bytearray"), args=[], keywords=[]):
                    b.invokestatic(RUNTIME, "bytearray0", f"(){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id="bytearray"), args=[arg], keywords=[]):
                    self._expr(arg,b,scope); b.invokestatic(RUNTIME, "bytearray1", f"({OBJ}){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id="bytearray"), args=[arg, enc], keywords=[]):
                    self._expr(arg,b,scope); self._expr(enc,b,scope); b.invokestatic(RUNTIME, "bytearray2", f"({OBJ}{OBJ}){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id="memoryview"), args=[arg], keywords=[]):
                    self._expr(arg,b,scope); b.invokestatic(RUNTIME, "memoryview1", f"({OBJ}){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id="list"), args=[], keywords=[]):
                    b.invokestatic(RUNTIME, "list0", f"(){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id="list"), args=[arg], keywords=[]):
                    self._expr(arg, b, scope); b.invokestatic(RUNTIME, "listFrom", f"({OBJ}){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id="tuple"), args=[], keywords=[]):
                    b.invokestatic(RUNTIME, "tuple0", f"(){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id="tuple"), args=[arg], keywords=[]):
                    self._expr(arg, b, scope); b.invokestatic(RUNTIME, "tupleFrom", f"({OBJ}){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id="set"), args=[], keywords=[]):
                    b.invokestatic(RUNTIME, "set0", f"(){OBJ}")
                    return True
                case _:
                    return False

    def _expr_part4(self, node, b, scope):
        match node:
                case ast.Call(func=ast.Name(id="set"), args=[arg], keywords=[]):
                    self._expr(arg, b, scope); b.invokestatic(RUNTIME, "setFrom", f"({OBJ}){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id="dict"), args=[], keywords=[]):
                    b.invokestatic(RUNTIME, "dict0", f"(){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id="dict"), args=[arg], keywords=[]):
                    self._expr(arg, b, scope); b.invokestatic(RUNTIME, "dictFrom", f"({OBJ}){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id=name), args=[arg], keywords=[]) if name in {"sorted", "reversed"}:
                    self._expr(arg, b, scope); b.invokestatic(RUNTIME, name, f"({OBJ}){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id="__py_exc_leave_internal"), args=[ast.Constant(value=int() as depth_slot)], keywords=[]):
                    b.aload(depth_slot); b.invokestatic(RUNTIME2, "excLeave", f"({OBJ})V"); b.aconst_null()
                    return True
                case ast.Call(func=ast.Name(id="__py_eg_internal"), args=eg_args, keywords=[]):
                    for eg_arg in eg_args: self._expr(eg_arg, b, scope)
                    for _pad in range(4 - len(eg_args)): b.aconst_null()
                    b.invokestatic("pyjvm315/runtime/ExceptionGroupSupport", "egOp", f"({OBJ}{OBJ}{OBJ}{OBJ}){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id="__py_await_iter_internal"), args=[arg], keywords=[]):
                    self._expr(arg, b, scope); b.invokestatic(RUNTIME, "awaitIterator", f"({OBJ}){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id="__py_async_comp_internal"), args=[ast.Constant(value=int() as token)], keywords=[]):
                    self._emit_async_comprehension_coroutine(token, b, scope)
                    return True
                case ast.Call(func=ast.Name(id="aiter"), args=[arg], keywords=[]):
                    self._expr(arg, b, scope); b.invokestatic(RUNTIME, "aiter", f"({OBJ}){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id="anext"), args=[arg], keywords=[]):
                    self._expr(arg, b, scope); b.invokestatic(RUNTIME, "anext_", f"({OBJ}){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id="iter"), args=[arg], keywords=[]):
                    self._expr(arg, b, scope); b.invokestatic(RUNTIME, "iter", f"({OBJ}){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id="next"), args=[arg], keywords=[]):
                    self._expr(arg, b, scope); b.invokestatic(RUNTIME, "next_", f"({OBJ}){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id=name), args=args, keywords=[]) if name in EXCEPTION_TYPES:
                    b.ldc_string(name)
                    if len(args) > 1:
                        b.invokestatic(RUNTIME, "list0", f"(){OBJ}")
                        for arg in args:
                            b.dup(); self._expr(arg, b, scope); b.invokestatic(RUNTIME, "listAppend", f"({OBJ}{OBJ})V")
                        b.invokestatic(RUNTIME2, "exceptionN", f"({OBJ}{OBJ}){OBJ}")
                    elif args:
                        self._expr(args[0], b, scope)
                        b.invokestatic(RUNTIME2, "exception1", f"({OBJ}{OBJ}){OBJ}")
                    else:
                        b.aconst_null()
                        b.invokestatic(RUNTIME, "makeException", f"({OBJ}{OBJ}){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id="range"), args=args, keywords=[]) if 1 <= len(args) <= 3:
                    for arg in args: self._expr(arg, b, scope)
                    b.invokestatic(RUNTIME, f"range{len(args)}", f"({OBJ * len(args)}){OBJ}")
                    return True
                case _:
                    return False

    def _expr_part5(self, node, b, scope):
        match node:
                case ast.Call(func=ast.Name(id="type"), args=[obj], keywords=[]):
                    self._expr(obj, b, scope); b.invokestatic(RUNTIME, "typeOf", f"({OBJ}){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id="callable"), args=[obj], keywords=[]):
                    self._expr(obj, b, scope); b.invokestatic(RUNTIME, "callable_", f"({OBJ}){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id="getattr"), args=[obj, name], keywords=[]):
                    self._expr(obj, b, scope); self._expr(name, b, scope); b.invokestatic(RUNTIME, "getattr", f"({OBJ}{OBJ}){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id="getattr"), args=[obj, name, default], keywords=[]):
                    self._expr(obj, b, scope); self._expr(name, b, scope); self._expr(default, b, scope); b.invokestatic(RUNTIME, "getattrDefault", f"({OBJ}{OBJ}{OBJ}){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id="hasattr"), args=[obj, name], keywords=[]):
                    self._expr(obj, b, scope); self._expr(name, b, scope); b.invokestatic(RUNTIME, "hasattr", f"({OBJ}{OBJ}){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id="setattr"), args=[obj, name, value], keywords=[]):
                    self._expr(obj, b, scope); self._expr(name, b, scope); self._expr(value, b, scope); b.invokestatic(RUNTIME, "setattr", f"({OBJ}{OBJ}{OBJ})V"); b.aconst_null()
                    return True
                case ast.Call(func=ast.Name(id="delattr"), args=[obj, name], keywords=[]):
                    self._expr(obj, b, scope); self._expr(name, b, scope); b.invokestatic(RUNTIME, "delattr", f"({OBJ}{OBJ})V"); b.aconst_null()
                    return True
                case ast.Call(func=ast.Name(id="isinstance"), args=[obj, cls], keywords=[]):
                    self._expr(obj, b, scope); self._expr(cls, b, scope)
                    b.invokestatic(RUNTIME, "isInstance", f"({OBJ}{OBJ}){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id="issubclass"), args=[sub, cls], keywords=[]):
                    self._expr(sub, b, scope); self._expr(cls, b, scope); b.invokestatic(RUNTIME, "isSubclass", f"({OBJ}{OBJ}){OBJ}")
                    return True
                case ast.Call(func=ast.Attribute(value=obj, attr=attr), args=args, keywords=[]):
                    self._expr(obj, b, scope); b.ldc_string(self._class_attr_name(attr))
                    if len(args) <= 4 and not any(isinstance(arg, ast.Starred) for arg in args):
                        for arg in args: self._expr(arg, b, scope)
                        b.invokestatic(RUNTIME, f"callMethod{len(args)}", f"({OBJ * (2 + len(args))}){OBJ}")
                    else:
                        self._emit_call_parts(args, [], b, scope)
                        b.invokestatic(RUNTIME, "callMethodDynamic", f"({OBJ * 4}){OBJ}")
                    return True
                case ast.Call(func=ast.Name(id=name) as func, args=args, keywords=[]) if name in self.classes:
                    self._emit_dynamic_call(func, args, [], b, scope)
                    return True
                case ast.Call(func=ast.Name(id=name) as func, args=args, keywords=keywords) if name in self.functions or scope.has(name):
                    self._emit_dynamic_call(func, args, keywords, b, scope)
                    return True
                case _:
                    return False

    def _expr_part6(self, node, b, scope):
        match node:
                case ast.Call(func=func, args=args, keywords=keywords):
                    self._emit_dynamic_call(func, args, keywords, b, scope)
                    return True
                case _ if type(node).__name__ == "TemplateStr":
                    self._template_str(node, b, scope)
                    return True
                case _:
                    raise CompileError(f"Unsupported expression: {type(node).__name__} at line {getattr(node, 'lineno', '?')}")

    def _template_str(self, node, b, scope):
        """PEP 750 t-string: a list of str parts and Interpolation objects handed to the runtime's Template constructor."""
        b.invokestatic(RUNTIME, "list0", f"(){OBJ}")
        for value in node.values:
            b.dup()
            if isinstance(value, ast.Constant):
                self._expr(value, b, scope)
            elif type(value).__name__ == "Interpolation":
                self._expr(value.value, b, scope)
                b.ldc_string(value.str)
                if value.conversion == -1: b.aconst_null()
                else: b.ldc_string(chr(value.conversion))
                if value.format_spec is None: b.ldc_string("")
                else: self._expr(value.format_spec, b, scope)
                b.invokestatic(RUNTIME, "interpolation", f"({OBJ * 4}){OBJ}")
            else:
                raise CompileError("unsupported t-string component")
            b.invokestatic(RUNTIME, "listAppend", f"({OBJ}{OBJ})V")
        b.invokestatic(RUNTIME, "template", f"({OBJ}){OBJ}")

    @staticmethod
    def _handler_body(handler: ast.ExceptHandler) -> list[ast.stmt]:
        if not handler.name: return handler.body
        target = ast.Name(id=handler.name, ctx=ast.Store())
        clear = ast.Assign(targets=[target], value=ast.Constant(value=None))
        delete = ast.Delete(targets=[ast.Name(id=handler.name, ctx=ast.Del())])
        wrapped = ast.Try(body=handler.body, handlers=[], orelse=[], finalbody=[clear, delete])
        ast.copy_location(wrapped, handler)
        ast.fix_missing_locations(wrapped)
        # the implicit cleanup must not move the frame's current line (a traceback raised through it keeps the raise line)
        del clear.lineno, delete.lineno
        return [wrapped]

    def _compile_try_except(self, body: list[ast.stmt], handlers: list[ast.ExceptHandler], orelse: list[ast.stmt], b: CodeBuilder, scope: Scope, in_function: bool) -> None:
        start, protected_end, dispatch, done = b.label(), b.label(), b.label(), b.label()
        exc_slot = scope.temp()
        # an exception that left an except clause of this try body leaves that clause's entry on the handled stack
        reset_slot = -1
        if self._contains_handler(body):
            reset_slot = scope.temp()
            b.invokestatic(RUNTIME2, "excDepth", f"(){OBJ}"); b.astore(reset_slot)
        b.mark(start)
        for stmt in body: self._stmt(stmt, b, scope, in_function)
        b.mark(protected_end)
        for stmt in orelse: self._stmt(stmt, b, scope, in_function)
        b.goto(done)
        b.mark(dispatch)
        if reset_slot >= 0:
            b.invokestatic(RUNTIME2, "liftForeign", "(Ljava/lang/Throwable;)Ljava/lang/Throwable;")
        b.astore(exc_slot)
        b.aload(exc_slot); b.invokestatic(RUNTIME2, "attachContext", f"({OBJ})V")
        if reset_slot >= 0:
            b.aload(reset_slot); b.invokestatic(RUNTIME2, "excLeave", f"({OBJ})V")
        b.aload(exc_slot); b.invokestatic(RUNTIME,"tracebackAddCurrentFrame",f"({OBJ})V")
        b.add_exception_handler(start, protected_end, dispatch, "java/lang/Throwable")
        self._trace_handler_entry(None, exc_slot, b)
        for handler in handlers:
            next_handler = b.label()
            self._trace_handler_line(handler, b)
            if handler.type is not None:
                self._emit_except_match(handler.type, exc_slot, next_handler, b, scope)
            if handler.name:
                b.aload(exc_slot); b.invokestatic(RUNTIME, "exceptionInstance", f"({OBJ}){OBJ}"); self._store_name(handler.name, b, scope)
            self.exception_stack.append(exc_slot)
            handler_body = self._handler_body(handler)
            frame_depth = None
            if not self.dynamic_reraise:
                frame_depth = self._handler_enter(exc_slot, b, scope)
            if self.dynamic_reraise:
                depth_slot = scope.temp()
                b.aload(exc_slot); b.invokestatic(RUNTIME2, "excEnter", f"({OBJ}){OBJ}"); b.astore(depth_slot)
                leave = ast.Expr(value=ast.Call(func=ast.Name(id="__py_exc_leave_internal", ctx=ast.Load()), args=[ast.Constant(value=depth_slot)], keywords=[]))
                wrapped_body = ast.Try(body=handler_body, handlers=[], orelse=[], finalbody=[leave])
                ast.copy_location(wrapped_body, handler)
                handler_body = [ast.fix_missing_locations(wrapped_body)]
            for stmt in handler_body: self._stmt(stmt, b, scope, in_function)
            self.exception_stack.pop()
            if frame_depth is not None: self._handler_leave(frame_depth, b)
            b.goto(done)
            b.mark(next_handler)
        b.aload(exc_slot); b.athrow()
        b.mark(done)

    @staticmethod
    def _contains_handler(stmts: list[ast.stmt]) -> bool:
        for stmt in stmts:
            for node in ast.walk(stmt):
                if isinstance(node, ast.ExceptHandler): return True
        return False

    def _handler_enter(self, exc_slot: int, b: CodeBuilder, scope: Scope) -> int:
        """Record the exception an except clause is handling (sys.exception()); returns the slot holding the previous depth."""
        depth_slot = scope.temp()
        b.aload(exc_slot); b.invokestatic(RUNTIME2, "excEnter", f"({OBJ}){OBJ}"); b.astore(depth_slot)
        self.handler_depths.append((depth_slot, len(self.loop_stack)))
        return depth_slot

    def _handler_leave(self, depth_slot: int, b: CodeBuilder) -> None:
        self.handler_depths.pop()
        b.aload(depth_slot); b.invokestatic(RUNTIME2, "excLeave", f"({OBJ})V")

    def _leave_handlers_for_branch(self, b: CodeBuilder) -> None:
        """break/continue leaves every except clause opened inside the loop it jumps out of."""
        depth = len(self.loop_stack)
        for slot, loop_depth in self.handler_depths:
            if loop_depth == depth:
                b.aload(slot); b.invokestatic(RUNTIME2, "excLeave", f"({OBJ})V")
                return

    def _except_name_is_variable(self, name: str, scope: Scope) -> bool:
        if name in EXCEPTION_TYPES or name in self.classes or name in self.functions:
            return False
        if name in {"object", "int", "bool", "float", "complex", "str", "bytes", "bytearray", "list", "tuple", "dict", "set", "frozenset", "range", "type", "property"}:
            return True
        return name in self.global_names or scope.has(name) or name in scope.local_names or name in scope.free_names

    def _emit_except_match(self, type_node: ast.expr, exc_slot: int, next_handler, b: CodeBuilder, scope: Scope) -> None:
        """Jump to next_handler unless the exception in exc_slot matches the handler's type expression."""
        if isinstance(type_node, ast.Name) and not self._except_name_is_variable(type_node.id, scope):
            b.aload(exc_slot); b.ldc_string(type_node.id)
            b.invokestatic(RUNTIME, "exceptionMatches", f"({OBJ}{OBJ})Z"); b.ifeq(next_handler)
        elif isinstance(type_node, ast.Tuple) and all(isinstance(x, ast.Name) and not self._except_name_is_variable(x.id, scope) for x in type_node.elts):
            matched = b.label()
            for typ in type_node.elts:
                b.aload(exc_slot); b.ldc_string(typ.id)
                b.invokestatic(RUNTIME, "exceptionMatches", f"({OBJ}{OBJ})Z"); b.ifne(matched)
            b.goto(next_handler); b.mark(matched)
        else:
            self._expr(type_node, b, scope)
            type_slot = scope.temp(); b.astore(type_slot)
            b.aload(exc_slot); b.aload(type_slot)
            b.invokestatic(RUNTIME2, "exceptionMatchesValue", f"({OBJ}{OBJ})Z"); b.ifeq(next_handler)

    def _compare_chain(self, left: ast.expr, ops: list[ast.cmpop], comparators: list[ast.expr], b: CodeBuilder, scope: Scope) -> None:
        left_slot = scope.temp(); right_slot = scope.temp(); false = b.label(); end = b.label()
        self._expr(left, b, scope); b.astore(left_slot)
        for op, right in zip(ops, comparators):
            self._expr(right, b, scope); b.astore(right_slot)
            if isinstance(op, (ast.In, ast.NotIn)):
                b.aload(right_slot); b.aload(left_slot); b.invokestatic(RUNTIME, "contains", f"({OBJ}{OBJ}){OBJ}")
                if isinstance(op, ast.NotIn): b.invokestatic(RUNTIME, "not_", f"({OBJ}){OBJ}")
            else:
                b.aload(left_slot); b.aload(right_slot)
                name = {ast.Eq:"eq", ast.NotEq:"ne", ast.Lt:"lt", ast.LtE:"le", ast.Gt:"gt", ast.GtE:"ge", ast.Is:"is_", ast.IsNot:"is_not"}.get(type(op))
                if not name: raise CompileError(f"Unsupported comparison {type(op).__name__}")
                b.invokestatic(RUNTIME, name, f"({OBJ}{OBJ}){OBJ}")
            b.invokestatic(RUNTIME, "truth", f"({OBJ})Z"); b.ifeq(false)
            b.aload(right_slot); b.astore(left_slot)
        self._boxed_bool(True, b); b.goto(end); b.mark(false); self._boxed_bool(False, b); b.mark(end)

    def _truthy(self, node: ast.expr, b: CodeBuilder, scope: Scope) -> None:
        self._expr(node, b, scope)
        b.invokestatic(RUNTIME, "truth", f"({OBJ})Z")

    def _boxed_bool(self, value: bool, b: CodeBuilder) -> None:
        b.getstatic("java/lang/Boolean", "TRUE" if value else "FALSE", "Ljava/lang/Boolean;")

    def _binary_runtime(self, op: ast.operator, b: CodeBuilder, *, inplace: bool = False) -> None:
        name = {ast.Add:"add", ast.Sub:"sub", ast.Mult:"mul", ast.Div:"truediv", ast.FloorDiv:"floordiv", ast.Mod:"mod", ast.Pow:"pow", ast.BitAnd:"bitAnd", ast.BitOr:"bitOr", ast.BitXor:"bitXor", ast.LShift:"lshift", ast.RShift:"rshift", ast.MatMult:"matmul"}.get(type(op))
        if name is None: raise CompileError(f"Unsupported binary operator: {type(op).__name__}")
        if inplace and name in {"add", "mul", "pow", "sub", "bitAnd", "bitOr", "bitXor", "matmul"}: name = "i" + name
        b.invokestatic(RUNTIME2 if name in {"matmul", "imatmul"} else RUNTIME, name, f"({OBJ}{OBJ}){OBJ}")

    def _boolop(self, values: list[ast.expr], is_and: bool, b: CodeBuilder, scope: Scope) -> None:
        if not values:
            self._boxed_bool(is_and, b); return
        end = b.label(); self._expr(values[0], b, scope)
        for value in values[1:]:
            b.dup(); b.invokestatic(RUNTIME, "truth", f"({OBJ})Z")
            (b.ifeq if is_and else b.ifne)(end)
            b.pop(); self._expr(value, b, scope)
        b.mark(end)


# Compile-time errors CPython also reports as SyntaxError (everything else CompileError raises is an unsupported feature).
GENUINE_SYNTAX_ERRORS = ("break outside loop", "continue outside loop", "return outside function", "cannot assign to __debug__",
                       "'await' outside function", "'async for' outside async function", "'async with' outside async function",
                       "asynchronous comprehension outside")


def check_async_scopes(tree: ast.AST) -> None:
    """await / async for / async with are only legal inside an async function (module level needs PyCF_ALLOW_TOP_LEVEL_AWAIT)."""
    stack = [(tree, False)]
    while stack:
        node, in_async = stack.pop()
        if isinstance(node, ast.Await) and not in_async:
            raise CompileError("'await' outside function")
        if isinstance(node, ast.AsyncFor) and not in_async:
            raise CompileError("'async for' outside async function")
        if isinstance(node, ast.AsyncWith) and not in_async:
            raise CompileError("'async with' outside async function")
        if isinstance(node, ast.comprehension) and node.is_async and not in_async:
            raise CompileError("asynchronous comprehension outside of an asynchronous function")
        if isinstance(node, (ast.AsyncFunctionDef, ast.GeneratorExp)):   # an async generator expression may await anywhere
            inner = True
        elif isinstance(node, (ast.FunctionDef, ast.Lambda, ast.ClassDef)):
            inner = False
        else:
            inner = in_async
        for child in ast.iter_child_nodes(node):
            # annotation expressions are validated (with CPython's own messages) by the annotation lowering
            if child is getattr(node, "annotation", None) or child is getattr(node, "returns", None):
                continue
            stack.append((child, inner))


def strip_docstrings(tree: ast.AST) -> None:
    """optimize=2: module, class and function docstrings are removed."""
    for node in ast.walk(tree):
        if isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
            first = node.body[0] if node.body else None
            if isinstance(first, ast.Expr) and isinstance(first.value, ast.Constant) and isinstance(first.value.value, str):
                node.body = node.body[1:] or [ast.copy_location(ast.Pass(), first)]


def compile_source(source: str, class_name: str = "Main", filename: str = "<string>",
                   import_map: dict[str,str] | None = None,
                   import_exports: dict[str, list[str] | None] | None = None,
                   module_name: str = "__main__", package_name: str | None = None,
                   *, target: int = DEFAULT_TARGET, dynamic: bool = False, mode: str = "exec", trace: bool = False,
                   optimize: int = 0, tree: ast.AST | None = None,
                   feature_version_override: tuple[int, int] | None = None) -> bytes:
    try:
        return Compiler(class_name, import_map=import_map, import_exports=import_exports,
                        module_name=module_name, package_name=package_name, target=target,
                        dynamic=dynamic, mode=mode, trace=trace, optimize=optimize, tree=tree,
                        feature_version_override=feature_version_override).compile(source, filename)
    except CompileError as exc:
        if str(exc).startswith(GENUINE_SYNTAX_ERRORS):
            raise SyntaxError(str(exc)) from None
        raise
    except FrameError as exc:
        if "1..65535 bytes" in str(exc):
            raise CompileError("code exceeds the JVM's 64KB method limit: a function body, class body or single module-level "
                               "statement is too large (module-level statements are split into methods automatically); "
                               "split it into smaller pieces") from None
        raise


def _resolve_import_name(current_module: str, current_is_package: bool, module: str | None, level: int) -> str | None:
    if level == 0:
        return module
    package = current_module if current_is_package else current_module.rpartition('.')[0]
    parts=package.split('.') if package else []
    up=level-1
    if up > len(parts): return None
    base=parts[:len(parts)-up] if up else parts
    if module: base += module.split('.')
    return '.'.join(base)
