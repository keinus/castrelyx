"""Offline PCAP -> Zeek adapter; requires legitimately obtained CIC captures."""
import argparse,json,subprocess
from pathlib import Path
from acquire import ROOT,digest,download

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--manifest',type=Path,required=True);ap.add_argument('--output',type=Path,required=True)
    ap.add_argument('--zeek',default='zeek');ap.add_argument('--ignore-checksums',action='store_true');args=ap.parse_args()
    if args.output.exists():raise FileExistsError('Output manifest exists')
    version=subprocess.check_output([args.zeek,'--version'],text=True).strip();source=json.loads(args.manifest.read_text(encoding='utf8'))
    output=[];root=args.output.parent.resolve();root.mkdir(parents=True,exist_ok=True)
    for capture in source['captures']:
        for key in ('capture','pcap','pcap_sha256','family','label','source_url'):
            if key not in capture:raise ValueError('Missing capture provenance: '+key)
        path=Path(capture['pcap'])
        if not path.is_absolute():path=args.manifest.parent/path
        if not path.exists():raise FileNotFoundError('Obtain the official PCAP, then set its manifest path: '+str(path))
        if digest(path)!=capture['pcap_sha256']:raise ValueError('PCAP checksum mismatch')
        name=capture['capture']
        if not name or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-' for c in name):raise ValueError('Unsafe capture ID')
        target=root/name
        if target.exists():raise FileExistsError('Preserve prior Zeek extraction: '+str(target))
        target.mkdir();command=[args.zeek]+(['-C'] if args.ignore_checksums else [])+['-r',str(path.resolve()),'LogAscii::use_json=T']
        subprocess.run(command,cwd=target,check=True)
        log=target/'conn.log'
        if not log.exists():raise RuntimeError('Zeek produced no conn.log')
        output.append({**capture,'conn_log':str(log),'conn_log_sha256':digest(log),'zeek_version':version,
            'extraction_command':command,'label_scope':'publisher capture class, inherited by each extracted flow'})
    args.output.write_text(json.dumps({'captures':output},indent=2),encoding='utf8')
    print(json.dumps({'manifest':str(args.output),'captures':len(output)}))
if __name__=='__main__':main()
