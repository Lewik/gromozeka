# Source with: . "$GRZ_SDK_HOME/grz.sh"
# POSIX sh (also Bash). No eval, daemon, downloads, venv or second MCP implementation.
# GRZ_PYTHON optionally selects an existing executable (a path, NOT shell arguments).
_grz_sdk_run() {
    if [ -z "${GRZ_SDK_HOME:-}" ] || [ ! -f "$GRZ_SDK_HOME/grz.py" ]; then
        printf '%s\n' 'GRZ_SDK_HOME must point to the shared SDK directory' >&2
        return 1
    fi
    "${GRZ_PYTHON:-python3}" "$GRZ_SDK_HOME/grz.py" "$@"
}

grz_list_tools() {
    if [ "$#" -ne 0 ]; then
        printf '%s\n' 'Usage: grz_list_tools' >&2
        return 1
    fi
    _grz_sdk_run list
}

grz_describe_tool() {
    if [ "$#" -ne 1 ]; then
        printf '%s\n' 'Usage: grz_describe_tool EXACT_NAME' >&2
        return 1
    fi
    _grz_sdk_run describe "$1"
}

grz_call() {
    if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
        printf '%s\n' 'Usage: grz_call EXACT_NAME [JSON]; omit JSON to read stdin' >&2
        return 1
    fi
    if [ "$#" -eq 2 ]; then
        # printf treats the value literally, including quotes, newlines and shell syntax.
        printf '%s' "$2" | _grz_sdk_run call "$1"
    else
        _grz_sdk_run call "$1"
    fi
}
