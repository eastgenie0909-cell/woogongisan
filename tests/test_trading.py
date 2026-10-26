from copy import deepcopy
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import json
import threading
import httpx
import pytest
from trading.broker import Meritz, PaperBroker, BrokerError, number
from trading.service import Service, DEFAULT_POLICY, make_plan, has_open_orders, identity
from trading_server import handler
from http.server import ThreadingHTTPServer

NOW = datetime(2026, 10, 26, 15, 0, tzinfo=timezone.utc)
SIGNAL = {'target': {'TQQQ': .7, 'SOXL': 0, 'BITO': 0, 'BRK.B': 0, 'GLD': .1, 'CASH': .2},
          'rebalance_band': .125, 'model_id': 'test-model', 'model_fingerprint': 'a'*64, 'market_date': '2026-10-23'}


class FakeBroker(PaperBroker):
    def __init__(self):
        self.account = super().snapshot()
        self.account['as_of'] = NOW.isoformat()
        self.calls = []
        self.fail = False

    def snapshot(self):
        return deepcopy(self.account)

    def submit(self, order):
        self.calls.append(order)
        if self.fail:
            raise TimeoutError('connection lost')
        return str(100 + len(self.calls))


@pytest.fixture
def service(tmp_path):
    broker = FakeBroker()
    s = Service(broker, tmp_path/'state.sqlite', signal_loader=lambda: deepcopy(SIGNAL), clock=lambda: NOW,
                policy_path=tmp_path/'policy.json')
    yield s
    s.db.close()


def arm_fake(s):
    s.broker.demo = False
    s.broker.account['demo'] = False
    s.check_window = lambda: None
    s.policy_path.write_text(json.dumps(DEFAULT_POLICY | {'allow_live': True, 'execution_reviewed': True,
        'approved_model_fingerprint': SIGNAL['model_fingerprint'], 'max_order_usd': 10000, 'max_daily_usd': 20000}))
    s.mode('confirm')


def test_default_observation_and_auto_locked(service):
    assert service.status()['mode'] == 'observe'
    p = service.create_plan()
    with pytest.raises(ValueError, match='반자동'):
        service.execute(p['id'])
    with pytest.raises(ValueError, match='자동 모드'):
        service.mode('auto')
    assert not service.broker.calls


def test_paper_does_not_call_broker_or_change_real_account(service):
    a = deepcopy(service.broker.account)
    service.mode('confirm')
    p = service.create_plan()
    assert service.execute(p['id'])['state'] == 'simulated'
    assert service.broker.account == a
    assert not service.broker.calls


def test_approval_idempotent_and_persistent(service):
    arm_fake(service)
    p = service.create_plan()
    service.execute(p['id'])
    count = len(service.broker.calls)
    assert count > 0
    service.execute(p['id'])
    assert len(service.broker.calls) == count


@pytest.mark.parametrize('race', ['same_plan', 'different_plans', 'simulation'])
def test_separate_service_instances_cannot_send_duplicate_orders(service, tmp_path, race):
    arm_fake(service)
    first = service.create_plan()
    other = service.create_plan() if race == 'different_plans' else first
    second = Service(service.broker, tmp_path/'state.sqlite', signal_loader=service.signal_loader,
                     clock=service.clock, policy_path=service.policy_path)
    second.check_window = lambda: None
    # Both instances have read draft and passed their local active-order check before either claims.
    barrier = threading.Barrier(2)
    def simultaneous_snapshot():
        barrier.wait(timeout=5)
        return service.broker.snapshot()
    service.sync = second.sync = simultaneous_snapshot
    errors = []
    def run(s, plan, simulate=False):
        try:
            (s.simulate if simulate else s.execute)(plan['id'])
        except Exception as e:
            errors.append(e)
    threads = [threading.Thread(target=run, args=(service, first)),
               threading.Thread(target=run, args=(second, other, race == 'simulation'))]
    try:
        for thread in threads: thread.start()
        for thread in threads: thread.join(timeout=10)
        assert not any(thread.is_alive() for thread in threads)
        calls = service.broker.calls
        assert len(calls) == (0 if race == 'simulation' and not calls else len(first['orders']))
        assert len({o['id'] for o in calls}) == len(calls)
        if race == 'different_plans':
            assert len(errors) == 1 and isinstance(errors[0], ValueError)
        else:
            assert not errors
        # A concurrent simulation must never overwrite accepted real order records.
        assert all(o['state'] == ('accepted' if calls else 'simulated') for o in service.orders())
    finally:
        second.db.close()


