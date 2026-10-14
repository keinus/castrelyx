"""Use the packaged DOCX rasterizer with Word conversion on Windows.

The canonical renderer failed because no bundled LibreOffice is present on
Windows. Only its conversion function is replaced; Poppler and page export
remain the bundled render_docx.py implementation.
"""
from pathlib import Path
import importlib.util, os, subprocess, json, argparse
HERE=Path(__file__).resolve().parent
parser=argparse.ArgumentParser()
parser.add_argument('--input',default='Laya_Threat_Detection_Paper.ko.docx')
parser.add_argument('--qa-dir',default='qa')
args=parser.parse_args()
qa=HERE/args.qa_dir
helper=Path('C:/Users/keinu/.codex/plugins/cache/openai-primary-runtime/documents/26.909.11814/skills/documents/render_docx.py')
spec=importlib.util.spec_from_file_location('docx_renderer',helper)
renderer=importlib.util.module_from_spec(spec);spec.loader.exec_module(renderer)
def convert(doc_path,user_profile,convert_tmp_dir,stem,verbose=False):
    output=Path(convert_tmp_dir)/(stem+'.pdf')
    command=['powershell.exe','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',str(HERE/'render_with_word.ps1'),'-DocPath',str(Path(doc_path).resolve()),'-PdfPath',str(output)]
    result=subprocess.run(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=90)
    debug=(result.stdout+result.stderr).decode('utf8','replace')
    if result.returncode:raise RuntimeError(debug)
    assert output.exists() and output.stat().st_size>0
    (qa/'word_conversion.log').write_text(debug,encoding='utf8')
    print(debug.strip())
    return str(output),debug
renderer.convert_to_pdf=convert
poppler='C:/Users/keinu/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/poppler/Library/bin'
os.environ['PATH']=poppler+os.pathsep+os.environ.get('PATH','')
qa.mkdir(exist_ok=True)
pages=renderer.rasterize(str(HERE/args.input),str(qa),144,False,True)
print(json.dumps({'pages':len(pages),'renderer':'packaged render_docx.py; Word PDF converter; bundled Poppler'},ensure_ascii=False))
