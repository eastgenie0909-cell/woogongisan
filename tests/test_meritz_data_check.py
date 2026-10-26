import json
import pandas as pd
import pytest
from tools import check_meritz_data as probe


@pytest.fixture
def market(monkeypatch, tmp_path):
    (tmp_path/'data').mkdir()
    pd.DataFrame({symbol:[100.,101.] for symbol in probe.EXCHANGES}, index=pd.to_datetime(['2026-10-19','2026-10-20'])).to_csv(tmp_path/'data/equities.csv')
    monkeypatch.setattr(probe,'ROOT',tmp_path)
    monkeypatch.setattr(probe,'session',lambda now:(pd.Timestamp('2026-10-20'),None))
    calls=[]
    class MarketOnly:
        def rows(self,path,params):
            calls.append((path,params))
            return [{'date':'20261019','prpr':'100'},{'date':'20261020','prpr':'101'}]
    monkeypatch.setattr(probe,'Meritz',MarketOnly)
    return calls,MarketOnly


def test_probe_never_uses_account_or_order_endpoints(market):
    calls,_=market
    result=probe.check()
    assert len(calls)==6
    assert all(path=='/market/v1/overseas/candles/days' and params['mod_stpr_cls_code']=='0' and params['dely_rltm_cls_code']=='1' for path,params in calls)
    assert all(r['overlap_consistent_with_reference'] for r in result['symbols'])
    assert not result['live_signal_approved'] and not result['account_queried'] and result['orders_sent']==0


def test_return_basis_mismatch_is_not_approved(market,monkeypatch):
    _,broker=market
    monkeypatch.setattr(broker,'rows',lambda *args:[{'date':'20261019','prpr':'100'},{'date':'20261020','prpr':'95'}])
    result=probe.check()
    assert all(not r['overlap_consistent_with_reference'] for r in result['symbols'])
    assert not result['live_signal_approved']


def test_failure_does_not_log_broker_secret(market,monkeypatch,capsys):
    _,broker=market
    def fail(*args):
        raise ValueError('private-sample-key-must-not-be-logged')
    monkeypatch.setattr(broker,'rows',fail)
    assert probe.main()==1
    report=(probe.ROOT/'runtime/meritz-market-check.json').read_text()
    assert 'private-sample-key' not in report+capsys.readouterr().out
    assert json.loads(report)['orders_sent']==0