def test_durable_execution_claim_blocks_another_plan_even_before_first_submission(service):
    arm_fake(service)
    first = service.create_plan()
    second = service.create_plan()
    claimed, ok = service.claim_plan(first, 'executing', day='2026-10-26')
    assert ok and claimed['state'] == 'executing'
    assert not service.orders()
    with pytest.raises(ValueError, match='실행 중'):
        service.execute(second['id'])
    assert not service.broker.calls


@pytest.mark.parametrize('change', ['cash', 'holdings', 'power', 'signal', 'policy', 'expired', 'paused', 'quote'])
def test_revalidate_before_any_submission(service, change):
    arm_fake(service)
    p = service.create_plan()
    a = service.broker.account
    if change == 'cash': a['cash_usd'] = '1'
    if change == 'power': a['buying_power'] = '1'
    if change == 'holdings': a['positions'][0]['quantity'] = '13'
    if change == 'signal': service.signal_loader = lambda: SIGNAL | {'model_fingerprint': 'b'*64}
    if change == 'policy': service.policy_path.write_text('{}')
    if change == 'expired': service.clock = lambda: NOW + timedelta(minutes=6)
    if change == 'paused': service.pause(True)
    if change == 'quote':
        for x in a['positions']: x['price'] = '0.01'
    with pytest.raises(ValueError): service.execute(p['id'])
    assert not service.broker.calls


def test_timeout_blocks_retry_and_next_plan(service):
    arm_fake(service)
    service.broker.fail = True
    p = service.create_plan()
    assert service.execute(p['id'])['state'] == 'needs_review'
    assert service.status()['paused']
    assert service.orders()[0]['state'] == 'unknown'
    service.pause(False)
    service.execute(p['id'])
    with pytest.raises(ValueError): service.create_plan()
    assert len(service.broker.calls) == 1


def test_restart_pending_submission_is_unknown(tmp_path):
    b = FakeBroker()
    s = Service(b, tmp_path/'db', clock=lambda: NOW)
    s.store_order({'id': 'x', 'plan_id': 'p', 'state': 'submitting'})
    s.db.close()
    s2 = Service(b, tmp_path/'db', clock=lambda: NOW)
    assert s2.orders()[0]['state'] == 'unknown'
    s2.db.close()


def test_partial_fill_and_terminal_fill(service):
    arm_fake(service)
    p = service.create_plan();service.execute(p['id'])
    o = service.orders()[0]
    row = {'oder_no': o['broker_id'], 'oder_date': '20261026', 'orgl_oder_no': 0,
        'ovrs_stck_symb_iscd': o['symbol'], 'odqt': o['quantity'], 'seln_byov_clcd': '1',
        'cntg_qty': 1, 'cntg_crnc_prc': '10', 'ncnt_qty': 0, 'cncl_cnfr_qty': 0,
        'ovrs_stck_oder_stat_cls_code': '0'}
    service.broker.account['orders'] = [row]
    service.sync()
    assert next(x for x in service.orders() if x['id'] == o['id'])['state'] == 'partial'
    row['cntg_qty'] = o['quantity'];service.sync()
    assert next(x for x in service.orders() if x['id'] == o['id'])['state'] == 'filled'


def test_wrong_date_order_is_not_reconciled(service):
    arm_fake(service);p=service.create_plan();service.execute(p['id'])
    o=service.orders()[0]
    service.broker.account['orders']=[{'oder_no': o['broker_id'], 'oder_date': '20261001', 'orgl_oder_no': 0}]
    service.sync()
    assert service.orders()[0]['state']=='accepted'


def test_sell_phase_never_spends_unfilled_proceeds(service):
    p=service.create_plan()
    assert p['stage']=='sell' and all(o['side']=='sell' for o in p['orders'])


def test_buying_power_and_reserve(service):
    a=service.broker.account
    for p in a['positions']:p['quantity']='0';p['sellable']='0'
    a['cash_usd']='10000';a['buying_power']='500'
    signal=SIGNAL|{'target':dict.fromkeys(SYMBOLS_FOR_TEST := ('TQQQ','SOXL','BITO','BRK.B','GLD','CASH'),0)}
    signal['target']['TQQQ']=1
    plan=make_plan(a,signal,DEFAULT_POLICY,NOW)
    assert plan['stage']=='buy'
    assert sum(number(o['limit'])*o['quantity']*Decimal('1.001') for o in plan['orders'])<=400


