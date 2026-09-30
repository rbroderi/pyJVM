from __future__ import annotations

import ast
import copy
from dataclasses import dataclass
from pathlib import Path

from .classfile import ClassFile, CodeBuilder, Field, Method


OBJ = "Ljava/lang/Object;"
RUNTIME = "pyjvm315/runtime/PyRuntime"


class CompileError(SyntaxError):
    pass


@dataclass
class FunctionInfo:
    name: str
    java_name: str
    posonly: list[str]
    poskw: list[str]
    kwonly: list[str]
    vararg: str | None
    kwarg: str | None
    defaults: dict[str, ast.expr]
    kw_defaults: dict[str, ast.expr]
    function_field: str | None
    top_level: bool
    env_mode: bool
    local_names: set[str]
    free_names: set[str]
    nonlocal_names: set[str]
    is_generator: bool = False
    is_async: bool = False

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


@dataclass
class MethodInfo:
    py_name: str
    java_name: str
    posonly: list[str]
    poskw: list[str]
    kwonly: list[str]
    vararg: str | None
    kwarg: str | None
    defaults: dict[str, ast.expr]
    kw_defaults: dict[str, ast.expr]
    kind: str = "instance"
    firstlineno: int = 0
    is_async: bool = False

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
        return "(" + (OBJ * len(self.bound_args)) + ")" + OBJ


@dataclass
class PropertyInfo:
    name: str
    getter: MethodInfo
    setter: MethodInfo | None = None


@dataclass
class ClassInfo:
    name: str
    bases: list[str]
    methods: dict[str, MethodInfo]
    properties: dict[str, PropertyInfo]
    attrs: list[tuple[str, ast.expr]]
    class_field: str


@dataclass
class FinallyContext:
    finalbody: list[ast.stmt]
    return_slot: int
    return_entry: object
    branch_entries: dict[object, object]
    manager_slot: int | None = None
    async_manager: bool = False


