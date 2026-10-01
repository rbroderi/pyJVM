import pkg
import pkg.helper

print(pkg.__spec__.name)
print(pkg.__spec__.parent)
print(pkg.__spec__.submodule_search_locations is not None)
print(pkg.helper.__spec__.name)
print(pkg.helper.__spec__.parent)
print(pkg.helper.__spec__.origin.endswith("helper.py"))
print(pkg.helper.__spec__.submodule_search_locations is None)
print(pkg.helper.__loader__ is not None)
print("VALUE" in pkg.helper.__dict__)
