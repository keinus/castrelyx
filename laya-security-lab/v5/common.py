"""Frozen FlowTransformer comparison protocol; no test-dependent tuning."""
from pathlib import Path
import hashlib, json, sys
import numpy as np

ROOT = Path(__file__).resolve().parent
LAB = ROOT.parent
UPSTREAM = ROOT / 'references/FlowTransformer'
sys.path.insert(0, str(UPSTREAM))
SEEDS = [20261005, 20261006, 20261007]
WINDOW = 8
BATCH = 128
STEPS = 64
EPOCHS = 20
PATIENCE = 5
CONFIGS = {
    'encoder_dense': dict(family='Shallow Encoder', layers=2, heads=2, ff=128, head='last', encoding='record_dense', dim=128, paper_params=382081, paper_f1=90.45, paper_recall=99.89, paper_fpr=1.03),
    'encoder_projection': dict(family='Shallow Encoder', layers=2, heads=2, ff=256, head='feature_projection', encoding='record_projection', dim=64, paper_params=350529, paper_f1=90.43, paper_recall=98.47, paper_fpr=.96),
    'decoder_last': dict(family='Shallow Decoder', layers=2, heads=2, ff=128, head='last', encoding='none', paper_params=1327649, paper_f1=88.94, paper_recall=99.79, paper_fpr=1.22),
    'decoder_flatten': dict(family='Shallow Decoder', layers=2, heads=2, ff=128, head='flatten', encoding='none', paper_params=1567777, paper_f1=87.91, paper_recall=99.61, paper_fpr=1.32),
    'gpt_lookup': dict(family='GPT Deep Decoder', layers=12, heads=12, ff=768, head='feature_projection', encoding='lookup', dim=16, paper_params=20312029, paper_f1=89.98, paper_recall=100., paper_fpr=1.07),
    'gpt_dense': dict(family='GPT Deep Decoder', layers=12, heads=12, ff=768, head='last', encoding='record_dense', dim=64, paper_params=3606081, paper_f1=89.95, paper_recall=99.82, paper_fpr=1.07),
    'bert_none': dict(family='BERT Deep Encoder', layers=12, heads=12, ff=768, head='last', encoding='none', paper_params=123889249, paper_f1=87.74, paper_recall=99.75, paper_fpr=1.33),
    'bert_projection': dict(family='BERT Deep Encoder', layers=12, heads=12, ff=768, head='last', encoding='record_projection', dim=64, paper_params=29862401, paper_f1=76.23, paper_recall=98.90, paper_fpr=2.98),
}

def digest(path):
    with open(path, 'rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()

def write_json(path, data):
    path=Path(path); path.parent.mkdir(parents=True, exist_ok=True)
    tmp=path.with_suffix(path.suffix+'.tmp')
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2, allow_nan=False), encoding='utf8')
    tmp.replace(path)

class Data:
    def __init__(self):
        p=ROOT/'data/prepared'
        self.meta=json.loads((p/'manifest.json').read_text())
        self.numeric=np.load(p/'numeric.npy', mmap_mode='r')
        self.categorical=np.load(p/'categorical.npy', mmap_mode='r')
        self.y=np.load(p/'y.npy', mmap_mode='r')
        self.attack=np.load(p/'attack.npy', mmap_mode='r')
        self.n=len(self.y); self.cut=self.meta['split_row']
        self.test=np.arange(self.cut, self.n)
        self.num_names=self.meta['numeric_fields']; self.cat_names=self.meta['categorical_fields']
        self.levels=self.meta['levels_per_categorical_feature']
        self.eyes=[np.eye(k, dtype=np.float32) for k in self.levels]
    def rows(self, indices, context='sequence'):
        i=np.asarray(indices)[:,None] + np.arange(1-WINDOW,1)[None,:]
        if context=='target_repeat': i=np.repeat(np.asarray(indices)[:,None], WINDOW, axis=1)
        elif context=='context_zero': pass
        elif context!='sequence': raise ValueError(context)
        n=self.numeric[i]; c=self.categorical[i]
        if context=='context_zero':
            n=n.copy(); c=c.copy(); n[:,:-1]=0; c[:,:-1]=1
        return n,c
    def features(self, indices, integers=False, context='sequence'):
        n,c=self.rows(indices, context)
        out=[n[:,:,i:i+1] for i in range(n.shape[-1])]
        out += [c[:,:,i:i+1].astype(np.int32) if integers else self.eyes[i][c[:,:,i]-1] for i in range(c.shape[-1])]
        return out
    def schedule(self, seed):
        return np.load(ROOT/f'data/prepared/schedule_{seed}.npy', mmap_mode='r')

def metrics(y, prob):
    from sklearn.metrics import average_precision_score, roc_auc_score
    y=np.asarray(y, dtype=bool); p=np.asarray(prob)
    if len(y)!=len(p) or not np.isfinite(p).all(): raise ValueError('Invalid predictions')
    z=p>.5
    tp=int(np.sum(z & y)); fp=int(np.sum(z & ~y)); tn=int(np.sum(~z & ~y)); fn=int(np.sum(~z & y))
    return dict(n=len(y), TP=tp, FP=fp, TN=tn, FN=fn,
        precision=tp/max(1,tp+fp), recall=tp/max(1,tp+fn), fpr=fp/max(1,fp+tn),
        f1=2*tp/max(1,2*tp+fp+fn), accuracy=(tp+tn)/len(y),
        average_precision=float(average_precision_score(y,p)), roc_auc=float(roc_auc_score(y,p)))

def group_metrics(data, indices, probabilities):
    overall=metrics(data.y[indices], probabilities)
    groups={}
    for k,name in enumerate(data.meta['attack_names']):
        mask=data.attack[indices]==k
        if mask.any():
            groups[name]=dict(n=int(mask.sum()), predicted_attack=int((probabilities[mask]>.5).sum()),
                              detection_rate=float(np.mean(probabilities[mask]>.5)))
    return dict(overall=overall, by_attack=groups)
