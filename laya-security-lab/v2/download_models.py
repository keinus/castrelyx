from pathlib import Path
import json,subprocess,shutil,sys
ROOT=Path(__file__).resolve().parent
hf=shutil.which('hf') or str(Path(sys.executable).with_name('hf.exe' if sys.platform=='win32' else 'hf'))
for repo,revision in json.loads((ROOT/'protocol.json').read_text())['hf_models'].items():
    dest=ROOT/'models'/repo.split('/')[-1].lower()
    subprocess.run([hf,'download',repo,'--revision',revision,'--local-dir',str(dest)],check=True)
