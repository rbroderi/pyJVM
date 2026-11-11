"""File-system driver: compile whole source trees to class files. Host-only (uses pathlib); the
compiler core in compiler.py has no file or stdlib dependencies so that it can compile itself."""
from __future__ import annotations

import ast
from pathlib import Path

from .classfile import DEFAULT_TARGET
from .compiler import CompileError, Compiler, _resolve_import_name, compile_source


def _module_context(path: Path, root: Path) -> tuple[str, bool]:
    rel=path.relative_to(root)
    if rel.name == "__init__.py":
        return '.'.join(rel.parent.parts), True
    return '.'.join(rel.with_suffix('').parts), False


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


def _static_module_export_info(path: Path, module_name: str, is_package: bool) -> tuple[list[str] | None | str, set[str], list[str]]:
    tree=ast.parse(path.read_text(encoding="utf-8"),filename=str(path),mode="exec")
    own: set[str]=set()
    star_deps: list[str]=[]
    explicit_all: list[str] | None | str = None

    def bind_target(target: ast.expr) -> None:
        if isinstance(target,ast.Name):
            own.add(target.id)
        elif isinstance(target,(ast.Tuple,ast.List)):
            for elt in target.elts: bind_target(elt)

    def literal_all(value: ast.expr) -> list[str] | None:
        if not isinstance(value,(ast.List,ast.Tuple,ast.Set)):
            return None
        out=[]
        for elt in value.elts:
            if not isinstance(elt,ast.Constant) or not isinstance(elt.value,str):
                return None
            out.append(elt.value)
        return out

    def visit(stmt: ast.stmt) -> None:
        nonlocal explicit_all
        if isinstance(stmt,(ast.FunctionDef,ast.AsyncFunctionDef,ast.ClassDef)):
            own.add(stmt.name); return
        if isinstance(stmt,ast.Assign):
            for target in stmt.targets: bind_target(target)
            if any(isinstance(t,ast.Name) and t.id=="__all__" for t in stmt.targets):
                parsed=literal_all(stmt.value); explicit_all=parsed if parsed is not None else "dynamic"
            return
        if isinstance(stmt,ast.AnnAssign):
            bind_target(stmt.target)
            if isinstance(stmt.target,ast.Name) and stmt.target.id=="__all__" and stmt.value is not None:
                parsed=literal_all(stmt.value); explicit_all=parsed if parsed is not None else "dynamic"
            return
        if isinstance(stmt,ast.AugAssign):
            bind_target(stmt.target)
            if isinstance(stmt.target,ast.Name) and stmt.target.id=="__all__":
                explicit_all="dynamic"
            return
        if isinstance(stmt,ast.Import):
            for alias in stmt.names:
                own.add(alias.asname or alias.name.split('.')[0])
            return
        if isinstance(stmt,ast.ImportFrom):
            resolved=_resolve_import_name(module_name,is_package,stmt.module,stmt.level)
            for alias in stmt.names:
                if alias.name=="*":
                    if resolved: star_deps.append(resolved)
                else:
                    own.add(alias.asname or alias.name)
            return
        if isinstance(stmt,(ast.If,ast.While,ast.For,ast.AsyncFor)):
            if isinstance(stmt,(ast.For,ast.AsyncFor)): bind_target(stmt.target)
            for child in stmt.body + stmt.orelse: visit(child)
            return
        if isinstance(stmt,ast.Try):
            for child in stmt.body + stmt.orelse + stmt.finalbody: visit(child)
            for handler in stmt.handlers:
                if handler.name: own.add(handler.name)
                for child in handler.body: visit(child)
            return
        if isinstance(stmt,(ast.With,ast.AsyncWith)):
            for item in stmt.items:
                if item.optional_vars is not None: bind_target(item.optional_vars)
            for child in stmt.body: visit(child)

    for stmt in tree.body: visit(stmt)
    return explicit_all, own, star_deps


def _build_import_exports(modules: dict[str, Path]) -> dict[str, list[str] | None]:
    info: dict[str, tuple[list[str] | None | str, set[str], list[str]]] = {}
    for name,path in modules.items():
        info[name]=_static_module_export_info(path,name,path.name=="__init__.py")

    cache: dict[str, list[str] | None]={}
    visiting: set[str]=set()

    def exports(name: str) -> list[str] | None:
        if name in cache: return cache[name]
        if name in visiting:
            return []
        details=info.get(name)
        if details is None: return []
        explicit,own,star_deps=details
        if explicit=="dynamic":
            cache[name]=None; return None
        if isinstance(explicit,list):
            cache[name]=list(explicit); return cache[name]
        visiting.add(name)
        names=set(own)
        for dep in star_deps:
            dep_exports=exports(dep)
            if dep_exports is None:
                cache[name]=None; visiting.remove(name); return None
            names.update(n for n in dep_exports if not n.startswith("_"))
        visiting.remove(name)
        cache[name]=sorted(n for n in names if not n.startswith("_"))
        return cache[name]

    for name in modules: exports(name)
    return cache


def compile_file(source_path: str | Path, output_dir: str | Path, class_name: str | None = None,
                 *, target: int = DEFAULT_TARGET) -> Path:
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
    import_exports=_build_import_exports(modules)

    def emit(path:Path,jvm_name:str,module_name:str)->Path:
        is_pkg = path.name == "__init__.py"
        package_name = module_name if is_pkg else module_name.rpartition('.')[0]
        data=compile_source(path.read_text(encoding="utf-8"),jvm_name,str(path),
                            import_map=import_map,import_exports=import_exports,
                            module_name=module_name,package_name=package_name,target=target)
        rel=Path(*jvm_name.replace('/','.').split('.')); out=output_dir/rel.with_suffix('.class')
        out.parent.mkdir(parents=True,exist_ok=True); out.write_bytes(data); return out

    for name,path in modules.items(): emit(path,import_map[name],name)
    return emit(source_path,class_name,"__main__")
