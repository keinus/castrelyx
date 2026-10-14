from pathlib import Path
import json,time,torch
from model_io import load,encode,collate,forward
root=Path(__file__).resolve().parent
torch.set_num_threads(4)
model,tok,cfg=load(root/'models/base','cuda:0',1024)
items=[];counts={}
for split in ['train','validation','calibration','policy_validation','test']:
    rows=[json.loads(l) for l in (root/f'data/processed/{split}.jsonl').open(encoding='utf8')]
    encoded=[i for r in rows for i in encode(r,tok,cfg)];counts[split]={'cases':len(rows),'max_tokens':max(len(x['ids']) for x in encoded)}
    if split=='train':items=encoded[:4]
batch=collate(items,tok.pad_token_id,'cuda:0')
model.train();logits=forward(model,batch,'cuda:0');loss=-(batch[-1]*torch.log_softmax(logits,-1)).sum(-1).mean();loss.backward()
assert torch.isfinite(loss)
assert model.scorer[-1].weight.grad is not None
result={'cuda':torch.cuda.get_device_name(0),'torch':torch.__version__,'counts':counts,'forward_backward_loss':float(loss.detach()),'gpu_peak_bytes':torch.cuda.max_memory_allocated(),'status':'pass'}
(root/'runs/preflight.json').write_text(json.dumps(result,indent=2));print(json.dumps(result),flush=True)
