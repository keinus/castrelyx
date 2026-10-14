"""Check label-free CLI output against already frozen reference predictions."""
import argparse,json,subprocess,sys
from pathlib import Path
from acquire import ROOT
from common_v4 import load_rows

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--experiment',required=True);args=ap.parse_args()
    root=ROOT/'runs'/args.experiment;folder=root/'cli_verification';folder.mkdir(exist_ok=False)
    sample=[]
    for split in ('known_test','external_test'):
        rows=load_rows(args.experiment,split)
        for label in (0,1):sample += [r for r in rows if r['y']==label][:6]
    path=folder/'input_without_labels.jsonl'
    path.write_text(''.join(json.dumps({'id':r['id'],'state':r['state']})+'\n' for r in sample))
    checks=[]
    for evaluation in sorted(root.glob('*/*/seed-*/evaluation.json')):
        model_folder=evaluation.parent;result=json.loads(evaluation.read_text());name=result['view']+'_'+result['model']+'_'+str(result['seed'])
        output=folder/(name+'.jsonl')
        subprocess.run([sys.executable,str(ROOT/'infer_v4.py'),'--folder',str(model_folder),'--input',str(path),'--output',str(output),'--batch-size','1'],check=True)
        expected={}
        for split in ('known_test','external_test'):
            for line in (model_folder/(split+'_predictions.jsonl')).read_text().splitlines():
                row=json.loads(line);expected[row['id']]=row
        actual=[json.loads(line) for line in output.read_text().splitlines()]
        if [r['id'] for r in actual]!=[r['id'] for r in sample]:raise AssertionError('CLI changed input count/order')
        maximum=0.
        for row in actual:
            reference=expected[row['id']];maximum=max(maximum,abs(row['score']-reference['score']))
            if row['threat']!=reference['predicted'] or abs(row['score']-reference['score'])>1e-5:raise AssertionError('CLI prediction mismatch')
            if row['response'] is not None:raise AssertionError('Untrained response action generated')
        checks.append({'model':name,'cases':len(actual),'max_score_difference':maximum});print(json.dumps(checks[-1]),flush=True)
    (folder/'verification.json').write_text(json.dumps({'status':'passed','label_free':True,'checks':checks},indent=2))
if __name__=='__main__':main()