def test_below_band_no_orders(service):
    a=service.broker.account
    budget=number(a['cash_usd'])+sum(number(p['quantity'])*number(p['price']) for p in a['positions'])
    w={p['symbol']:float(number(p['quantity'])*number(p['price'])/budget) for p in a['positions']}
    w['CASH']=float(number(a['cash_usd'])/budget)
    assert make_plan(a,SIGNAL|{'target':w},DEFAULT_POLICY,NOW)['orders']==[]


@pytest.mark.parametrize('bad', ['NaN','Infinity','-Infinity'])
def test_nonfinite_values_rejected(bad):
    with pytest.raises(ValueError):number(bad)


def test_incomplete_account_and_manual_open_order_block(service):
    service.broker.account['positions'].pop()
    with pytest.raises(ValueError,match='시세 누락'):service.create_plan()
    assert has_open_orders({'orders':[{'ovrs_stck_oder_stat_cls_code':'0','odqt':5,'cntg_qty':1,'ncnt_qty':0}]})


def test_same_day_mark_replaces_and_remains_not_profit(service):
    service.sync();service.broker.account['cash_usd']='100';service.sync()
    history=service.status()['history']
    assert len(history)==1 and history[0]['cash_usd']=='100'
    assert history[0]['flow_adjusted'] is False


def test_live_mode_requires_reviewed_fingerprint(service):
    service.broker.demo=False
    with pytest.raises(ValueError):service.mode('confirm')
    assert service.status()['mode']=='observe'


def test_observation_simulates_even_with_live_orders_locked(service):
    service.broker.demo=False;service.broker.account['demo']=False
    p=service.create_plan()
    service.simulate(p['id']);service.simulate(p['id'])
    assert not service.broker.calls
    assert len(service.orders())==len(p['orders'])
    assert all(o['demo'] and o['state']=='simulated' for o in service.orders())


def test_daily_limit_includes_all_past_orders(service):
    arm_fake(service)
    for i in range(205):
        service.store_order({'id':str(i),'plan_id':'past','state':'filled','day':'2026-10-26',
            'demo':False,'quantity':1,'limit':'100.00'})
    p=service.create_plan()
    with pytest.raises(ValueError,match='일일 누적'):service.execute(p['id'])
    assert not service.broker.calls


def test_known_rejection_stops_remaining_orders(service):
    from trading.broker import OrderRejected
    arm_fake(service)
    calls=[]
    def reject(o):calls.append(o);raise OrderRejected('경고 확인 필요')
    service.broker.submit=reject
    p=service.create_plan();service.execute(p['id'])
    assert len(calls)==1 and service.orders()[0]['state']=='rejected'
    assert service.status()['paused']


def test_pause_during_first_request_stops_next_request(service):
    arm_fake(service)
    def submit(o):
        service.broker.calls.append(o)
        service.stop_orders.set()
        return '101'
    service.broker.submit=submit
    p=service.create_plan();assert len(p['orders'])>1
    service.execute(p['id'])
    assert len(service.broker.calls)==1


def test_auto_disables_after_policy_or_model_change(service):
    arm_fake(service)
    service.set('mode','auto')
    service.signal_loader=lambda:SIGNAL|{'model_fingerprint':'b'*64}
    service.tick()
    assert service.get('mode')=='observe'
    assert not service.broker.calls


def test_real_exchange_calendar_blocks_weekend(service):
    service.broker.demo=False
    service.clock=lambda:datetime(2026,10,25,15,0,tzinfo=timezone.utc)
    with pytest.raises(ValueError,match='정규장'):service.check_window()


def meritz_mock(monkeypatch, route):
    monkeypatch.setenv('MERITZ_APP_KEY','test-key');monkeypatch.setenv('MERITZ_APP_SECRET','test-secret')
    def request(r):
        if r.url.path=='/oauth2/token':return httpx.Response(200,json={'access_token':'test-token','expires_in':3600})
        assert r.headers['mac_address'] and r.headers['authorization']=='Bearer test-token'
        return route(r)
    m=Meritz(httpx.Client(base_url='https://example.test',transport=httpx.MockTransport(request)))
    return m


def test_api_paging_preserves_key_and_5820_data(monkeypatch):
    calls=[]
    def route(r):
        calls.append(r)
        if len(calls)==1:return httpx.Response(200,json={'rsp_cd':'5762','data':[{'quantity':1}],'tr_cont':'1','tr_cont_key':'abc   '})
        assert r.url.params['tr_cont_key']=='abc   '
        return httpx.Response(200,json={'rsp_cd':'5820','data':[{'quantity':2}],'tr_cont':'0'})
    m=meritz_mock(monkeypatch,route)
    assert len(m.rows('/holdings'))==2


