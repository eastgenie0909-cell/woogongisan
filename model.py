"""List profiles or activate a verified model: python model.py use woogong-1.3-d."""
import argparse
import json
from pathlib import Path
from models.registry import load_model
from pipeline import write
from prepare import main as prepare
ROOT=Path(__file__).parent

def activate(model_id):
    model=load_model(model_id)
    evidence=json.loads((ROOT/'models/evidence'/f'{model.id}.backtest.json').read_text(encoding='utf-8'))
    if evidence.get('model_fingerprint')!=model.fingerprint:
        raise ValueError('선택 모델의 검증 자료를 먼저 등록하세요')
    active=ROOT/'active_model.json'
    previous=active.read_bytes()
    files=[folder/f'{name}.json' for folder in [ROOT/'site',ROOT/'android/app/src/main/assets']
           for name in ['snapshot','status','model','backtest','extended_backtest']]
    backups={p:p.read_bytes() if p.exists() else None for p in files}
    try:
        write(active,{'model_id':model.id})
        prepare()
    except Exception:
        active.write_bytes(previous)
        for path,data in backups.items():
            if data is None:path.unlink(missing_ok=True)
            else:path.write_bytes(data)
        raise

def main():
    parser=argparse.ArgumentParser(description='우공 투자 모델 관리')
    sub=parser.add_subparsers(dest='command',required=True)
    sub.add_parser('list')
    use=sub.add_parser('use');use.add_argument('model_id')
    args=parser.parse_args()
    if args.command=='use':activate(args.model_id)
    else:
        for path in sorted((ROOT/'models/profiles').glob('*.json')):
            model=load_model(path.stem)
            print(model.id,model.version,'(active)' if model.id==load_model().id else '')

if __name__=='__main__':main()