class Scope:
    def __init__(self, initial: list[str] | None = None, start_slot: int = 0, *, module: bool = False,
                 parent: "Scope | None" = None, env_mode: bool = False, env_slot: int | None = None,
                 local_names: set[str] | None = None, free_names: set[str] | None = None,
                 nonlocal_names: set[str] | None = None) -> None:
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
    def __init__(self, class_name: str, import_map: dict[str, str] | None = None, *, module_name: str = "__main__", package_name: str | None = None) -> None:
        self.class_name = class_name.replace(".", "/")
        self.import_map = dict(import_map or {})
        self.module_name = module_name
        self.package_name = module_name.rpartition(".")[0] if package_name is None else package_name
        self.cf = ClassFile(self.class_name)
        self.functions: dict[str, FunctionInfo] = {}
        self.function_infos: dict[int, FunctionInfo] = {}
        self.function_nodes: list[ast.FunctionDef | ast.AsyncFunctionDef] = []
        self.classes: dict[str, ClassInfo] = {}
        self.class_method_infos: dict[int, MethodInfo] = {}
        self.current_class: ClassInfo | None = None
        self.current_method_self: str | None = None
        self.global_names: set[str] = set()
        self.global_fields: dict[str, str] = {}
        self._method_counter = 0
        self.loop_stack: list[tuple[object, object]] = []  # (continue, break)
        self.exception_stack: list[int] = []
        self.cleanup_stack: list[tuple[str, object]] = []
        self.finally_stack: list[FinallyContext] = []
        self.filename = "<string>"
        self.current_frame_name = "<module>"
        self.current_frame_firstlineno = 1

    def compile(self, source: str, filename: str = "<string>") -> bytes:
        self.filename = filename
        tree = ast.parse(source, filename=filename, mode="exec", feature_version=(3, 15))
        self._register_module_globals(tree.body)
        for stmt in tree.body:
            if isinstance(stmt, (ast.FunctionDef, ast.AsyncFunctionDef)):
                self._register_function_tree(stmt, [], top_level=True)
            elif isinstance(stmt, ast.ClassDef):
                self._register_class(stmt)

        self._add_constructor()
        for node in self.function_nodes:
            self._compile_function(node)
        for stmt in tree.body:
            if isinstance(stmt, ast.ClassDef):
                self._compile_class_methods(stmt)

        self._compile_main(tree.body)
        return self.cf.to_bytes()

    def _register_module_globals(self, body: list[ast.stmt]) -> None:
        names: set[str] = {"__name__", "__package__"}

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

    def _load_name(self, name: str, b: CodeBuilder, scope: Scope) -> None:
        if name in scope.global_decl:
            pass
        elif scope.env_mode and scope.env_slot is not None:
            if name in scope.local_names:
                b.aload(scope.env_slot); b.ldc_string(name)
                b.invokestatic(RUNTIME, "envGetLocal", f"({OBJ}{OBJ}){OBJ}")
                return
            if name in scope.free_names or name in scope.nonlocal_decl:
                b.aload(scope.env_slot); b.ldc_string(name)
                b.invokestatic(RUNTIME, "envGet", f"({OBJ}{OBJ}){OBJ}")
                return
        elif scope.has(name):
            b.aload(scope.get(name)); return
        if name in self.functions:
            info = self.functions[name]
            assert info.function_field is not None
            b.getstatic(self.class_name, info.function_field, OBJ); return
        if name in self.classes:
            b.getstatic(self.class_name, self.classes[name].class_field, OBJ); return
        if name in self.global_names:
            b.getstatic(self.class_name, self.global_fields[name], OBJ); return
        if name in {"object","int","bool","float","str","list","tuple","dict","set","range","type",
                    "BaseException","Exception","ArithmeticError","LookupError","ValueError","TypeError",
                    "ZeroDivisionError","OverflowError","IndexError","KeyError","AssertionError","RuntimeError",
                    "NameError","UnboundLocalError","AttributeError","StopIteration","StopAsyncIteration","GeneratorExit","OSError"}:
            b.ldc_string(name); b.invokestatic(RUNTIME, "builtinType", f"({OBJ}){OBJ}"); return
        raise CompileError(f"Name {name!r} referenced before assignment")

    def _store_name(self, name: str, b: CodeBuilder, scope: Scope, *, temp_scope: Scope | None = None) -> None:
        b.ldc_string(name); b.invokestatic(RUNTIME, "frameSetLocalValue", f"({OBJ}{OBJ}){OBJ}")
        if scope.module or name in scope.global_decl:
            if name not in self.global_fields:
                self.global_names.add(name)
                field_name = f"__py_global_{name}"
                self.global_fields[name] = field_name
                self.cf.add_field(Field(field_name))
            b.putstatic(self.class_name, self.global_fields[name], OBJ)
        elif scope.env_mode and scope.env_slot is not None:
            alloc_scope = temp_scope or scope
            value_slot = alloc_scope.temp(); b.astore(value_slot)
            b.aload(scope.env_slot); b.ldc_string(name); b.aload(value_slot)
            method = "envSetNonlocal" if name in scope.nonlocal_decl else "envSetLocal"
            b.invokestatic(RUNTIME, method, f"({OBJ}{OBJ}{OBJ})V")
        else:
            b.astore(scope.define(name))

    @staticmethod
    def _target_names(target: ast.AST) -> set[str]:
        out: set[str] = set()
        if isinstance(target, ast.Name): out.add(target.id)
        elif isinstance(target, (ast.Tuple, ast.List)):
            for elt in target.elts: out.update(Compiler._target_names(elt))
        elif isinstance(target, ast.Starred): out.update(Compiler._target_names(target.value))
        return out

    def _function_locals(self, node: ast.FunctionDef | ast.AsyncFunctionDef) -> tuple[set[str], set[str], set[str]]:
        locals_: set[str] = {a.arg for a in node.args.posonlyargs + node.args.args + node.args.kwonlyargs}
        if node.args.vararg: locals_.add(node.args.vararg.arg)
        if node.args.kwarg: locals_.add(node.args.kwarg.arg)
        globals_: set[str] = set(); nonlocals: set[str] = set()

        def visit_stmt(stmt: ast.stmt) -> None:
            if isinstance(stmt, ast.Global): globals_.update(stmt.names); return
            if isinstance(stmt, ast.Nonlocal): nonlocals.update(stmt.names); return
            if isinstance(stmt, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                locals_.add(stmt.name); return
            if isinstance(stmt, ast.Assign):
                for t in stmt.targets: locals_.update(self._target_names(t))
            elif isinstance(stmt, ast.AnnAssign): locals_.update(self._target_names(stmt.target))
            elif isinstance(stmt, ast.AugAssign): locals_.update(self._target_names(stmt.target))
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
                # nested body is a separate scope
            def visit_AsyncFunctionDef(self, n: ast.AsyncFunctionDef) -> None:
                if n is node:
                    for d in n.args.defaults: self.visit(d)
                    for d in n.args.kw_defaults:
                        if d is not None: self.visit(d)
                    for st in n.body: self.visit(st)
            def visit_ClassDef(self, n: ast.ClassDef) -> None: return
            def visit_Lambda(self, n: ast.Lambda) -> None: return
        Visitor().visit(node)
        return refs

    def _register_function_tree(self, node: ast.FunctionDef | ast.AsyncFunctionDef, enclosing_locals: list[set[str]], *, top_level: bool = False) -> None:
        local_names, global_names, nonlocal_names = self._function_locals(node)
        refs = self._referenced_names_shallow(node)
        free_names = {name for name in refs if name not in local_names and name not in global_names and any(name in s for s in reversed(enclosing_locals))}
        for name in nonlocal_names:
            if not any(name in s for s in reversed(enclosing_locals)):
                raise CompileError(f"no binding for nonlocal {name!r} found")
            free_names.add(name)
        has_nested = any(isinstance(x, (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda, ast.GeneratorExp)) for st in node.body for x in ast.walk(st) if x is not node)
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
            def visit_Lambda(self, n): return
        yf = YieldFinder(); yf.visit(node)
        is_generator = yf.found and isinstance(node, ast.FunctionDef)
        if isinstance(node, ast.AsyncFunctionDef) and yf.found:
            raise CompileError("async generators are not implemented yet")
        is_async = isinstance(node, ast.AsyncFunctionDef)
        env_mode = is_generator or is_async or bool(enclosing_locals) or has_nested or bool(nonlocal_names)

        posonly = [a.arg for a in node.args.posonlyargs]
        poskw = [a.arg for a in node.args.args]
        kwonly = [a.arg for a in node.args.kwonlyargs]
        positional = posonly + poskw
        defaults: dict[str, ast.expr] = {}
        if node.args.defaults:
            for name, expr in zip(positional[-len(node.args.defaults):], node.args.defaults): defaults[name] = expr
        kw_defaults = {a.arg: e for a, e in zip(node.args.kwonlyargs, node.args.kw_defaults) if e is not None}
        java_name = node.name if top_level else f"__py_nested_{self._method_counter}_{node.name}"
        if not top_level: self._method_counter += 1
        function_field = f"__py_function_{node.name}" if top_level else None
        if function_field: self.cf.add_field(Field(function_field))
        info = FunctionInfo(node.name, java_name, posonly, poskw, kwonly,
            node.args.vararg.arg if node.args.vararg else None,
            node.args.kwarg.arg if node.args.kwarg else None,
            defaults, kw_defaults, function_field, top_level, env_mode,
            local_names, free_names, nonlocal_names, is_generator, is_async)
        self.function_infos[id(node)] = info
        self.function_nodes.append(node)
        if top_level: self.functions[node.name] = info

        new_enclosing = enclosing_locals + [local_names]
        for stmt in node.body:
            if isinstance(stmt, (ast.FunctionDef, ast.AsyncFunctionDef)):
                self._register_function_tree(stmt, new_enclosing, top_level=False)


    def _register_class(self, node: ast.ClassDef) -> None:
        if node.keywords:
            for kw in node.keywords:
                if kw.arg != "metaclass" or not isinstance(kw.value, ast.Name) or kw.value.id != "type":
                    raise CompileError("custom metaclasses are not implemented yet")
        bases: list[str] = []
        for base in node.bases:
            if not isinstance(base, ast.Name):
                raise CompileError("class bases must currently be simple names")
            bases.append(base.id)
        methods: dict[str, MethodInfo] = {}
        properties: dict[str, PropertyInfo] = {}
        attrs: list[tuple[str, ast.expr]] = []
        for item in node.body:
            if isinstance(item, ast.Pass):
                continue
            if isinstance(item, ast.Assign) and len(item.targets) == 1 and isinstance(item.targets[0], ast.Name):
                attrs.append((item.targets[0].id, item.value))
                continue
            if isinstance(item, ast.AnnAssign) and isinstance(item.target, ast.Name) and item.value is not None:
                attrs.append((item.target.id, item.value))
                continue
            if not isinstance(item, (ast.FunctionDef, ast.AsyncFunctionDef)):
                raise CompileError("class bodies currently support methods and simple class attributes")
            posonly = [a.arg for a in item.args.posonlyargs]
            poskw = [a.arg for a in item.args.args]
            positional = posonly + poskw
            defaults: dict[str, ast.expr] = {}
            if item.args.defaults:
                for param, expr in zip(positional[-len(item.args.defaults):], item.args.defaults):
                    defaults[param] = expr
            kw_defaults = {a.arg: e for a, e in zip(item.args.kwonlyargs, item.args.kw_defaults) if e is not None}
            kind = "instance"
            if len(item.decorator_list) == 1 and isinstance(item.decorator_list[0], ast.Name) and item.decorator_list[0].id in {"classmethod", "staticmethod"}:
                kind = item.decorator_list[0].id.removesuffix("method")
            if kind != "static" and not positional:
                raise CompileError(f"method {item.name} must declare an implicit receiver argument")
            java_name = f"__py_method_{self._method_counter}"
            self._method_counter += 1
            method = MethodInfo(
                item.name, java_name, posonly, poskw, [a.arg for a in item.args.kwonlyargs],
                item.args.vararg.arg if item.args.vararg else None,
                item.args.kwarg.arg if item.args.kwarg else None,
                defaults, kw_defaults, kind, item.lineno, isinstance(item, ast.AsyncFunctionDef)
            )
            self.class_method_infos[id(item)] = method

            if not item.decorator_list or kind in {"class", "static"}:
                methods[f"{item.name}#{len(methods)}"] = method
                continue
            if len(item.decorator_list) == 1 and isinstance(item.decorator_list[0], ast.Name) and item.decorator_list[0].id == "property":
                properties[item.name] = PropertyInfo(item.name, method)
                continue
            if len(item.decorator_list) == 1 and isinstance(item.decorator_list[0], ast.Attribute):
                dec = item.decorator_list[0]
                if dec.attr == "setter" and isinstance(dec.value, ast.Name):
                    prop_name = dec.value.id
                    prop = properties.get(prop_name)
                    if prop is None:
                        raise CompileError(f"property setter {prop_name!r} appears before its property getter")
                    prop.setter = method
                    continue
            raise CompileError("method decorators currently support @property and @name.setter")
        class_field = f"__py_class_{node.name}"
        self.cf.add_field(Field(class_field))
        self.classes[node.name] = ClassInfo(node.name, bases, methods, properties, attrs, class_field)

    def _compile_class_methods(self, node: ast.ClassDef) -> None:
        info = self.classes[node.name]
        old_class, old_self = self.current_class, self.current_method_self
        self.current_class = info
        try:
            for item in node.body:
                if isinstance(item, (ast.FunctionDef, ast.AsyncFunctionDef)):
                    method = self.class_method_infos[id(item)]
                    b = CodeBuilder(self.cf.cp)
                    scope = Scope(method.bound_args, start_slot=0)
                    self.current_method_self = method.positional[0] if method.positional else None
                    saved_frame=(self.current_frame_name,self.current_frame_firstlineno)
                    self.current_frame_name,self.current_frame_firstlineno=item.name,item.lineno
                    self.loop_stack = []; self.exception_stack = []; self.cleanup_stack = []; self.finally_stack = []
                    for stmt in item.body:
                        self._stmt(stmt, b, scope, in_function=True)
                    self.current_frame_name,self.current_frame_firstlineno=saved_frame
                    b.aconst_null(); b.areturn()
                    code = b.finish()
                    self.cf.add_method(Method(method.java_name, method.descriptor, code, max_locals=max(8, scope.next_slot + 2), exception_table=b.exception_table))
        finally:
            self.current_class, self.current_method_self = old_class, old_self

    def _add_constructor(self) -> None:
        b = CodeBuilder(self.cf.cp)
        b.aload(0)
        b.invokespecial("java/lang/Object", "<init>", "()V")
        b.return_()
        code = b.finish()
        self.cf.add_method(Method("<init>", "()V", code, max_stack=2, max_locals=1, access=0x0001, exception_table=b.exception_table))

    def _compile_main(self, body: list[ast.stmt]) -> None:
        self.current_frame_name,self.current_frame_firstlineno="<module>",1
        b = CodeBuilder(self.cf.cp)
        scope = Scope(start_slot=1, module=True)
        self.loop_stack = []; self.exception_stack = []; self.cleanup_stack = []; self.finally_stack = []
        b.ldc_string("<module>"); b.ldc_string(self.filename); self._emit_int(1,b)
        b.invokestatic(RUNTIME,"pushLogicalFrame",f"({OBJ*3})V")
        b.ldc_string(self.module_name); self._store_name("__name__", b, scope)
        b.ldc_string(self.package_name); self._store_name("__package__", b, scope)
        for stmt in body:
            self._stmt(stmt, b, scope, in_function=False)
        b.invokestatic(RUNTIME,"popLogicalFrame",f"()V")
        b.return_()
        code = b.finish()
        self.cf.add_method(Method("main", "([Ljava/lang/String;)V", code, max_locals=max(8, scope.next_slot + 2), exception_table=b.exception_table))

    def _compile_function(self, node: ast.FunctionDef | ast.AsyncFunctionDef) -> None:
        info = self.function_infos[id(node)]
        saved_frame=(self.current_frame_name,self.current_frame_firstlineno)
        self.current_frame_name,self.current_frame_firstlineno=info.name,node.lineno
        if info.is_generator:
            self._compile_generator_function(node, info)
            self.current_frame_name,self.current_frame_firstlineno=saved_frame
            return
        if info.is_async and self._contains_await(node):
            lowered = self._lower_async_function(node)
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
            for name in info.bound_args:
                b.aload(env_slot); b.ldc_string(name); b.aload(scope.get(name))
                b.invokestatic(RUNTIME, "envSetLocal", f"({OBJ}{OBJ}{OBJ})V")
        self.loop_stack = []; self.exception_stack = []; self.cleanup_stack = []; self.finally_stack = []; self.cleanup_stack = []; self.finally_stack = []
        for stmt in node.body:
            self._stmt(stmt, b, scope, in_function=True)
        b.aconst_null(); b.areturn()
        code = b.finish()
        self.cf.add_method(Method(info.java_name, info.descriptor, code,
            max_locals=max(8, scope.next_slot + 2), exception_table=b.exception_table))
        self.current_frame_name,self.current_frame_firstlineno=saved_frame

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
        original = copy.deepcopy(node)

        class Lower(ast.NodeTransformer):
            def __init__(self, root):
                self.root=root
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
            def visit_Await(self, n):
                awaited=self.visit(n.value)
                call=ast.Call(func=ast.Name(id="__py_await_iter_internal",ctx=ast.Load()),args=[awaited],keywords=[])
                return ast.copy_location(ast.YieldFrom(value=call),n)

        lowered_async=Lower(original).visit(original)
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
            def visit_AsyncFunctionDef(self, n): return
            def visit_Lambda(self, n): return
        v=V()
        if isinstance(node, ast.FunctionDef):
            for st in node.body: v.visit(st)
        else:
            v.visit(node)
        return v.found

    def _generator_yields(self, node: ast.FunctionDef) -> list[ast.Yield | ast.YieldFrom]:
        out: list[ast.Yield | ast.YieldFrom] = []
        class V(ast.NodeVisitor):
            def visit_Yield(self, n): out.append(n)
            def visit_YieldFrom(self, n): out.append(n)
            def visit_FunctionDef(self, n):
                if n is node:
                    for st in n.body: self.visit(st)
            def visit_AsyncFunctionDef(self, n): return
            def visit_Lambda(self, n): return
        V().visit(node)
        return out

    def _compile_generator_function(self, node: ast.FunctionDef, info: FunctionInfo, *, as_coroutine: bool = False) -> None:
        # The callable body creates a persistent frame and returns immediately.
        b=CodeBuilder(self.cf.cp)
        scope=Scope(info.bound_args,start_slot=1,env_mode=True,local_names=info.local_names,
                    free_names=info.free_names,nonlocal_names=info.nonlocal_names)
        b.aload(0); b.invokestatic(RUNTIME,"envChild",f"({OBJ}){OBJ}")
        env_slot=scope.temp(); b.astore(env_slot); scope.env_slot=env_slot
        for name in info.bound_args:
            b.aload(env_slot); b.ldc_string(name); b.aload(scope.get(name))
            b.invokestatic(RUNTIME,"envSetLocal",f"({OBJ}{OBJ}{OBJ})V")
        resume_name=info.java_name+"$resume"
        b.ldc_string(self.class_name.replace('/','.')); b.ldc_string(resume_name); b.aload(env_slot)
        b.ldc_string(info.name); b.ldc_string(self.filename); self._emit_int(node.lineno,b)
        maker = "makeSuspendableCoroutineEx" if as_coroutine else "makeGeneratorEx"
        b.invokestatic(RUNTIME,maker,f"({OBJ*6}){OBJ}"); b.areturn()
        code=b.finish()
        self.cf.add_method(Method(info.java_name,info.descriptor,code,max_locals=max(8,scope.next_slot+2),exception_table=b.exception_table))
        self._compile_generator_resume(node, info, resume_name)

    def _gen_env_store(self, name: str, value_slot: int, b: CodeBuilder, scope: Scope) -> None:
        b.aload(scope.env_slot); b.ldc_string(name); b.aload(value_slot)
        b.invokestatic(RUNTIME,"envSetLocal",f"({OBJ}{OBJ}{OBJ})V")

    def _gen_env_load(self, name: str, b: CodeBuilder, scope: Scope) -> None:
        b.aload(scope.env_slot); b.ldc_string(name)
        b.invokestatic(RUNTIME,"envGet",f"({OBJ}{OBJ}){OBJ}")

    def _compile_generator_resume(self, node: ast.FunctionDef, info: FunctionInfo, resume_name: str) -> None:
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
        self.loop_stack=[]; self.exception_stack=[]; self.cleanup_stack=[]; self.finally_stack=[]
        gen_finally_stack: list[list[ast.stmt]] = []
        gen_active_exception_env: list[str] = []
        gen_finally_counter = [0]

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

        def emit_yield(y: ast.Yield):
            value_slot=scope.temp()
            if y.value is None: b.aconst_null()
            else: self._expr(y.value,b,scope)
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
            self._expr(y.value,b,scope); b.invokestatic(RUNTIME,"iter",f"({OBJ}){OBJ}")
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
            for handler in handlers:
                next_handler = b.label()
                if handler.type is not None:
                    if isinstance(handler.type, ast.Name):
                        b.aload(exc_slot); b.ldc_string(handler.type.id)
                        b.invokestatic(RUNTIME, "exceptionMatches", f"({OBJ}{OBJ})Z"); b.ifeq(next_handler)
                    elif isinstance(handler.type, ast.Tuple) and all(isinstance(x, ast.Name) for x in handler.type.elts):
                        matched = b.label()
                        for typ in handler.type.elts:
                            b.aload(exc_slot); b.ldc_string(typ.id)
                            b.invokestatic(RUNTIME, "exceptionMatches", f"({OBJ}{OBJ})Z"); b.ifne(matched)
                        b.goto(next_handler); b.mark(matched)
                    else:
                        raise CompileError("except types currently must be exception names or tuples of names")
                if handler.name:
                    b.aload(exc_slot); b.invokestatic(RUNTIME, "exceptionInstance", f"({OBJ}){OBJ}")
                    self._store_name(handler.name, b, scope)
                # Persist the active Throwable in the generator environment. JVM locals
                # do not survive a resume jump in a verifier-safe way, but Python's bare
                # ``raise`` must keep referring to the exception being handled even after
                # one or more yields.
                exc_env = f"$active_exc_{synthetic_counter[0]}"; synthetic_counter[0] += 1
                self._gen_env_store(exc_env, exc_slot, b, scope)
                self.exception_stack.append(exc_slot)
                gen_active_exception_env.append(exc_env)
                for x in handler.body: gen_stmt(x)
                gen_active_exception_env.pop()
                self.exception_stack.pop()
                b.goto(done)
                b.mark(next_handler)
            b.aload(exc_slot); b.athrow()
            b.mark(done)

        def gen_stmt(stmt: ast.stmt):
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
            if isinstance(stmt, ast.Raise) and stmt.exc is None:
                if gen_active_exception_env:
                    self._gen_env_load(gen_active_exception_env[-1], b, scope)
                    b.invokestatic(RUNTIME, "rethrowThrowable", f"({OBJ})V")
                    return
                if self.exception_stack:
                    b.aload(self.exception_stack[-1]); b.athrow(); return
                raise CompileError("No active exception to reraise")
            if isinstance(stmt,ast.Return):
                if stmt.value is None: b.aconst_null()
                else: self._expr(stmt.value,b,scope)
                ret=scope.temp(); b.astore(ret)
                if gen_finally_stack: emit_generator_cleanups()
                b.aload(gen_slot); b.aload(ret)
                b.invokestatic(RUNTIME,"generatorFinish",f"({OBJ}{OBJ}){OBJ}"); b.areturn(); return
            if isinstance(stmt,ast.Break):
                if not self.loop_stack: raise CompileError("break outside loop")
                target=self.loop_stack[-1][1]
                if gen_finally_stack: emit_generator_cleanups()
                b.goto(target); return
            if isinstance(stmt,ast.Continue):
                if not self.loop_stack: raise CompileError("continue outside loop")
                target=self.loop_stack[-1][0]
                if gen_finally_stack: emit_generator_cleanups()
                b.goto(target); return
            if isinstance(stmt,ast.For):
                syn=f"$gen_iter_{synthetic_counter[0]}"; synthetic_counter[0]+=1
                self._expr(stmt.iter,b,scope); b.invokestatic(RUNTIME,"iter",f"({OBJ}){OBJ}")
                it_slot=scope.temp(); b.astore(it_slot); self._gen_env_store(syn,it_slot,b,scope)
                start,normal_end,break_end=b.label(),b.label(),b.label(); b.mark(start)
                self._gen_env_load(syn,b,scope); b.invokestatic(RUNTIME,"iterHasNext",f"({OBJ})Z"); b.ifeq(normal_end)
                self._gen_env_load(syn,b,scope); b.invokestatic(RUNTIME,"iterNext",f"({OBJ}){OBJ}"); self._store_target(stmt.target,b,scope)
                self.loop_stack.append((start,break_end))
                for x in stmt.body: gen_stmt(x)
                self.loop_stack.pop(); b.goto(start); b.mark(normal_end)
                for x in stmt.orelse: gen_stmt(x)
                b.mark(break_end); return
            if isinstance(stmt,ast.If):
                els,end=b.label(),b.label(); self._truthy(stmt.test,b,scope); b.ifeq(els)
                for x in stmt.body: gen_stmt(x)
                b.goto(end); b.mark(els)
                for x in stmt.orelse: gen_stmt(x)
                b.mark(end); return
            if isinstance(stmt,ast.While):
                start,normal_end,break_end=b.label(),b.label(),b.label(); b.mark(start); self._truthy(stmt.test,b,scope); b.ifeq(normal_end)
                self.loop_stack.append((start,break_end))
                for x in stmt.body: gen_stmt(x)
                self.loop_stack.pop(); b.goto(start); b.mark(normal_end)
                for x in stmt.orelse: gen_stmt(x)
                b.mark(break_end); return
            if isinstance(stmt, ast.With) and self._contains_yield(stmt):
                if self._has_abrupt_control(stmt.body):
                    raise CompileError("return/break/continue through a yielding generator with is not implemented yet")

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
                    b.mark(start)
                    gen_with(rest, body)
                    b.mark(protected_end)
                    self._gen_env_load(mgr_name, b, scope); b.invokestatic(RUNTIME, "withExitNormal", f"({OBJ})V")
                    b.goto(done)
                    b.mark(handler); b.astore(exc_slot)
                    self._gen_env_load(mgr_name, b, scope); b.aload(exc_slot)
                    b.invokestatic(RUNTIME, "withExitException", f"({OBJ}{OBJ})Z")
                    b.ifne(done); b.aload(exc_slot); b.athrow()
                    b.add_exception_handler(start, protected_end, handler, "java/lang/Throwable")
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
                        # A yielding finally suite is compiled exactly once. Both normal
                        # completion and an exceptional exit route through that shared
                        # state-machine region. The pending Throwable is persisted in the
                        # generator environment so it survives any yields in finally.
                        # Abrupt return/break/continue from the protected body needs a
                        # separate persisted continuation and remains a narrow explicit
                        # limitation for this form.
                        abrupt = any(
                            isinstance(n, (ast.Return, ast.Break, ast.Continue))
                            for x in (stmt.body + stmt.orelse) for n in ast.walk(x)
                            if not isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda))
                        )
                        if abrupt:
                            raise CompileError("return/break/continue through a yielding generator finally is not implemented yet")
                        idx = gen_finally_counter[0]; gen_finally_counter[0] += 1
                        exc_env = f"$finally_exc_{idx}"
                        outer_start, outer_end = b.label(), b.label()
                        outer_handler, final_entry, outer_done = b.label(), b.label(), b.label()
                        outer_exc = scope.temp()
                        b.mark(outer_start)
                        if stmt.handlers:
                            gen_try_except(stmt.body, stmt.handlers, stmt.orelse)
                        else:
                            for x in stmt.body: gen_stmt(x)
                        b.mark(outer_end)
                        b.aconst_null(); normal_slot = scope.temp(); b.astore(normal_slot)
                        self._gen_env_store(exc_env, normal_slot, b, scope)
                        b.goto(final_entry)
                        b.mark(outer_handler); b.astore(outer_exc)
                        self._gen_env_store(exc_env, outer_exc, b, scope)
                        b.goto(final_entry)
                        b.add_exception_handler(outer_start, outer_end, outer_handler, "java/lang/Throwable")
                        b.mark(final_entry)
                        for x in stmt.finalbody: gen_stmt(x)
                        self._gen_env_load(exc_env, b, scope)
                        pending_slot = scope.temp(); b.astore(pending_slot)
                        b.aload(pending_slot); b.invokestatic(RUNTIME, "truth", f"({OBJ})Z"); b.ifeq(outer_done)
                        b.aload(pending_slot); b.invokestatic(RUNTIME, "rethrowThrowable", f"({OBJ})V")
                        b.mark(outer_done)
                    else:
                        # Compile try/except as the protected body of an outer finally.
                        outer_start, outer_end, outer_handler, outer_done = b.label(), b.label(), b.label(), b.label()
                        outer_exc = scope.temp()
                        b.mark(outer_start)
                        gen_finally_stack.append(stmt.finalbody)
                        if stmt.handlers:
                            gen_try_except(stmt.body, stmt.handlers, stmt.orelse)
                        else:
                            for x in stmt.body: gen_stmt(x)
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
            self._expr(target.value, b, scope); b.ldc_string(target.attr); b.aload(tmp)
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

    def _emit_function_definition(self, node: ast.FunctionDef | ast.AsyncFunctionDef, b: CodeBuilder, scope: Scope) -> None:
        info = self.function_infos[id(node)]
        # Python evaluates decorator expressions in source order in the enclosing
        # scope, then applies them from the bottom upward to the function object.
        decorator_slots: list[int] = []
        for decorator in node.decorator_list:
            self._expr(decorator, b, scope)
            slot = scope.temp(); b.astore(slot); decorator_slots.append(slot)

        # Defaults are evaluated exactly once at execution of this def statement and retained by the function object.
        b.invokestatic(RUNTIME, "dict0", f"(){OBJ}")
        defaults_slot = scope.temp(); b.astore(defaults_slot)
        for param, expr in [*info.defaults.items(), *info.kw_defaults.items()]:
            b.aload(defaults_slot); b.ldc_string(param); self._expr(expr, b, scope)
            b.invokestatic(RUNTIME, "dictPut", f"({OBJ}{OBJ}{OBJ})V")

        b.ldc_string(self.class_name.replace('/', '.'))
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
        if info.is_async:
            b.dup(); b.invokestatic(RUNTIME, "setFunctionAsync", f"({OBJ})V")

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

        if info.top_level:
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
        free_names={name for name in refs if name not in local_names and scope.has(name) and name not in scope.global_decl}
        java_name=f"__py_lambda_{self._method_counter}"; self._method_counter += 1
        info=FunctionInfo("<lambda>", java_name, posonly,poskw,kwonly,
            args_obj.vararg.arg if args_obj.vararg else None,
            args_obj.kwarg.arg if args_obj.kwarg else None,
            defaults,kw_defaults,None,False,bool(scope.env_mode),local_names,free_names,set(),False,False)
        self.function_infos[id(node)]=info
        self._compile_lambda_method(node, info)
        return info

    def _compile_lambda_method(self, node: ast.Lambda, info: FunctionInfo) -> None:
        saved_frame=(self.current_frame_name,self.current_frame_firstlineno)
        self.current_frame_name,self.current_frame_firstlineno="<lambda>",node.lineno
        b=CodeBuilder(self.cf.cp); arg_start=1 if info.env_mode else 0
        scope=Scope(info.bound_args,start_slot=arg_start,env_mode=info.env_mode,
                    local_names=info.local_names,free_names=info.free_names)
        if info.env_mode:
            b.aload(0); b.invokestatic(RUNTIME,"envChild",f"({OBJ}){OBJ}")
            env_slot=scope.temp(); b.astore(env_slot); scope.env_slot=env_slot
            for name in info.bound_args:
                b.aload(env_slot); b.ldc_string(name); b.aload(scope.get(name)); b.invokestatic(RUNTIME,"envSetLocal",f"({OBJ}{OBJ}{OBJ})V")
        saved_loop,saved_exc,saved_finally=self.loop_stack,self.exception_stack,self.finally_stack
        self.loop_stack=[]; self.exception_stack=[]; self.finally_stack=[]
        self._expr(node.body,b,scope); b.areturn()
        self.loop_stack,self.exception_stack,self.finally_stack=saved_loop,saved_exc,saved_finally
        code=b.finish(); self.cf.add_method(Method(info.java_name,info.descriptor,code,max_locals=max(8,scope.next_slot+2),exception_table=b.exception_table))
        self.current_frame_name,self.current_frame_firstlineno=saved_frame

    def _emit_lambda(self, node: ast.Lambda, b: CodeBuilder, scope: Scope) -> None:
        info=self._lambda_info(node,scope)
        b.invokestatic(RUNTIME,"dict0",f"(){OBJ}"); defaults_slot=scope.temp(); b.astore(defaults_slot)
        for param,expr in [*info.defaults.items(),*info.kw_defaults.items()]:
            b.aload(defaults_slot); b.ldc_string(param); self._expr(expr,b,scope); b.invokestatic(RUNTIME,"dictPut",f"({OBJ}{OBJ}{OBJ})V")
        b.ldc_string(self.class_name.replace('/','.')); b.ldc_string(info.java_name)
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
        if any(gen.is_async for gen in node.generators):
            raise CompileError("async generator expressions are not implemented yet")
        # Python evaluates the outermost iterable expression immediately when the
        # generator expression is created. The actual iteration remains lazy.
        outer_name=f"$genexpr_outer_{self._method_counter}"
        java_name=f"__py_genexpr_{self._method_counter}"; self._method_counter += 1
        target_names: set[str] = {outer_name}
        for gen in node.generators: target_names.update(self._target_names(gen.target))
        refs={n.id for n in ast.walk(node) if isinstance(n,ast.Name) and isinstance(n.ctx,ast.Load)}
        free_names={name for name in refs if name not in target_names and scope.has(name) and name not in scope.global_decl}
        info=FunctionInfo("<genexpr>",java_name,[],[],[],None,None,{}, {},None,False,True,
                          target_names,free_names,set(),True,False)

        body: list[ast.stmt]=[ast.Expr(value=ast.Yield(value=node.elt))]
        generators=list(node.generators)
        for index in range(len(generators)-1,-1,-1):
            gen=generators[index]
            inner=body
            for cond in reversed(gen.ifs): inner=[ast.If(test=cond,body=inner,orelse=[])]
            iterable=ast.Name(id=outer_name,ctx=ast.Load()) if index==0 else gen.iter
            body=[ast.For(target=gen.target,iter=iterable,body=inner,orelse=[])]
        synthetic=ast.FunctionDef(name="<genexpr>",args=ast.arguments(posonlyargs=[],args=[],kwonlyargs=[],kw_defaults=[],defaults=[]),
                                  body=body,decorator_list=[])
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
        b.ldc_string(self.class_name.replace('/','.')); b.ldc_string(resume_name); b.aload(env_slot)
        b.invokestatic(RUNTIME,"makeGenerator",f"({OBJ}{OBJ}{OBJ}){OBJ}")

    def _emit_comprehension(self, node: ast.ListComp | ast.SetComp | ast.DictComp, b: CodeBuilder, scope: Scope) -> None:
        if any(gen.is_async for gen in node.generators):
            raise CompileError("async comprehensions are not implemented yet")
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
        child = Scope(start_slot=scope.next_slot, parent=scope)
        child.comprehension_outer = scope

        def emit_generator(index: int) -> None:
            gen = node.generators[index]
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
        ctx = FinallyContext(finalbody, return_slot, return_entry, {})
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
        self._emit_context_cleanup(ctx, b, scope, in_function)
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
        ctx = FinallyContext([], return_slot, return_entry, {}, manager_slot=manager_slot, async_manager=is_async)
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

    def _stmt(self, node: ast.stmt, b: CodeBuilder, scope: Scope, in_function: bool) -> None:
        if hasattr(node, "lineno"):
            self._emit_int(node.lineno, b); b.invokestatic(RUNTIME,"setCurrentLine",f"({OBJ})V")
        match node:
            case ast.Pass():
                return
            case ast.Expr(value=value):
                self._expr(value, b, scope)
                b.pop()
            case ast.Assign(targets=targets, value=value):
                self._expr(value, b, scope)
                if len(targets) == 1:
                    self._store_target(targets[0], b, scope)
                else:
                    tmp = scope.temp(); b.astore(tmp)
                    for target in targets:
                        b.aload(tmp); self._store_target(target, b, scope)
            case ast.Assign(targets=[ast.Subscript(value=obj, slice=key)], value=value):
                self._expr(obj, b, scope)
                self._expr(key, b, scope)
                self._expr(value, b, scope)
                b.invokestatic(RUNTIME, "setitem", f"({OBJ}{OBJ}{OBJ})V")
            case ast.Assign(targets=[ast.Attribute(value=obj, attr=attr)], value=value):
                self._expr(obj, b, scope); b.ldc_string(attr); self._expr(value, b, scope)
                b.invokestatic(RUNTIME, "setattr", f"({OBJ}{OBJ}{OBJ})V")
            case ast.FunctionDef() | ast.AsyncFunctionDef():
                self._emit_function_definition(node, b, scope)
            case ast.Import(names=names):
                for alias in names:
                    module_name = alias.name
                    jvm_name = self.import_map.get(module_name)
                    if jvm_name is None:
                        raise CompileError(f"import of module {module_name!r} is not available")
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
            case ast.ImportFrom(module=module, names=names, level=level):
                resolved = module
                if level:
                    parts = self.package_name.split('.') if self.package_name else []
                    up = level - 1
                    if up > len(parts):
                        raise CompileError("attempted relative import beyond top-level package")
                    base = parts[:len(parts)-up] if up else parts
                    if module:
                        base += module.split('.')
                    resolved = '.'.join(base)
                if not resolved:
                    raise CompileError("relative import requires a resolvable package")
                jvm_name = self.import_map.get(resolved)
                if jvm_name is None:
                    raise CompileError(f"import from module {resolved!r} is not available")
                b.ldc_string(resolved); b.ldc_string(jvm_name.replace('/', '.'))
                b.invokestatic(RUNTIME, "importModule", f"({OBJ}{OBJ}){OBJ}")
                module_slot = scope.temp(); b.astore(module_slot)
                for alias in names:
                    if alias.name == '*': raise CompileError("star imports are not implemented yet")
                    submodule = resolved + '.' + alias.name
                    sub_jvm = self.import_map.get(submodule)
                    if sub_jvm is not None:
                        b.ldc_string(submodule); b.ldc_string(sub_jvm.replace('/', '.'))
                        b.invokestatic(RUNTIME, "importModule", f"({OBJ}{OBJ}){OBJ}")
                    else:
                        b.aload(module_slot); b.ldc_string(alias.name)
                        b.invokestatic(RUNTIME, "moduleGetattr", f"({OBJ}{OBJ}){OBJ}")
                    self._store_name(alias.asname or alias.name, b, scope)
            case ast.ClassDef(name=name, decorator_list=decorators):
                info = self.classes[name]
                decorator_slots: list[int] = []
                for decorator in decorators:
                    self._expr(decorator, b, scope)
                    slot = scope.temp(); b.astore(slot); decorator_slots.append(slot)
                b.ldc_string(name)
                b.invokestatic(RUNTIME, "class0", f"({OBJ}){OBJ}")
                class_slot = scope.temp(); b.astore(class_slot)
                for base_name in info.bases:
                    b.aload(class_slot); self._load_name(base_name, b, scope)
                    b.invokestatic(RUNTIME, "classAddBase", f"({OBJ}{OBJ})V")
                for attr_name, attr_value in info.attrs:
                    b.aload(class_slot); b.ldc_string(attr_name); self._expr(attr_value, b, scope)
                    b.invokestatic(RUNTIME, "classAddAttr", f"({OBJ}{OBJ}{OBJ})V")
                for method in info.methods.values():
                    b.invokestatic(RUNTIME, "dict0", f"(){OBJ}")
                    defaults_slot = scope.temp(); b.astore(defaults_slot)
                    for param, expr in [*method.defaults.items(), *method.kw_defaults.items()]:
                        b.aload(defaults_slot); b.ldc_string(param); self._expr(expr, b, scope)
                        b.invokestatic(RUNTIME, "dictPut", f"({OBJ}{OBJ}{OBJ})V")
                    b.aload(class_slot); b.ldc_string(method.py_name); b.ldc_string(self.class_name.replace('/', '.')); b.ldc_string(method.java_name); b.ldc_string(method.kind)
                    b.ldc_string(",".join(method.posonly)); b.ldc_string(",".join(method.poskw)); b.ldc_string(",".join(method.kwonly))
                    if method.vararg is None: b.aconst_null()
                    else: b.ldc_string(method.vararg)
                    if method.kwarg is None: b.aconst_null()
                    else: b.ldc_string(method.kwarg)
                    b.aload(defaults_slot); b.ldc_string(self.filename); self._emit_int(method.firstlineno, b)
                    b.invokestatic(RUNTIME, "classAddMethodEx", f"({OBJ * 13})V")
                    if method.is_async:
                        b.aload(class_slot); b.ldc_string(method.py_name)
                        b.invokestatic(RUNTIME, "classSetMethodAsync", f"({OBJ}{OBJ})V")
                for prop in info.properties.values():
                    b.aload(class_slot); b.ldc_string(prop.name); b.ldc_string(self.class_name.replace('/', '.')); b.ldc_string(prop.getter.java_name)
                    if prop.setter is None: b.aconst_null()
                    else: b.ldc_string(prop.setter.java_name)
                    b.invokestatic(RUNTIME, "classAddProperty", f"({OBJ}{OBJ}{OBJ}{OBJ}{OBJ})V")
                b.aload(class_slot); b.invokestatic(RUNTIME, "classFinalize", f"({OBJ})V")
                for decorator_slot in reversed(decorator_slots):
                    b.aload(decorator_slot)
                    b.invokestatic(RUNTIME, "list0", f"(){OBJ}")
                    b.dup(); b.aload(class_slot); b.invokestatic(RUNTIME, "listAppend", f"({OBJ}{OBJ})V")
                    b.invokestatic(RUNTIME, "dict0", f"(){OBJ}")
                    b.invokestatic(RUNTIME, "callFunction", f"({OBJ}{OBJ}{OBJ}){OBJ}")
                    b.astore(class_slot)
                b.aload(class_slot); b.putstatic(self.class_name, info.class_field, OBJ)
            case ast.AnnAssign(target=ast.Name(id=name), value=value):
                if value is None: b.aconst_null()
                else: self._expr(value, b, scope)
                self._store_name(name, b, scope)
            case ast.AugAssign(target=ast.Name(id=name), op=op, value=value):
                self._load_name(name, b, scope)
                self._expr(value, b, scope)
                self._binary_runtime(op, b)
                self._store_name(name, b, scope)
            case ast.AugAssign(target=ast.Attribute(value=obj, attr=attr), op=op, value=value):
                obj_slot, result_slot = scope.temp(), scope.temp()
                self._expr(obj, b, scope); b.astore(obj_slot)
                b.aload(obj_slot); b.ldc_string(attr); b.invokestatic(RUNTIME, "getattr", f"({OBJ}{OBJ}){OBJ}")
                self._expr(value, b, scope); self._binary_runtime(op, b); b.astore(result_slot)
                b.aload(obj_slot); b.ldc_string(attr); b.aload(result_slot)
                b.invokestatic(RUNTIME, "setattr", f"({OBJ}{OBJ}{OBJ})V")
            case ast.AugAssign(target=ast.Subscript(value=obj, slice=key), op=op, value=value):
                obj_slot, key_slot, result_slot = scope.temp(), scope.temp(), scope.temp()
                self._expr(obj, b, scope); b.astore(obj_slot)
                self._expr(key, b, scope); b.astore(key_slot)
                b.aload(obj_slot); b.aload(key_slot); b.invokestatic(RUNTIME, "getitem", f"({OBJ}{OBJ}){OBJ}")
                self._expr(value, b, scope); self._binary_runtime(op, b); b.astore(result_slot)
                b.aload(obj_slot); b.aload(key_slot); b.aload(result_slot)
                b.invokestatic(RUNTIME, "setitem", f"({OBJ}{OBJ}{OBJ})V")
            case ast.Global(names=names):
                scope.global_decl.update(names)
            case ast.Nonlocal(names=names):
                scope.nonlocal_decl.update(names)
            case ast.Return(value=value):
                if not in_function: raise CompileError("return outside function")
                if value is None: b.aconst_null()
                else: self._expr(value, b, scope)
                if self.finally_stack:
                    ctx = self.finally_stack[-1]; b.astore(ctx.return_slot); b.goto(ctx.return_entry)
                else:
                    b.areturn()
            case ast.If(test=test, body=body, orelse=orelse):
                else_label, end_label = b.label(), b.label()
                self._truthy(test, b, scope)
                b.ifeq(else_label)
                for s in body: self._stmt(s, b, scope, in_function)
                b.goto(end_label)
                b.mark(else_label)
                for s in orelse: self._stmt(s, b, scope, in_function)
                b.mark(end_label)
            case ast.While(test=test, body=body, orelse=orelse):
                start, normal_end, break_end = b.label(), b.label(), b.label()
                b.mark(start)
                self._truthy(test, b, scope); b.ifeq(normal_end)
                self.loop_stack.append((start, break_end))
                for s in body: self._stmt(s, b, scope, in_function)
                self.loop_stack.pop(); b.goto(start)
                b.mark(normal_end)
                for s in orelse: self._stmt(s, b, scope, in_function)
                b.mark(break_end)
            case ast.For(target=target, iter=iterable, body=body, orelse=orelse):
                iterator_slot = scope.temp()
                self._expr(iterable, b, scope); b.invokestatic(RUNTIME, "iter", f"({OBJ}){OBJ}"); b.astore(iterator_slot)
                start, normal_end, break_end = b.label(), b.label(), b.label()
                b.mark(start); b.aload(iterator_slot); b.invokestatic(RUNTIME, "iterHasNext", f"({OBJ})Z"); b.ifeq(normal_end)
                b.aload(iterator_slot); b.invokestatic(RUNTIME, "iterNext", f"({OBJ}){OBJ}"); self._store_target(target, b, scope)
                self.loop_stack.append((start, break_end))
                for s in body: self._stmt(s, b, scope, in_function)
                self.loop_stack.pop(); b.goto(start)
                b.mark(normal_end)
                for s in orelse: self._stmt(s, b, scope, in_function)
                b.mark(break_end)
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
            case ast.Break():
                if not self.loop_stack: raise CompileError("break outside loop")
                target = self.loop_stack[-1][1]
                if self.finally_stack: b.goto(self._branch_cleanup_entry(self.finally_stack[-1], target, b))
                else: b.goto(target)
            case ast.Continue():
                if not self.loop_stack: raise CompileError("continue outside loop")
                target = self.loop_stack[-1][0]
                if self.finally_stack: b.goto(self._branch_cleanup_entry(self.finally_stack[-1], target, b))
                else: b.goto(target)
            case ast.Delete(targets=targets):
                for target in targets:
                    if isinstance(target, ast.Subscript):
                        self._expr(target.value, b, scope); self._expr(target.slice, b, scope)
                        b.invokestatic(RUNTIME, "delitem", f"({OBJ}{OBJ})V")
                    elif isinstance(target, ast.Attribute):
                        self._expr(target.value, b, scope); b.ldc_string(target.attr)
                        b.invokestatic(RUNTIME, "delattr", f"({OBJ}{OBJ})V")
                    else:
                        raise CompileError("del currently supports attributes and subscripts")
            case ast.Assert(test=test, msg=msg):
                ok = b.label(); self._truthy(test, b, scope); b.ifne(ok)
                if msg is None: b.aconst_null()
                else: self._expr(msg, b, scope)
                b.invokestatic(RUNTIME, "assertFail", f"({OBJ})V")
                b.mark(ok)
            case ast.Raise(exc=exc, cause=cause):
                if exc is None:
                    if not self.exception_stack: raise CompileError("No active exception to reraise")
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
                        b.invokestatic(RUNTIME, "raiseObjectFrom", f"({OBJ}{OBJ})V")
            case ast.Try(body=body, handlers=handlers, orelse=orelse, finalbody=[]):
                self._compile_try_except(body, handlers, orelse, b, scope, in_function)
            case ast.Try(body=body, handlers=handlers, orelse=orelse, finalbody=finalbody):
                self._compile_try_finally(body, handlers, orelse, finalbody, b, scope, in_function)
            case ast.With(items=items, body=body):
                self._compile_with(items, body, b, scope, in_function)
            case ast.AsyncWith(items=items, body=body):
                self._compile_with(items, body, b, scope, in_function, is_async=True)
            case _:
                raise CompileError(f"Unsupported statement: {type(node).__name__} at line {getattr(node, 'lineno', '?')}")

    def _expr(self, node: ast.expr, b: CodeBuilder, scope: Scope) -> None:
        match node:
            case ast.Constant(value=None):
                b.aconst_null()
            case ast.Constant(value=True): self._boxed_bool(True, b)
            case ast.Constant(value=False): self._boxed_bool(False, b)
            case ast.Constant(value=int() as value) if not isinstance(value, bool):
                self._emit_int(value, b)
            case ast.Constant(value=float() as value):
                b.ldc_string(repr(value))
                b.invokestatic("java/lang/Double", "valueOf", "(Ljava/lang/String;)Ljava/lang/Double;")
            case ast.Constant(value=str() as value):
                b.ldc_string(value)
            case ast.Name(id=name):
                self._load_name(name, b, scope)
            case ast.NamedExpr(target=ast.Name(id=name), value=value):
                self._expr(value, b, scope); b.dup()
                target_scope = getattr(scope, "comprehension_outer", None) or scope
                self._store_name(name, b, target_scope, temp_scope=scope)
            case ast.Lambda():
                self._emit_lambda(node, b, scope)
            case ast.ListComp() | ast.SetComp() | ast.DictComp():
                self._emit_comprehension(node, b, scope)
            case ast.GeneratorExp():
                self._emit_generator_expression(node, b, scope)
            case ast.Await(value=value):
                self._expr(value, b, scope)
                b.invokestatic(RUNTIME, "awaitValue", f"({OBJ}){OBJ}")
            case ast.List(elts=elts):
                b.invokestatic(RUNTIME, "list0", f"(){OBJ}")
                for elt in elts:
                    b.dup()
                    if isinstance(elt, ast.Starred):
                        self._expr(elt.value, b, scope); b.invokestatic(RUNTIME, "listExtend", f"({OBJ}{OBJ})V")
                    else:
                        self._expr(elt, b, scope); b.invokestatic(RUNTIME, "listAppend", f"({OBJ}{OBJ})V")
            case ast.Tuple(elts=elts):
                b.invokestatic(RUNTIME, "tuple0", f"(){OBJ}")
                for elt in elts:
                    b.dup()
                    if isinstance(elt, ast.Starred):
                        self._expr(elt.value, b, scope); b.invokestatic(RUNTIME, "tupleExtend", f"({OBJ}{OBJ})V")
                    else:
                        self._expr(elt, b, scope); b.invokestatic(RUNTIME, "tupleAppend", f"({OBJ}{OBJ})V")
            case ast.Set(elts=elts):
                b.invokestatic(RUNTIME, "set0", f"(){OBJ}")
                for elt in elts:
                    b.dup()
                    if isinstance(elt, ast.Starred):
                        self._expr(elt.value, b, scope); b.invokestatic(RUNTIME, "setUpdate", f"({OBJ}{OBJ})V")
                    else:
                        self._expr(elt, b, scope); b.invokestatic(RUNTIME, "setAdd", f"({OBJ}{OBJ})V")
            case ast.Dict(keys=keys, values=values):
                b.invokestatic(RUNTIME, "dict0", f"(){OBJ}")
                for key, value in zip(keys, values):
                    b.dup()
                    if key is None:
                        self._expr(value, b, scope); b.invokestatic(RUNTIME, "dictUpdate", f"({OBJ}{OBJ})V")
                    else:
                        self._expr(key, b, scope); self._expr(value, b, scope)
                        b.invokestatic(RUNTIME, "dictPut", f"({OBJ}{OBJ}{OBJ})V")
            case ast.Subscript(value=value, slice=key):
                self._expr(value, b, scope); self._expr(key, b, scope)
                b.invokestatic(RUNTIME, "getitem", f"({OBJ}{OBJ}){OBJ}")
            case ast.Attribute(value=value, attr=attr):
                self._expr(value, b, scope); b.ldc_string(attr)
                b.invokestatic(RUNTIME, "getattr", f"({OBJ}{OBJ}){OBJ}")
            case ast.Slice(lower=lower, upper=upper, step=step):
                (b.aconst_null() if lower is None else self._expr(lower, b, scope))
                (b.aconst_null() if upper is None else self._expr(upper, b, scope))
                (b.aconst_null() if step is None else self._expr(step, b, scope))
                b.invokestatic(RUNTIME, "slice", f"({OBJ}{OBJ}{OBJ}){OBJ}")
            case ast.IfExp(test=test, body=body, orelse=orelse):
                no, end = b.label(), b.label(); self._truthy(test, b, scope); b.ifeq(no)
                self._expr(body, b, scope); b.goto(end); b.mark(no); self._expr(orelse, b, scope); b.mark(end)
            case ast.JoinedStr(values=values):
                b.ldc_string("")
                for value in values:
                    if isinstance(value, ast.Constant): self._expr(value, b, scope)
                    elif isinstance(value, ast.FormattedValue):
                        self._expr(value.value, b, scope)
                        method = "repr_" if value.conversion == ord('r') else "str_"
                        b.invokestatic(RUNTIME, method, f"({OBJ}){OBJ}")
                    else: raise CompileError("unsupported f-string component")
                    b.invokestatic(RUNTIME, "add", f"({OBJ}{OBJ}){OBJ}")
            case ast.BinOp(left=left, op=op, right=right):
                self._expr(left, b, scope); self._expr(right, b, scope); self._binary_runtime(op, b)
            case ast.UnaryOp(op=ast.USub(), operand=operand):
                self._expr(operand, b, scope); b.invokestatic(RUNTIME, "neg", f"({OBJ}){OBJ}")
            case ast.UnaryOp(op=ast.Not(), operand=operand):
                self._expr(operand, b, scope); b.invokestatic(RUNTIME, "not_", f"({OBJ}){OBJ}")
            case ast.UnaryOp(op=ast.UAdd(), operand=operand):
                self._expr(operand, b, scope); b.invokestatic(RUNTIME, "pos", f"({OBJ}){OBJ}")
            case ast.UnaryOp(op=ast.Invert(), operand=operand):
                self._expr(operand, b, scope); b.invokestatic(RUNTIME, "invert", f"({OBJ}){OBJ}")
            case ast.BoolOp(op=ast.And(), values=values): self._boolop(values, True, b, scope)
            case ast.BoolOp(op=ast.Or(), values=values): self._boolop(values, False, b, scope)
            case ast.Compare(left=left, ops=ops, comparators=comparators):
                self._compare_chain(left, ops, comparators, b, scope)
            case ast.Call(func=ast.Name(id="super"), args=[], keywords=[]):
                if self.current_class is None or self.current_method_self is None:
                    raise CompileError("zero-argument super() is only available inside compiled methods")
                b.getstatic(self.class_name, self.current_class.class_field, OBJ)
                self._load_name(self.current_method_self, b, scope)
                b.invokestatic(RUNTIME, "makeSuper", f"({OBJ}{OBJ}){OBJ}")
            case ast.Call(func=ast.Name(id="super"), args=[cls, obj], keywords=[]):
                self._expr(cls, b, scope); self._expr(obj, b, scope)
                b.invokestatic(RUNTIME, "makeSuper", f"({OBJ}{OBJ}){OBJ}")
            case ast.Call(func=ast.Name(id="print"), args=args, keywords=keywords):
                b.invokestatic(RUNTIME, "list0", f"(){OBJ}")
                for arg in args:
                    b.dup(); self._expr(arg, b, scope); b.invokestatic(RUNTIME, "listAppend", f"({OBJ}{OBJ})V")
                sep = next((kw.value for kw in keywords if kw.arg == "sep"), None)
                endv = next((kw.value for kw in keywords if kw.arg == "end"), None)
                if any(kw.arg not in {"sep", "end"} for kw in keywords): raise CompileError("print() only supports sep= and end= keywords")
                (b.aconst_null() if sep is None else self._expr(sep, b, scope))
                (b.aconst_null() if endv is None else self._expr(endv, b, scope))
                b.invokestatic(RUNTIME, "printArgs", f"({OBJ}{OBJ}{OBJ}){OBJ}")
            case ast.Call(func=ast.Name(id="len"), args=[arg], keywords=[]):
                self._expr(arg, b, scope); b.invokestatic(RUNTIME, "len", f"({OBJ}){OBJ}")
            case ast.Call(func=ast.Name(id=name), args=[arg], keywords=[]) if name in {"abs", "bool", "str", "repr", "int", "float", "sum", "min", "max", "any", "all"}:
                self._expr(arg, b, scope)
                runtime_name = {"bool":"bool_", "str":"str_", "repr":"repr_", "int":"int_", "float":"float_"}.get(name, name)
                b.invokestatic(RUNTIME, runtime_name, f"({OBJ}){OBJ}")
            case ast.Call(func=ast.Name(id="list"), args=[], keywords=[]):
                b.invokestatic(RUNTIME, "list0", f"(){OBJ}")
            case ast.Call(func=ast.Name(id="list"), args=[arg], keywords=[]):
                self._expr(arg, b, scope); b.invokestatic(RUNTIME, "listFrom", f"({OBJ}){OBJ}")
            case ast.Call(func=ast.Name(id="tuple"), args=[], keywords=[]):
                b.invokestatic(RUNTIME, "tuple0", f"(){OBJ}")
            case ast.Call(func=ast.Name(id="tuple"), args=[arg], keywords=[]):
                self._expr(arg, b, scope); b.invokestatic(RUNTIME, "tupleFrom", f"({OBJ}){OBJ}")
            case ast.Call(func=ast.Name(id="set"), args=[], keywords=[]):
                b.invokestatic(RUNTIME, "set0", f"(){OBJ}")
            case ast.Call(func=ast.Name(id="set"), args=[arg], keywords=[]):
                self._expr(arg, b, scope); b.invokestatic(RUNTIME, "setFrom", f"({OBJ}){OBJ}")
            case ast.Call(func=ast.Name(id="dict"), args=[], keywords=[]):
                b.invokestatic(RUNTIME, "dict0", f"(){OBJ}")
            case ast.Call(func=ast.Name(id="dict"), args=[arg], keywords=[]):
                self._expr(arg, b, scope); b.invokestatic(RUNTIME, "dictFrom", f"({OBJ}){OBJ}")
            case ast.Call(func=ast.Name(id=name), args=[arg], keywords=[]) if name in {"sorted", "reversed"}:
                self._expr(arg, b, scope); b.invokestatic(RUNTIME, name, f"({OBJ}){OBJ}")
            case ast.Call(func=ast.Name(id="__py_await_iter_internal"), args=[arg], keywords=[]):
                self._expr(arg, b, scope); b.invokestatic(RUNTIME, "awaitIterator", f"({OBJ}){OBJ}")
            case ast.Call(func=ast.Name(id="aiter"), args=[arg], keywords=[]):
                self._expr(arg, b, scope); b.invokestatic(RUNTIME, "aiter", f"({OBJ}){OBJ}")
            case ast.Call(func=ast.Name(id="anext"), args=[arg], keywords=[]):
                self._expr(arg, b, scope); b.invokestatic(RUNTIME, "anext_", f"({OBJ}){OBJ}")
            case ast.Call(func=ast.Name(id="iter"), args=[arg], keywords=[]):
                self._expr(arg, b, scope); b.invokestatic(RUNTIME, "iter", f"({OBJ}){OBJ}")
            case ast.Call(func=ast.Name(id="next"), args=[arg], keywords=[]):
                self._expr(arg, b, scope); b.invokestatic(RUNTIME, "next_", f"({OBJ}){OBJ}")
            case ast.Call(func=ast.Name(id="enumerate"), args=[arg], keywords=[]):
                self._expr(arg, b, scope); b.invokestatic(RUNTIME, "enumerate1", f"({OBJ}){OBJ}")
            case ast.Call(func=ast.Name(id="zip"), args=[a, c], keywords=[]):
                self._expr(a, b, scope); self._expr(c, b, scope); b.invokestatic(RUNTIME, "zip2", f"({OBJ}{OBJ}){OBJ}")
            case ast.Call(func=ast.Name(id=name), args=args, keywords=[]) if name in {"Exception", "BaseException", "ValueError", "TypeError", "ZeroDivisionError", "OverflowError", "IndexError", "KeyError", "AssertionError", "RuntimeError", "NameError", "UnboundLocalError", "AttributeError", "StopIteration", "StopAsyncIteration", "GeneratorExit", "OSError"}:
                if len(args) > 1: raise CompileError("exception constructors currently accept zero or one positional argument")
                b.ldc_string(name)
                if args: self._expr(args[0], b, scope)
                else: b.aconst_null()
                b.invokestatic(RUNTIME, "makeException", f"({OBJ}{OBJ}){OBJ}")
            case ast.Call(func=ast.Name(id="range"), args=args, keywords=[]):
                if not 1 <= len(args) <= 3: raise CompileError("range() currently accepts 1-3 positional args")
                for arg in args: self._expr(arg, b, scope)
                b.invokestatic(RUNTIME, f"range{len(args)}", f"({OBJ * len(args)}){OBJ}")
            case ast.Call(func=ast.Name(id="type"), args=[obj], keywords=[]):
                self._expr(obj, b, scope); b.invokestatic(RUNTIME, "typeOf", f"({OBJ}){OBJ}")
            case ast.Call(func=ast.Name(id="callable"), args=[obj], keywords=[]):
                self._expr(obj, b, scope); b.invokestatic(RUNTIME, "callable_", f"({OBJ}){OBJ}")
            case ast.Call(func=ast.Name(id="getattr"), args=[obj, name], keywords=[]):
                self._expr(obj, b, scope); self._expr(name, b, scope); b.invokestatic(RUNTIME, "getattr", f"({OBJ}{OBJ}){OBJ}")
            case ast.Call(func=ast.Name(id="getattr"), args=[obj, name, default], keywords=[]):
                self._expr(obj, b, scope); self._expr(name, b, scope); self._expr(default, b, scope); b.invokestatic(RUNTIME, "getattrDefault", f"({OBJ}{OBJ}{OBJ}){OBJ}")
            case ast.Call(func=ast.Name(id="hasattr"), args=[obj, name], keywords=[]):
                self._expr(obj, b, scope); self._expr(name, b, scope); b.invokestatic(RUNTIME, "hasattr", f"({OBJ}{OBJ}){OBJ}")
            case ast.Call(func=ast.Name(id="setattr"), args=[obj, name, value], keywords=[]):
                self._expr(obj, b, scope); self._expr(name, b, scope); self._expr(value, b, scope); b.invokestatic(RUNTIME, "setattr", f"({OBJ}{OBJ}{OBJ})V"); b.aconst_null()
            case ast.Call(func=ast.Name(id="delattr"), args=[obj, name], keywords=[]):
                self._expr(obj, b, scope); self._expr(name, b, scope); b.invokestatic(RUNTIME, "delattr", f"({OBJ}{OBJ})V"); b.aconst_null()
            case ast.Call(func=ast.Name(id="isinstance"), args=[obj, cls], keywords=[]):
                self._expr(obj, b, scope); self._expr(cls, b, scope)
                b.invokestatic(RUNTIME, "isInstance", f"({OBJ}{OBJ}){OBJ}")
            case ast.Call(func=ast.Name(id="issubclass"), args=[sub, cls], keywords=[]):
                self._expr(sub, b, scope); self._expr(cls, b, scope); b.invokestatic(RUNTIME, "isSubclass", f"({OBJ}{OBJ}){OBJ}")
            case ast.Call(func=ast.Attribute(value=obj, attr=attr), args=args, keywords=[]):
                if len(args) > 3: raise CompileError("dynamic method calls currently support up to 3 arguments")
                self._expr(obj, b, scope); b.ldc_string(attr)
                for arg in args: self._expr(arg, b, scope)
                b.invokestatic(RUNTIME, f"callMethod{len(args)}", f"({OBJ * (2 + len(args))}){OBJ}")
            case ast.Call(func=ast.Name(id=name), args=args, keywords=[]) if name in self.classes:
                if len(args) > 3: raise CompileError("class construction currently supports up to 3 arguments")
                self._load_name(name, b, scope)
                for arg in args: self._expr(arg, b, scope)
                b.invokestatic(RUNTIME, f"instantiate{len(args)}", f"({OBJ * (1 + len(args))}){OBJ}")
            case ast.Call(func=ast.Name(id=name) as func, args=args, keywords=keywords) if name in self.functions or scope.has(name):
                self._emit_dynamic_call(func, args, keywords, b, scope)
            case ast.Call(func=func, args=args, keywords=keywords):
                self._emit_dynamic_call(func, args, keywords, b, scope)
            case _:
                raise CompileError(f"Unsupported expression: {type(node).__name__} at line {getattr(node, 'lineno', '?')}")

    def _compile_try_except(self, body: list[ast.stmt], handlers: list[ast.ExceptHandler], orelse: list[ast.stmt], b: CodeBuilder, scope: Scope, in_function: bool) -> None:
        start, protected_end, dispatch, done = b.label(), b.label(), b.label(), b.label()
        exc_slot = scope.temp()
        b.mark(start)
        for stmt in body: self._stmt(stmt, b, scope, in_function)
        b.mark(protected_end)
        for stmt in orelse: self._stmt(stmt, b, scope, in_function)
        b.goto(done)
        b.mark(dispatch); b.astore(exc_slot)
        b.aload(exc_slot); b.invokestatic(RUNTIME,"tracebackAddCurrentFrame",f"({OBJ})V")
        b.add_exception_handler(start, protected_end, dispatch, "java/lang/Throwable")
        for handler in handlers:
            next_handler = b.label()
            if handler.type is not None:
                if isinstance(handler.type, ast.Name):
                    b.aload(exc_slot); b.ldc_string(handler.type.id)
                    b.invokestatic(RUNTIME, "exceptionMatches", f"({OBJ}{OBJ})Z"); b.ifeq(next_handler)
                elif isinstance(handler.type, ast.Tuple) and all(isinstance(x, ast.Name) for x in handler.type.elts):
                    matched = b.label()
                    for typ in handler.type.elts:
                        b.aload(exc_slot); b.ldc_string(typ.id)
                        b.invokestatic(RUNTIME, "exceptionMatches", f"({OBJ}{OBJ})Z"); b.ifne(matched)
                    b.goto(next_handler); b.mark(matched)
                else:
                    raise CompileError("except types currently must be exception names or tuples of names")
            if handler.name:
                b.aload(exc_slot); b.invokestatic(RUNTIME, "exceptionInstance", f"({OBJ}){OBJ}"); b.astore(scope.define(handler.name))
            self.exception_stack.append(exc_slot)
            for stmt in handler.body: self._stmt(stmt, b, scope, in_function)
            self.exception_stack.pop()
            b.goto(done)
            b.mark(next_handler)
        b.aload(exc_slot); b.athrow()
        b.mark(done)

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

    def _binary_runtime(self, op: ast.operator, b: CodeBuilder) -> None:
        name = {ast.Add:"add", ast.Sub:"sub", ast.Mult:"mul", ast.Div:"truediv", ast.FloorDiv:"floordiv", ast.Mod:"mod", ast.Pow:"pow", ast.BitAnd:"bitAnd", ast.BitOr:"bitOr", ast.BitXor:"bitXor", ast.LShift:"lshift", ast.RShift:"rshift"}.get(type(op))
        if name is None: raise CompileError(f"Unsupported binary operator: {type(op).__name__}")
        b.invokestatic(RUNTIME, name, f"({OBJ}{OBJ}){OBJ}")

    def _boolop(self, values: list[ast.expr], is_and: bool, b: CodeBuilder, scope: Scope) -> None:
        if not values:
            self._boxed_bool(is_and, b); return
        end = b.label(); self._expr(values[0], b, scope)
        for value in values[1:]:
            b.dup(); b.invokestatic(RUNTIME, "truth", f"({OBJ})Z")
            (b.ifeq if is_and else b.ifne)(end)
            b.pop(); self._expr(value, b, scope)
        b.mark(end)


