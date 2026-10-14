"""Recompute confusion matrices and verify model/data artifacts independently."""
import argparse,hashlib,json,math,platform,sys
from pathlib import Path
from acquire import ROOT,digest

def wilson(successes,total):
    if not total:return None
    z=1.959963984540054;p=successes/total;d=1+z*z/total
    center=(p+z*z/(2*total))/d;half=z*math.sqrt(p*(1-p)/total+z*z/(4*total*total))/d
    return [max(0.,center-half),min(1.,center+half)]

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--experiment',required=True);ap.add_argument('--expected-configurations',type=int);args=ap.parse_args()
    directory=ROOT/'runs'/args.experiment;data=ROOT/'data/experiments'/args.experiment
    manifest=json.loads((data/'manifest.json').read_text());evaluations=sorted(directory.glob('*/*/seed-*/evaluation.json'))
    if not evaluations:raise ValueError('No finished evaluations')
    if args.expected_configurations is not None and len(evaluations)!=args.expected_configurations:raise AssertionError('Incomplete experiment matrix')
    validated=[]
    for file in evaluations:
        result=json.loads(file.read_text());folder=file.parent;meta=json.loads((folder/'training.json').read_text())
        kind=meta['model'];weight=folder/('model/model.safetensors' if kind=='laya' else 'model/adapter_model.safetensors' if kind=='qwen' else 'model.pt' if kind=='gru' else 'model.joblib')
        if digest(weight)!=meta['weights_sha256'] or meta['weights_sha256']!=result['weights_sha256']:raise AssertionError('Weight fingerprint mismatch')
        if digest(data/'manifest.json')!=result['data_manifest_sha256']:raise AssertionError('Evaluation manifest changed')
        intervals={}
        for split,policies in result['tests'].items():
            truth=[json.loads(line) for line in (data/(split+'.jsonl')).read_text().splitlines()]
            pred=[json.loads(line) for line in (folder/(split+'_predictions.jsonl')).read_text().splitlines()]
            if [r['id'] for r in truth]!=[r['id'] for r in pred]:raise AssertionError('Prediction ordering/count mismatch')
            for target,metrics in policies.items():
                threshold=result['policies'][target]['threshold'];counts={'tp':0,'fp':0,'fn':0,'tn':0}
                for source,row in zip(truth,pred):
                    if source['y']!=row['y']:raise AssertionError('Prediction target mismatch')
                    z=row['score']>=threshold;y=bool(row['y']);counts['tp' if y and z else 'fp' if z else 'fn' if y else 'tn']+=1
                if any(counts[k]!=metrics[k] for k in counts):raise AssertionError('Reported confusion matrix mismatch')
                intervals[split+':'+target]={'fpr_wilson95':wilson(counts['fp'],counts['fp']+counts['tn']),
                    'recall_wilson95':wilson(counts['tp'],counts['tp']+counts['fn']),
                    'caveat':'Binomial intervals assume independent cases; temporal correlation violates that assumption. See clustered descriptive intervals too.'}
        validated.append({'folder':str(folder.relative_to(ROOT)),'weights_sha256':meta['weights_sha256'],'confidence_diagnostics':intervals})
    files=[]
    for path in sorted(ROOT.glob('*.py'))+sorted(ROOT.glob('*.json'))+sorted(ROOT.glob('*.md'))+sorted(ROOT.glob('*.sh'))+list(data.glob('*.json'))+list(data.glob('*.jsonl')):
        files.append({'path':str(path.relative_to(ROOT)),'bytes':path.stat().st_size,'sha256':digest(path)})
    for file in directory.rglob('*'):
        if file.is_file() and file.name!='audit.json':
            files.append({'path':str(file.relative_to(ROOT)),'bytes':file.stat().st_size,'sha256':digest(file)})
    import importlib.metadata
    versions={name:importlib.metadata.version(name) for name in ['torch','transformers','peft','numpy','pandas','pyarrow','duckdb','scikit-learn','xgboost']}
    report={'status':'passed','models':len(validated),'cases_per_model':sum(manifest['counts'][s]['rows'] for s in ('known_test','external_test','unseen_test')),
        'validated':validated,'files':files,'runtime':{'python':sys.version,'platform':platform.platform(),'packages':versions}}
    (directory/'audit.json').write_text(json.dumps(report,indent=2));print(json.dumps({'status':'passed','models':len(validated),'files':len(files)}))
if __name__=='__main__':main()
