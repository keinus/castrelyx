"""Exact Laya architecture and tokenization, with a fixed security question contract."""
from pathlib import Path
from contextlib import nullcontext
import json,copy
import torch
from safetensors.torch import load_file,save_file
from transformers import AutoTokenizer
from laya.agent import _fix_tokenizer_config
from laya.common import build_model,build_sequence,QTYPES
from contract import QUESTIONS,VERSION

def load(path,device='cuda',max_len=None):
    path=Path(path);cfg=json.loads((path/'rl_agent_config.json').read_text())
    if max_len:cfg['max_len']=max_len
    cfg['head_max_len']=192
    _fix_tokenizer_config(str(path))
    tokenizer=AutoTokenizer.from_pretrained(path/'tokenizer',local_files_only=True)
    model=build_model(cfg,encoder_dir=str(path/'encoder'),pretrained=False)
    model.load_state_dict(load_file(str(path/'model.safetensors')),strict=True)
    model=model.float().to(device)
    return model,tokenizer,cfg

def encode(case,tokenizer,cfg):
    items=[]
    for name,q in QUESTIONS.items():
        ids,markers,stats=build_sequence(tokenizer,case['state'],{'t':q['type'],'ins':q['instructions'],'crit':q.get('criteria')},cfg['max_len'],cfg['head_max_len'],return_truncation_stats=True)
        if stats['truncated']:raise ValueError(f"Input truncation: {case.get('case_id','predict')} {name}: {stats}")
        keys=list(q['criteria']) if q['type']=='choice' else [str(i) for i in range(len(q['criteria']))] if q['type']=='score' else ['false','true']
        if len(markers)!=len(keys):raise ValueError('Missing choice markers')
        target=[case.get('gold',{}).get(name,{}).get('probabilities',{}).get(k,0.0) for k in keys]
        items.append({'ids':ids,'markers':markers,'qtype':QTYPES[q['type']],'target':target,'name':name,'keys':keys,'case_id':case.get('case_id','predict')})
    return items

def collate(items,pad_id,device):
    n=len(items);length=max(len(x['ids']) for x in items);k=max(len(x['markers']) for x in items)
    ids=torch.full((n,length),pad_id,dtype=torch.long);att=torch.zeros((n,length),dtype=torch.long)
    pos=torch.zeros((n,k),dtype=torch.long);mask=torch.zeros((n,k),dtype=torch.bool);target=torch.zeros((n,k))
    for i,x in enumerate(items):
        l=len(x['ids']);z=len(x['markers']);ids[i,:l]=torch.tensor(x['ids']);att[i,:l]=1
        pos[i,:z]=torch.tensor(x['markers']);mask[i,:z]=True;target[i,:z]=torch.tensor(x['target'])
    qtype=torch.tensor([x['qtype'] for x in items],dtype=torch.long)
    return tuple(x.to(device) for x in (ids,att,pos,mask,qtype,target))

def amp(device):
    return torch.autocast('cuda',dtype=torch.bfloat16) if str(device).startswith('cuda') else nullcontext()

def forward(model,batch,device):
    ids,att,pos,mask,qt,target=batch
    with amp(device):logits,_=model(ids,att,pos,mask,qt)
    return logits.float().masked_fill(~mask,-1e4)

def export(model,tokenizer,cfg,path,metadata):
    path=Path(path);path.mkdir(parents=True,exist_ok=True)
    save_file({n:v.detach().cpu().half().contiguous() if v.is_floating_point() else v.detach().cpu().contiguous() for n,v in model.state_dict().items()},str(path/'model.safetensors'))
    model.encoder.config.save_pretrained(path/'encoder');tokenizer.save_pretrained(path/'tokenizer')
    out=copy.deepcopy(cfg);out.pop('temperature_by_options',None);out.update({'temperature':[1.,1.,1.],'model_name':'laya-security','fine_tuned':True,'security_schema':VERSION,'amp_dtype':'bf16'})
    (path/'rl_agent_config.json').write_text(json.dumps(out,indent=2))
    (path/'training_metadata.json').write_text(json.dumps(metadata,indent=2))
    (path/'questions.json').write_text(json.dumps(QUESTIONS,indent=2))

class Predictor:
    def __init__(self,path,device='cuda'):
        self.device=device;self.model,self.tokenizer,self.cfg=load(path,device);self.model.eval()
    @torch.inference_mode()
    def predict(self,state):
        items=encode({'state':state},self.tokenizer,self.cfg);batch=collate(items,self.tokenizer.pad_token_id,self.device)
        logits=forward(self.model,batch,self.device);answers={}
        for i,item in enumerate(items):
            k=len(item['keys']);bucket='2' if k==2 else '3-5' if k<=5 else '6-10' if k<=10 else '11+'
            kind=QUESTIONS[item['name']]['type'];group=f'{kind}:{bucket}'
            default=self.cfg.get('temperature',[1.,1.,1.])[item['qtype']]
            t=min(5.,max(.5,self.cfg.get('temperature_by_options',{}).get(group,default)))
            probs=torch.softmax(logits[i,:len(item['keys'])]/t,-1).cpu().tolist();j=max(range(len(probs)),key=probs.__getitem__)
            answer={'probabilities':dict(zip(item['keys'],probs)),'answer_confidence':probs[j]}
            answer.update({'choice':item['keys'][j]} if item['name']=='response' else {'score':j} if item['name']=='urgency' else {'noul':probs[1],'threat':probs[1]>=.5})
            answers[item['name']]=answer
        return {'answers':answers,'input_tokens':[len(x['ids']) for x in items]}