def compile_source(source: str, class_name: str = "Main", filename: str = "<string>", import_map: dict[str,str] | None = None, module_name: str = "__main__", package_name: str | None = None) -> bytes:
    return Compiler(class_name, import_map=import_map, module_name=module_name, package_name=package_name).compile(source, filename)


def _module_context(path: Path, root: Path) -> tuple[str, bool]:
    rel=path.relative_to(root)
    if rel.name == "__init__.py":
        return '.'.join(rel.parent.parts), True
    return '.'.join(rel.with_suffix('').parts), False


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


def _find_module(root: Path, mod: str) -> Path | None:
    if not mod: return None
    candidate=root.joinpath(*mod.split('.')).with_suffix('.py')
    package=root.joinpath(*mod.split('.'),'__init__.py')
    return candidate if candidate.exists() else package if package.exists() else None


def _scan_local_imports(source_path: Path, root: Path, found: dict[str, Path], module_name: str, is_package: bool) -> None:
    tree=ast.parse(source_path.read_text(encoding="utf-8"),filename=str(source_path),mode="exec")
    for node in ast.walk(tree):
        requested: list[str]=[]
        if isinstance(node,ast.Import):
            requested.extend(a.name for a in node.names)
        elif isinstance(node,ast.ImportFrom):
            resolved=_resolve_import_name(module_name,is_package,node.module,node.level)
            if resolved: requested.append(resolved)
            # `from pkg import child` may refer to a submodule.
            if resolved:
                for alias in node.names:
                    if alias.name != '*': requested.append(resolved+'.'+alias.name)
        for mod in requested:
            if mod in found: continue
            path=_find_module(root,mod)
            if path is not None:
                found[mod]=path
                child_name, child_is_pkg=_module_context(path,root)
                _scan_local_imports(path,root,found,child_name,child_is_pkg)


