from pathlib import Path
from collections import defaultdict
import argparse,copy,gc,json,time,random,joblib
import numpy as np
import torch
from sklearn.feature_extraction import DictVectorizer
from sklearn.ensemble import ExtraTreesClassifier,HistGradientBoostingClassifier,IsolationForest
from common_v3 import ROOT,LAB,read,features,LayaDetector,QwenDetector,sigmoid,fit_temperature,choose_threshold,metric,sha,without_context

TESTS=['known_test','unseen_test','external_known_test']

def interval(scores,rows,threshold):
    groups=defaultdict(lambda:np.zeros(4))
    for p,r in zip(scores,rows):
        pos=p>=threshold;y=bool(r['y']);groups[r['group']]+=np.array([pos and y,pos and not y,not pos and y,not pos and not y])
    x=np.array(list(groups.values()));rng=np.random.default_rng(20261005);recalls=[];fprs=[]
    for i in range(1000):
        tp,fp,fn,tn=x[rng.integers(len(x),size=len(x))].sum(0)
        if tp+fn:recalls.append(tp/(tp+fn))
        if fp+tn:fprs.append(fp/(fp+tn))
    return {'cluster_bootstrap_recall_95':np.percentile(recalls,[2.5,97.5]).tolist() if recalls else None,'cluster_bootstrap_fpr_95':np.percentile(fprs,[2.5,97.5]).tolist() if fprs else None,'unit':'30-minute host group; only a few independent captures'}

def latency(detector,rows):
    rng=random.Random(20261005);sample=list(rows);rng.shuffle(sample);sample=sample[:80]
    for r in sample[:10]:detector.score([r],batch_size=1)
    values=[]
    for r in sample:
        t=time.perf_counter();detector.score([r],batch_size=1);values.append((time.perf_counter()-t)*1000)
    return {'samples':len(values),**{f'p{p}_ms':float(np.percentile(values,p)) for p in (50,95,99)},'within_200ms_fraction':sum(v<=200 for v in values)/len(values),'scope':'one state, tokenization + constrained binary score; no sensor, queue, prose generation or actuation'}

