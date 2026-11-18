"""Execute one managed Python program; the Worker owns process lifetime and output."""
import ast
import asyncio
import builtins
import inspect
import pathlib
import sys
import tokenize
import types

# -I excludes ambient PYTHONPATH and the script directory. Load the Harness SDK
# before adding user imports, so a workspace's grz.py cannot shadow the client.
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import grz


def main():
    if len(sys.argv) != 2:
        raise ValueError("Python runner requires one launch descriptor")
    descriptor = grz._json_loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
    if not isinstance(descriptor, dict) or set(descriptor) != {"source_path", "arguments", "inline"}:
        raise ValueError("Invalid Python launch descriptor")
    source_path = descriptor["source_path"]
    arguments = descriptor["arguments"]
    inline = descriptor["inline"]
    if not isinstance(source_path, str) or not source_path or "\x00" in source_path:
        raise ValueError("Invalid Python source path")
    if not isinstance(arguments, list) or any(not isinstance(arg, str) or "\x00" in arg for arg in arguments):
        raise ValueError("Invalid Python script arguments")
    if type(inline) is not bool:
        raise ValueError("Invalid Python source mode")
    source = pathlib.Path(source_path)
    if not source.is_absolute() or not source.is_file():
        raise ValueError("Python source must be an existing absolute file path")
    with tokenize.open(source) as stream:
        code = compile(stream.read(), str(source), "exec", flags=ast.PyCF_ALLOW_TOP_LEVEL_AWAIT, dont_inherit=True)

    sys.argv = [str(source), *arguments]
    sys.path.insert(0, str(pathlib.Path.cwd() if inline else source.parent))
    module = types.ModuleType("__main__")
    module.__file__ = str(source)
    module.__package__ = None
    module.__spec__ = None
    module.__cached__ = None
    module.__builtins__ = builtins
    sys.modules["__main__"] = module
    result = eval(code, vars(module))
    if inspect.isawaitable(result):
        asyncio.run(result)


if __name__ == "__main__":
    main()