def compile_file(source_path: str | Path, output_dir: str | Path, class_name: str | None = None) -> Path:
    source_path=Path(source_path); output_dir=Path(output_dir)
    class_name=class_name or source_path.stem.title().replace("_","")
    # Walk upward through package markers so imports are resolved from the package root.
    root=source_path.parent
    probe=source_path.parent
    while (probe/'__init__.py').exists():
        root=probe.parent; probe=probe.parent
    main_module, main_is_package=_module_context(source_path,root)
    modules: dict[str,Path]={}
    _scan_local_imports(source_path,root,modules,main_module,main_is_package)
    # Include package parents so `import pkg.sub` can expose pkg.
    for name in list(modules):
        parts=name.split('.')
        for i in range(1,len(parts)):
            parent='.'.join(parts[:i])
            path=_find_module(root,parent)
            if path is not None: modules.setdefault(parent,path)
    import_map={name:"pyjvm315/imports/"+name.replace('.','/') for name in modules}

    def emit(path:Path,jvm_name:str,module_name:str)->Path:
        is_pkg = path.name == "__init__.py"
        package_name = module_name if is_pkg else module_name.rpartition('.')[0]
        data=compile_source(path.read_text(encoding="utf-8"),jvm_name,str(path),import_map=import_map,module_name=module_name,package_name=package_name)
        rel=Path(*jvm_name.replace('/','.').split('.')); out=output_dir/rel.with_suffix('.class')
        out.parent.mkdir(parents=True,exist_ok=True); out.write_bytes(data); return out

    for name,path in modules.items(): emit(path,import_map[name],name)
    return emit(source_path,class_name,"__main__")