class TreeDetector:
    def __init__(self,model,vectorizer,cdf=None):self.model=model;self.vectorizer=vectorizer;self.cdf=cdf
    def score(self,rows,batch_size=None):
        x=self.vectorizer.transform([features(r['state']) for r in rows])
        if self.cdf is None:return self.model.predict_proba(x)[:,1]
        score=-self.model.score_samples(x);return np.searchsorted(self.cdf,score,side='right')/len(self.cdf)

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--only',nargs='*');args=ap.parse_args()
    torch.set_num_threads(4);train=read('train');val=read('validation');cal=read('calibration');tests={name:read(name) for name in TESTS}
    folder=ROOT/'runs';folder.mkdir(exist_ok=True);result_path=folder/'comparison.json'
    summary=json.loads(result_path.read_text()) if result_path.exists() else {'models':{},'protocol':json.loads((ROOT/'protocol.json').read_text()),'data_manifest':json.loads((ROOT/'data/manifest.json').read_text()),'hardware':{'gpu':torch.cuda.get_device_name(0),'torch':torch.__version__}}
    specifications=[('laya_v2_transfer','laya',LAB/'v2/models/laya-v2'),('laya_v3_sft','laya',ROOT/'models/laya_v3_sft'),('laya_v3_normal_relation_sft','laya',ROOT/'models/laya_v3_normal_relation'),('qwen3_06b_v3_lora','lora',LAB/'v2/models/qwen3-0.6b'),('extra_trees','tree',None),('hist_gradient_boosting','tree',None),('isolation_forest','tree',None)]
    for name,kind,path in specifications:
        if args.only and name not in args.only:continue
        started=time.time();temperature=1.;raw_probabilities=kind=='tree'
        if kind=='laya':detector=LayaDetector(path)
        elif kind in ('qwen','lora'):detector=QwenDetector(path,adapter=ROOT/'models/qwen3_06b_v3_lora' if kind=='lora' else None)
        else:
            vec=DictVectorizer(sparse=False);x=vec.fit_transform([features(r['state']) for r in train]);y=np.array([r['y'] for r in train])
            if name=='extra_trees':model=ExtraTreesClassifier(n_estimators=400,min_samples_leaf=2,max_features=.8,class_weight='balanced',n_jobs=4,random_state=20261005)
            elif name=='hist_gradient_boosting':model=HistGradientBoostingClassifier(max_iter=250,learning_rate=.06,max_leaf_nodes=31,l2_regularization=1,class_weight='balanced',random_state=20261005)
            else:model=IsolationForest(n_estimators=400,max_samples=min(1024,int(sum(y==0))),n_jobs=4,random_state=20261005)
            model.fit(x[y==0] if name=='isolation_forest' else x,y[y==0] if name=='isolation_forest' else y)
            cdf=np.sort(-model.score_samples(x[y==0])) if name=='isolation_forest' else None
            detector=TreeDetector(model,vec,cdf);joblib.dump({'model':model,'vectorizer':vec,'cdf':cdf},ROOT/f'models/{name}.joblib')
        cold=time.perf_counter();detector.score([val[0]],batch_size=1);cold_ms=(time.perf_counter()-cold)*1000
        if not raw_probabilities:temperature=fit_temperature(detector.score(cal),cal)
        def score(rows):
            value=detector.score(rows);return np.asarray(value) if raw_probabilities else sigmoid(value/temperature)
        cal_scores=score(cal);policy=choose_threshold(cal_scores,cal);policy['selection_split']='calibration';policy['calibration_fpr']=policy.pop('validation_fpr');policy['calibration_f1']=policy.pop('validation_f1');threshold=policy['threshold']
        entry={'training':kind=='lora' or name.startswith('laya_v3') or kind=='tree','normal_only':name=='isolation_forest','temperature':temperature,'policy':policy,'cold_first_request_ms':cold_ms,'tests':{},'exceptions':0}
        if kind=='laya':entry['weights_sha256']=sha(path/'model.safetensors')
        elif kind=='lora':entry['adapter_sha256']=sha(ROOT/'models/qwen3_06b_v3_lora/adapter_model.safetensors')
        elif kind=='tree':entry['artifact_sha256']=sha(ROOT/f'models/{name}.joblib')
        if name=='laya_v2_transfer':entry['caveat']='Old CIC-trained weights evaluated on the new Zeek-state interface. Not a directly comparable replay of the prior report.'
        predictions=[]
        for test,rows in tests.items():
            scores=score(rows);m=metric(scores,rows,threshold);m.update(interval(scores,rows,threshold));m['at_05']=metric(scores,rows,.5)
            m['attack_families']={f:{'n':int(sum(r['family']==f and r['y'] for r in rows)),'detected':int(sum(p>=threshold and r['family']==f and r['y'] for r,p in zip(rows,scores)))} for f in sorted({r['family'] for r in rows if r['y']})}
            entry['tests'][test]=m
            predictions += [{'id':r['id'],'group':r['group'],'split':test,'source':r['source'],'family':r['family'],'gold':r['y'],'probability':float(p),'predicted':bool(p>=threshold)} for r,p in zip(rows,scores)]
        entry['without_context']={test:metric(score(without_context(rows)),rows,threshold) for test,rows in tests.items()}
        entry['latency']=latency(detector,tests['known_test']+tests['unseen_test']);entry['seconds']=time.time()-started
        summary['models'][name]=entry
        (folder/f'predictions_{name}.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in predictions))
        (ROOT/f'models/{name}_decision_policy.json').write_text(json.dumps({'temperature':temperature,**policy},indent=2))
        result_path.write_text(json.dumps(summary,indent=2));print(json.dumps({'model':name,'known':entry['tests']['known_test'],'unseen':entry['tests']['unseen_test'],'latency':entry['latency']}),flush=True)
        del detector;gc.collect();torch.cuda.empty_cache()
    print('COMPARISON_COMPLETE',flush=True)

if __name__=='__main__':main()