def test_api_repeated_page_fails(monkeypatch):
    m=meritz_mock(monkeypatch,lambda r:httpx.Response(200,json={'rsp_cd':'5762','data':[],'tr_cont_key':'a','tr_cont':'1'}))
    with pytest.raises(BrokerError,match='반복'):m.rows('/holdings')


@pytest.mark.parametrize('warn',['1','2','3'])
def test_api_order_warning_is_not_success_or_retried(monkeypatch,warn):
    calls=[]
    def route(r):
        calls.append(r)
        return httpx.Response(200,json={'rsp_cd':'0001','data':{'warn_cls_code':warn,'oder_no':100}})
    m=meritz_mock(monkeypatch,route)
    with pytest.raises(BrokerError):m.submit({'symbol':'TQQQ','vendor':'0537','side':'buy','quantity':1,'limit':'10.00'})
    assert len(calls)==1


def test_api_business_error_even_http_200(monkeypatch):
    m=meritz_mock(monkeypatch,lambda r:httpx.Response(200,json={'rsp_cd':'0760','data':[]}))
    with pytest.raises(BrokerError,match='0760'):m.rows('/holdings')


def test_history_normalizes_fractional_fields_without_losing_dot_symbol(monkeypatch):
    m=meritz_mock(monkeypatch,lambda r:httpx.Response(200,json={'rsp_cd':'5766','data':[
        {'iscd':'BRK.B.NY','oder_dqty':'3.000','cntg_dqty':'1.000'}]}))
    row=m.history('20261023','20261026')[0]
    assert row['ovrs_stck_symb_iscd']=='BRK.B'
    assert number(row['odqt'])==3 and number(row['cntg_qty'])==1


def test_snapshot_merges_fractional_holdings_but_keeps_sellability_separate(monkeypatch):
    m=Meritz()
    common={'crcd':'USD','ovrs_stck_mrkt_tr_cls_code':'01','loan_dtl_type_code':'0000',
        'ovrs_stck_symb_iscd':'TQQQ','ovrs_exch_vndr_idno':'0537','psnt_prc':'100'}
    data={'/accounts/v1/me/overseas/holdings':[
        common|{'rmnd_clcd':'1','rmnd_dqty':'3','seln_able_dqty':'2','avrg_unpr':'80'},
        common|{'rmnd_clcd':'2','rmnd_dqty':'.5','seln_able_dqty':'.5','avrg_unpr':'90'}],
        '/accounts/v1/me/overseas/currencies':[{'crcd':'USD','estm_frcr_amt':'1000','vltn_exrt':'1350'}],
        '/trading/v1/overseas/orders/estimate':[{'crcd':'USD','oder_crnc_oder_able_camt':'900'}],
        '/trading/v1/overseas/orders/today':[]}
    m.rows=lambda path,params=None:deepcopy(data[path])
    m.call=lambda path,params=None:{'data':{'tfam':'100000'}}
    m.quote=lambda symbol,vendor=None:{'symbol':symbol,'price':'100','vendor':vendor or '0537'}
    a=m.snapshot();p=next(p for p in a['positions'] if p['symbol']=='TQQQ')
    assert number(p['quantity'])==Decimal('3.5') and number(p['sellable'])==2
    assert a['cash_usd']=='1000' and a['buying_power']=='900'


def test_mismatched_account_db_refused(tmp_path):
    a=FakeBroker();s=Service(a,tmp_path/'db');s.db.close()
    b=FakeBroker();b.account_id='another-account'
    with pytest.raises(ValueError,match='계좌가 바뀌'):Service(b,tmp_path/'db')


def test_private_http_auth_and_one_shot_approval(service):
    token='t'*40
    server=ThreadingHTTPServer(('127.0.0.1',0),handler(service,token))
    thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    try:
        with httpx.Client(base_url=f'http://127.0.0.1:{server.server_port}',trust_env=False) as client:
            assert client.get('/api/status').status_code==401
            client.headers['Authorization']='Bearer '+token
            r=client.get('/api/status');assert r.status_code==200 and r.headers['cache-control']=='no-store'
            assert client.get('/runtime/trading-access-token.txt').status_code!=200
            assert client.post('/api/mode',json={'mode':'confirm'}).status_code==200
            p=client.post('/api/plan',json={}).json()['plan']
            first=client.post('/api/approve',json={'plan_id':p['id']}).json()
            second=client.post('/api/approve',json={'plan_id':p['id']}).json()
            assert len(first['orders'])==len(second['orders'])>0
    finally:
        server.shutdown();server.server_close()
