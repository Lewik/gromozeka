#!/bin/bash
set -e

# Reranker service launcher
# Creates venv in GROMOZEKA_HOME/services/reranker-venv and runs infinity-emb
# Requires: uv (https://github.com/astral-sh/uv)

MODEL_ID="${RERANKER_MODEL:-mixedbread-ai/mxbai-rerank-xsmall-v1}"
PORT="${RERANKER_PORT:-7997}"
PYTHON_VERSION="${RERANKER_PYTHON_VERSION:-3.12}"

# Check uv is available
if ! command -v uv &> /dev/null; then
    echo "ERROR: uv is required but not installed."
    echo "Install: curl -LsSf https://astral.sh/uv/install.sh | sh"
    exit 1
fi

# Determine GROMOZEKA_HOME
if [ -n "$GROMOZEKA_HOME" ]; then
    HOME_DIR="$GROMOZEKA_HOME"
elif [ -d "dev-data/client/.gromozeka" ]; then
    # Dev mode - running from project root
    HOME_DIR="$(pwd)/dev-data/client/.gromozeka"
else
    HOME_DIR="$HOME/.gromozeka"
fi

VENV_DIR="$HOME_DIR/services/reranker-venv"
SERVICES_DIR="$HOME_DIR/services"

echo "Gromozeka home: $HOME_DIR"
echo "Reranker venv: $VENV_DIR"
echo "Model: $MODEL_ID"
echo "Port: $PORT"

# Create services directory
mkdir -p "$SERVICES_DIR"

# Create venv if doesn't exist or infinity_emb is missing
if [ ! -f "$VENV_DIR/bin/infinity_emb" ]; then
    echo "Creating virtual environment with Python $PYTHON_VERSION..."
    rm -rf "$VENV_DIR"
    uv venv --python "$PYTHON_VERSION" "$VENV_DIR"

    echo "Installing infinity-emb..."
    uv pip install --python "$VENV_DIR/bin/python" \
        "infinity-emb[torch,server]==0.0.70"
    echo "Installation complete."
fi

# Verify installation
if [ ! -f "$VENV_DIR/bin/infinity_emb" ]; then
    echo "ERROR: infinity_emb not found after installation"
    exit 1
fi

echo "Using Python: $("$VENV_DIR/bin/python" --version)"
echo "Starting reranker on port $PORT..."
exec "$VENV_DIR/bin/infinity_emb" v2 \
    --model-id "$MODEL_ID" \
    --port "$PORT"
