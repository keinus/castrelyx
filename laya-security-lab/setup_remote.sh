#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p runs
uv_bin="${HOME}/.local/bin/uv"
if [ ! -x "$uv_bin" ]; then uv_bin="$(command -v uv)"; fi
if [ ! -x .venv/bin/python ]; then "$uv_bin" venv --python 3.11 .venv; fi
"$uv_bin" pip install --python .venv/bin/python torch==2.8.0 --index-url https://download.pytorch.org/whl/cu128
"$uv_bin" pip install --python .venv/bin/python transformers==4.57.1 huggingface_hub==0.36.0 safetensors pandas pyarrow numpy scikit-learn openpyxl psutil
"$uv_bin" pip install --python .venv/bin/python --no-deps ./vendor/laya
.venv/bin/python -m pip freeze > runs/requirements-resolved.txt 2>/dev/null || "$uv_bin" pip freeze --python .venv/bin/python > runs/requirements-resolved.txt
.venv/bin/python -c 'import torch; print(torch.__version__); print(torch.cuda.is_available()); print(torch.cuda.get_device_name(0))'
