import json
import sys
from pathlib import Path
import numpy as np
import pandas as pd
import pytest
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from engine import evaluate
from models.registry import load_model
from pipeline import snapshot

def inputs():
    return (pd.read_csv(ROOT/'data/equities.csv',index_col=0,parse_dates=True),
            pd.read_csv(ROOT/'data/btc.csv',parse_dates=['date_utc']).set_index('date_utc').close)

def test_d25_full_audited_history():
    p,btc=inputs();t,ind=evaluate(p,btc,load_model('woogong-1.3-d'))
    ref=pd.read_csv(ROOT/'data/reference_targets_1.3-d.csv',index_col=0,parse_dates=True)
    assert len(ref)==2843
    np.testing.assert_allclose(t.loc[ref.index],ref,atol=1e-12,rtol=0)
    assert ind.cross_cash.any() and ind.defensive_cash.any()
    forced=(ind.state==1)&ind.defensive_cash
    assert forced.any()
    np.testing.assert_allclose(t.loc[forced,'BRK.B'],.75)
    np.testing.assert_allclose(t.loc[forced,'CASH'],.25)
    np.testing.assert_allclose(t.loc[ind.cross_cash,'CASH'],1)

@pytest.mark.parametrize('day',['2020-03-20','2022-05-10','2024-01-03'])
def test_overlays_are_causal(day):
    p,btc=inputs();t,_=evaluate(p,btc);prefix,_=evaluate(p.loc[:day],btc)
    np.testing.assert_allclose(prefix,t.loc[prefix.index],atol=1e-12)
    future=btc.copy();future.loc[day:]*=3
    altered,_=evaluate(p,future)
    np.testing.assert_allclose(altered.loc[:day],t.loc[:day],atol=1e-12)

def test_profiles_switch_without_changing_application_contract():
    p,btc=inputs();old=load_model('woogong-v1');new=load_model('woogong-1.3-d')
    a,_=snapshot(p,btc,'historical_demo',model=old)
    b,_=snapshot(p,btc,'historical_demo',model=new)
    assert a.keys()==b.keys()
    assert a['model_fingerprint']!=b['model_fingerprint']
    assert a['target'].keys()==b['target'].keys()
    assert len(b['reasons'])==6
    with pytest.raises(ValueError):load_model('../secret')

def test_all_bundled_assets_share_model_and_evidence():
    model=load_model()
    for name in ['snapshot','status','backtest','model']:
        site=json.loads((ROOT/f'site/{name}.json').read_text(encoding='utf-8'))
        phone=json.loads((ROOT/f'android/app/src/main/assets/{name}.json').read_text(encoding='utf-8'))
        # Published daily snapshots advance while the APK's offline seed stays frozen.
        if name in ['model','backtest']:assert site==phone
        assert phone['model_fingerprint']==model.fingerprint
        assert site['model_fingerprint']==model.fingerprint
        assert not site['orders_enabled'] and not site['live_approved']

def test_same_day_model_change_recalculates(tmp_path,monkeypatch):
    import pipeline
    p,btc=inputs();day=p.index[-1]
    (tmp_path/'snapshot.json').write_text(json.dumps({'market_date':str(day.date()),'mode':'live','model_fingerprint':'old'}))
    monkeypatch.setattr(pipeline,'SITE',tmp_path)
    monkeypatch.setattr(pipeline,'session',lambda now:(day,None))
    monkeypatch.setattr(pipeline,'fetch',lambda expected:(p,btc,{}))
    monkeypatch.setattr(sys,'argv',['pipeline.py','--live'])
    pipeline.main()
    new=json.loads((tmp_path/'snapshot.json').read_text(encoding='utf-8'))
    assert new['model_fingerprint']==load_model().fingerprint
    assert json.loads((tmp_path/'status.json').read_text(encoding='utf-8'))['state']=='ready'

def test_model_activation_restores_files_on_failure(tmp_path,monkeypatch):
    import model as manager
    selected=load_model()
    evidence=tmp_path/'models/evidence';evidence.mkdir(parents=True)
    (evidence/f'{selected.id}.backtest.json').write_text(json.dumps(selected.metadata()))
    active=tmp_path/'active_model.json';active.write_text('{"model_id":"woogong-v1"}')
    site=tmp_path/'site';site.mkdir();snap=site/'snapshot.json';snap.write_text('old snapshot')
    old_active=active.read_bytes()
    monkeypatch.setattr(manager,'ROOT',tmp_path)
    def fail():
        snap.write_text('partially generated new snapshot')
        raise RuntimeError('generation failed')
    monkeypatch.setattr(manager,'prepare',fail)
    with pytest.raises(RuntimeError):manager.activate(selected.id)
    assert active.read_bytes()==old_active
    assert snap.read_text()=='old snapshot'

def test_model_activation_rejects_unverified_evidence(tmp_path,monkeypatch):
    import model as manager
    selected=load_model()
    evidence=tmp_path/'models/evidence';evidence.mkdir(parents=True)
    (evidence/f'{selected.id}.backtest.json').write_text('{"model_fingerprint":"old"}')
    monkeypatch.setattr(manager,'ROOT',tmp_path)
    with pytest.raises(ValueError):manager.activate(selected.id)
    assert not (tmp_path/'active_model.json').exists()
