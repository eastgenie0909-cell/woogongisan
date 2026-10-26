"""Durable order proposals, explicit approval, reconciliation and paper journal."""
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_FLOOR, ROUND_CEILING
from pathlib import Path
from zoneinfo import ZoneInfo
import hashlib
import json
import sqlite3
import threading
import uuid
from .broker import SYMBOLS, number, utc, OrderRejected

ROOT = Path(__file__).resolve().parents[1]
ACTIVE = {'submitting', 'unknown', 'accepted', 'partial'}
DEFAULT_POLICY = {'allow_live': False, 'allow_auto': False, 'approved_model_fingerprint': '',
    'execution_reviewed': False, 'execution': 'regular_limit', 'max_order_usd': 500,
    'max_daily_usd': 1000, 'reserve_usd': 100, 'fee_rate': .001, 'price_buffer': .001}


def dump(obj):
    return json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)


def digest(obj):
    return hashlib.sha256(dump(obj).encode()).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def parse_time(value):
    t = datetime.fromisoformat(value.replace('Z', '+00:00'))
    require(t.tzinfo is not None, '시간대가 없는 시각입니다')
    return t


def identity(account):
    # Quotes can change; cash, holdings, sellability and buying power must not.
    return digest({k: account[k] for k in ('account_id', 'cash_usd', 'cash_krw', 'buying_power')} |
        {'positions': sorted([(p['symbol'], str(number(p['quantity'])), str(number(p['sellable'])))
                              for p in account['positions']])})


def validate_account(a, now):
    age = (now - parse_time(a['as_of'])).total_seconds()
    require(0 <= age <= 120, '계좌 조회가 오래되었습니다. 다시 동기화하세요')
    for key in ('cash_usd', 'cash_krw', 'buying_power'):
        require(number(a[key]) >= 0, '음수 현금·미수 계좌는 지원하지 않습니다')
    require(number(a['fx']) > 0, '환율 누락')
    seen = set()
    for p in a['positions']:
        require(p['symbol'] not in seen, '중복 종목')
        seen.add(p['symbol'])
        require(number(p['quantity']) >= number(p['sellable']) >= 0, '매도가능수량 오류')
        require(number(p['price']) > 0 and number(p['average']) >= 0, '가격·평단 오류')
    require(set(SYMBOLS) <= seen, '모델 종목 시세 누락')


def has_open_orders(a):
    # Unknown broker states fail closed. Today includes manual and other-client orders.
    for o in a['orders']:
        state = str(o.get('ovrs_stck_oder_stat_cls_code', ''))
        if state not in ('0', '1', '2', '3', '4'):
            return True
        if state in ('1', '2', '3'):
            continue
        remaining = number(o.get('odqt', 0)) - number(o.get('cntg_qty', 0)) - number(o.get('cncl_cnfr_qty', 0))
        if remaining > 0 or state == '4':
            return True
    return False


def make_plan(a, signal, policy, now):
    validate_account(a, now)
    require(not has_open_orders(a), '미체결·처리 중 주문이 있습니다. 체결 확인 후 다시 계산하세요')
    weights = {k: number(v) for k, v in signal['target'].items()}
    require(set(weights) == set(SYMBOLS) | {'CASH'}, '지원하지 않는 모델 자산')
    require(all(w >= 0 for w in weights.values()) and abs(sum(weights.values()) - 1) < Decimal('1e-8'), '목표 비중 오류')
    fee, buffer = number(policy['fee_rate']), number(policy['price_buffer'])
    require(0 <= fee < Decimal('.1') and 0 <= buffer < Decimal('.1'), '비용 범위 오류')
    reserve_floor = number(policy['reserve_usd'])
    require(reserve_floor >= 0, '현금 유지액 오류')
    ps = {p['symbol']: p for p in a['positions']}
    cash = number(a['cash_usd'])
    budget = cash + sum(number(ps[s]['quantity']) * number(ps[s]['price']) for s in SYMBOLS)
    require(budget > 0, '운용할 달러 자산이 없습니다')
    gap = max([abs(weights[s] - number(ps[s]['quantity']) * number(ps[s]['price']) / budget) for s in SYMBOLS] + [abs(weights['CASH'] - cash / budget)])
    band = number(signal['rebalance_band'])
    require(0 < band <= 1, '재조정 문턱 오류')
    reserve = max(reserve_floor, budget * weights['CASH'])
    orders = []
    target = {s: (budget * weights[s] / number(ps[s]['price'])).to_integral_value(rounding=ROUND_FLOOR) for s in SYMBOLS}
    if gap >= band:
        for s in SYMBOLS:
            p = ps[s]
            qty = min(number(p['sellable']), max(Decimal(0), number(p['quantity']) - target[s])).to_integral_value(rounding=ROUND_FLOOR)
            if qty:
                limit = (number(p['price']) * (1 - buffer)).quantize(Decimal('.01'), rounding=ROUND_FLOOR)
                require(limit > 0, '지정가가 0 이하입니다')
                orders.append({'symbol': s, 'side': 'sell', 'quantity': int(qty), 'limit': str(limit), 'vendor': p['vendor']})
        # Sell phase and buy phase need separate reviewed plans. Unfilled sale proceeds never fund purchases.
        if not orders:
            spendable = max(Decimal(0), min(cash - reserve, number(a['buying_power']) - reserve))
            for s in sorted(SYMBOLS, key=lambda s: budget * weights[s] - number(ps[s]['quantity']) * number(ps[s]['price']), reverse=True):
                p = ps[s]
                limit = (number(p['price']) * (1 + buffer)).quantize(Decimal('.01'), rounding=ROUND_CEILING)
                need = max(Decimal(0), target[s] - number(p['quantity'])).to_integral_value(rounding=ROUND_FLOOR)
                qty = min(need, (spendable / (limit * (1 + fee))).to_integral_value(rounding=ROUND_FLOOR))
                if qty:
                    orders.append({'symbol': s, 'side': 'buy', 'quantity': int(qty), 'limit': str(limit), 'vendor': p['vendor']})
                    spendable -= qty * limit * (1 + fee)
    notional = sum(number(o['limit']) * o['quantity'] for o in orders)
    max_order, max_daily = number(policy['max_order_usd']), number(policy['max_daily_usd'])
    require(max_order > 0 and max_daily > 0, '운용 한도는 0보다 커야 합니다')
    limits = [o['symbol'] + ' 건별 주문 한도 초과' for o in orders if number(o['limit']) * o['quantity'] > max_order]
    if notional > max_daily:
        limits.append('일일 주문 한도 초과')
    delta = sum(number(o['limit']) * o['quantity'] * ((1 - fee) if o['side'] == 'sell' else -(1 + fee)) for o in orders)
    return {'id': uuid.uuid4().hex, 'created_at': now.isoformat(), 'expires_at': (now + timedelta(minutes=5)).isoformat(),
        'model_id': signal['model_id'], 'model_fingerprint': signal['model_fingerprint'], 'signal_date': signal['market_date'],
        'account_id': a['account_id'], 'account_identity': identity(a), 'demo': a['demo'], 'orders': orders,
        'stage': 'sell' if orders and orders[0]['side'] == 'sell' else 'buy' if orders else 'hold',
        'execution': 'regular_limit', 'budget_usd': str(budget), 'cash_after_usd': str(cash + delta),
        'reserve_usd': str(reserve), 'notional_usd': str(notional), 'gap': float(gap), 'band': float(band),
        'limits': limits, 'state': 'draft', 'policy_hash': digest(policy), 'signal_hash': digest(signal)}


class Service:
    def __init__(self, broker, db_path, signal_loader=None, clock=utc, policy_path=None):
        self.broker, self.clock = broker, clock
        self.lock = threading.RLock()
        self.stop_orders = threading.Event()
        self.policy_path = Path(policy_path) if policy_path else ROOT / 'runtime/execution-policy.json'
        self.signal_loader = signal_loader or self.load_signal
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(str(db_path), check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript('''
            PRAGMA journal_mode=WAL;
            PRAGMA synchronous=FULL;
            CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS plans (id TEXT PRIMARY KEY, body TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS orders (id TEXT PRIMARY KEY, plan_id TEXT NOT NULL, body TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS marks (day TEXT PRIMARY KEY, body TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY, at TEXT NOT NULL, message TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS automatic_days (day TEXT PRIMARY KEY);
        ''')
        stored = self.get('broker_account', '')
        require(not stored or stored == broker.account_id, '계좌가 바뀌었습니다. 별도 데이터베이스 경로를 사용하세요')
        self.set('broker_account', broker.account_id)
        if self.get('paused', False):
            self.stop_orders.set()
        # A process stopped during submission must not silently retry on restart.
        for row in self.db.execute('SELECT id, body FROM orders').fetchall():
            o = json.loads(row['body'])
            if o['state'] == 'submitting':
                o['state'] = 'unknown'
                self.store_order(o)
        self.error = ''

    def get(self, key, default=None):
        row = self.db.execute('SELECT value FROM settings WHERE key=?', (key,)).fetchone()
        return json.loads(row[0]) if row else default

    def set(self, key, value):
        self.db.execute('INSERT OR REPLACE INTO settings VALUES (?,?)', (key, dump(value)))
        self.db.commit()

    def event(self, message):
        self.db.execute('INSERT INTO events(at,message) VALUES (?,?)', (self.clock().isoformat(), message))
        self.db.commit()

    def policy(self):
        defaults = DEFAULT_POLICY | ({'max_order_usd': 5000, 'max_daily_usd': 20000} if self.broker.demo else {})
        if not self.policy_path.exists():
            return defaults
        obj = json.loads(self.policy_path.read_text(encoding='utf-8-sig'))
        require(set(obj) <= set(DEFAULT_POLICY), '알 수 없는 실행 정책 항목')
        result = defaults | obj
        require(all(isinstance(result[k], bool) for k in ('allow_live', 'allow_auto', 'execution_reviewed')), '실행 정책의 허용 값은 true/false여야 합니다')
        return result

    def load_signal(self):
        from models.registry import load_model
        signal = json.loads((ROOT / 'site/snapshot.json').read_text(encoding='utf-8'))
        if self.broker.demo:
            return signal
        health = json.loads((ROOT / 'site/status.json').read_text(encoding='utf-8'))
        model = load_model()
        require(signal.get('mode') == 'live' and health.get('state') == 'ready', '확정 종가 신호 서버 연결이 필요합니다')
        for doc in (signal, health):
            require(doc.get('model_id') == model.id and doc.get('model_fingerprint') == model.fingerprint, '모델 지문 불일치')
        require(signal['market_date'] == health['market_date'] and parse_time(signal['valid_until']) > self.clock(), '신호 유효기간 만료')
        from pipeline import session
        latest = session(self.clock())
        require(latest is not None and signal['market_date'] == str(latest[0].date()), '최신 완료 거래일의 신호가 아닙니다')
        return signal

    def live_reasons(self, signal=None):
        policy = self.policy()
        reasons = []
        if not policy['allow_live']:
            reasons.append('실주문 사용 설정 전')
        if not policy['execution_reviewed'] or policy['execution'] != 'regular_limit':
            reasons.append('정규장 지정가 실행 검증 전')
        try:
            signal = signal or self.signal_loader()
            if policy['approved_model_fingerprint'] != signal['model_fingerprint']:
                reasons.append('현재 모델에 대한 실행 검증 전')
        except ValueError as e:
            reasons.append(str(e))
        return reasons

    def check_window(self):
        if self.broker.demo:
            return
        import pandas_market_calendars as calendars
        now = self.clock()
        day = now.astimezone(ZoneInfo('America/New_York')).date()
        schedule = calendars.get_calendar('NYSE').schedule(start_date=day, end_date=day)
        require(not schedule.empty and schedule.market_open.iloc[0].to_pydatetime() <= now < schedule.market_close.iloc[0].to_pydatetime() - timedelta(minutes=10),
                '미국 정규장 주문 시간 외입니다. 마감 10분 전부터 신규 주문을 중단합니다')

    def store_plan(self, p):
        self.db.execute('INSERT OR REPLACE INTO plans VALUES (?,?)', (p['id'], dump(p)))
        self.db.commit()

    def store_order(self, o):
        self.db.execute('INSERT OR REPLACE INTO orders VALUES (?,?,?)', (o['id'], o['plan_id'], dump(o)))
        self.db.commit()

    def claim_plan(self, p, state, automatic=False, day=None):
        """Claim once across server processes; commit the claim before any broker I/O."""
        try:
            self.db.execute('BEGIN IMMEDIATE')
            row = self.db.execute('SELECT body FROM plans WHERE id=?', (p['id'],)).fetchone()
            require(row is not None, '주문안을 찾을 수 없습니다')
            current = json.loads(row[0])
            if current['state'] != 'draft':
                self.db.rollback()
                return current, False
            require(current == p, '주문안이 변경되었습니다. 다시 계산하세요')
            require(self.clock() < parse_time(p['expires_at']), '주문안이 만료되었습니다. 다시 계산하세요')
            if state == 'executing':
                require(not self.get('paused', False), '신규 주문이 중지되어 있습니다')
                require(self.get('mode', 'observe') == ('auto' if automatic else 'confirm'), '운용 모드가 변경되었습니다')
                policy = self.policy()
                require(p['policy_hash'] == digest(policy), '운용 설정이 변경되었습니다. 다시 계산하세요')
                require(not any(o['state'] in ACTIVE for o in self.orders()), '이전 주문 처리 확인이 필요합니다')
                # An executing plan also covers the interval before its first order is stored.
                # A crashed process keeps this durable claim; never guess that it sent nothing.
                require(not any(json.loads(r[0])['state'] == 'executing' for r in self.db.execute('SELECT body FROM plans')),
                        '실행 중이거나 결과 확인이 필요한 주문안이 있습니다')
                used = sum(number(o['limit']) * o['quantity'] for o in self.orders()
                           if o['day'] == day and o['demo'] == self.broker.demo)
                require(used + number(p['notional_usd']) <= number(policy['max_daily_usd']), '일일 누적 주문 한도 초과')
            p = current | {'state': state}
            self.db.execute('UPDATE plans SET body=? WHERE id=?', (dump(p), p['id']))
            self.db.commit()
            return p, True
        except Exception:
            self.db.rollback()
            raise

    def orders(self):
        return [json.loads(r[0]) for r in self.db.execute('SELECT body FROM orders ORDER BY rowid DESC')]

    def sync(self):
        with self.lock:
            try:
                a = self.broker.snapshot()
                validate_account(a, self.clock())
                self.set('account', a)
                self.reconcile(a)
                # One per Seoul date; valuation change is explicitly NOT cashflow-adjusted profit.
                day = self.clock().astimezone(ZoneInfo('Asia/Seoul')).date().isoformat()
                total = (number(a['cash_usd']) + sum(number(p['quantity']) * number(p['price']) for p in a['positions'])) * number(a['fx']) + number(a['cash_krw'])
                mark = {'day': day, 'as_of': a['as_of'], 'total_krw': str(total), 'positions': a['positions'],
                    'cash_usd': a['cash_usd'], 'cash_krw': a['cash_krw'], 'fx': a['fx'], 'flow_adjusted': False, 'demo': a['demo']}
                self.db.execute('INSERT OR REPLACE INTO marks VALUES (?,?)', (day, dump(mark)))
                self.db.commit()
                self.error = ''
                return a
            except Exception as e:
                self.error = str(e) if isinstance(e, ValueError) else '계좌 연결 실패: 서버 연결·인증 설정을 확인하세요'
                raise ValueError(self.error) from None

    def reconcile(self, a):
        records = a['orders']
        pending = [o for o in self.orders() if o['state'] in ACTIVE and o.get('broker_id')]
        if pending and not self.broker.demo and hasattr(self.broker, 'history'):
            start = min(parse_time(o['at']).astimezone(ZoneInfo('America/New_York')).strftime('%Y%m%d') for o in pending)
            end = self.clock().astimezone(ZoneInfo('Asia/Seoul')).strftime('%Y%m%d')
            records = self.broker.history(start, end)
        for o in self.orders():
            if o['state'] not in ACTIVE or not o.get('broker_id'):
                continue
            dates = {parse_time(o['at']).astimezone(ZoneInfo(z)).strftime('%Y%m%d') for z in ('Asia/Seoul', 'America/New_York')}
            matches = [b for b in records if str(b.get('oder_no')) == o['broker_id'] and
                str(b.get('oder_date')) in ({o['broker_date']} if o.get('broker_date') else dates) and
                b.get('ovrs_stck_symb_iscd') == o['symbol'] and number(b.get('odqt', 0)) == o['quantity'] and
                str(b.get('seln_byov_clcd')) == ('2' if o['side'] == 'buy' else '1') and str(b.get('orgl_oder_no', 0)) == '0']
            if len(matches) != 1:
                # Never guess a fill or resubmit when an order disappears from today's results.
                continue
            b = matches[0]
            o['broker_date'] = str(b['oder_date'])
            filled = number(b['cntg_qty'])
            require(0 <= filled <= o['quantity'], '체결수량 불일치')
            o.update(filled=str(filled), fill_price=str(number(b['cntg_crnc_prc'])))
            if filled == o['quantity']:
                o['state'] = 'filled'
            elif str(b.get('ovrs_stck_oder_stat_cls_code')) in ('1', '2', '3') or number(b.get('cncl_cnfr_qty', 0)) >= o['quantity'] - filled:
                o['state'] = 'closed'
            else:
                o['state'] = 'partial' if filled else 'accepted'
            self.store_order(o)

    def resolve(self, order_id, broker_id, broker_date):
        """Operator supplies the observed broker identifier; never mark 'not sent' by assumption."""
        with self.lock:
            require(not self.broker.demo, '예시 계좌에는 대조할 실제 주문이 없습니다')
            row = self.db.execute('SELECT body FROM orders WHERE id=?', (order_id,)).fetchone()
            require(row is not None, '주문 기록 없음')
            o = json.loads(row[0])
            require(o['state'] == 'unknown', '결과 미확인 주문만 대조할 수 있습니다')
            require(str(broker_id).isdigit() and len(str(broker_id)) <= 20, '메리츠 주문번호를 확인하세요')
            datetime.strptime(broker_date, '%Y%m%d')
            require(broker_date in {parse_time(o['at']).astimezone(ZoneInfo(z)).strftime('%Y%m%d') for z in ('Asia/Seoul', 'America/New_York')}, '전송 시점과 다른 거래일입니다')
            require(not any(x.get('broker_id') == str(broker_id) and x.get('broker_date') == broker_date for x in self.orders()), '이미 연결된 주문번호')
            rows = self.broker.history(broker_date, broker_date)
            matches = [b for b in rows if str(b.get('oder_no')) == str(broker_id) and
                b.get('ovrs_stck_symb_iscd') == o['symbol'] and number(b.get('odqt', 0)) == o['quantity'] and
                str(b.get('seln_byov_clcd')) == ('2' if o['side'] == 'buy' else '1') and
                number(b.get('ovrs_stck_oder_prc', -1)) == number(o['limit']) and str(b.get('orgl_oder_no', 0)) == '0']
            require(len(matches) == 1, '종목·수량·가격이 일치하는 주문을 확인할 수 없습니다')
            o.update(broker_id=str(broker_id), broker_date=broker_date, state='accepted')
            self.store_order(o)
            self.sync()
            self.event('결과 미확인 주문을 증권사 주문번호와 대조했습니다')

    def create_plan(self):
        with self.lock:
            require(not any(o['state'] in ACTIVE for o in self.orders()), '접수·부분체결·결과 미확인 주문을 먼저 확인하세요')
            signal = self.signal_loader()
            a = self.sync()
            p = make_plan(a, signal, self.policy(), self.clock())
            self.store_plan(p)
            self.event(('예시 ' if p['demo'] else '') + '주문안 생성 · ' + p['id'][:8])
            return p

    def mode(self, mode):
        with self.lock:
            require(mode in ('observe', 'confirm', 'auto'), '지원하지 않는 운용 모드')
            if mode != 'observe' and not self.broker.demo:
                require(not self.live_reasons(), ' / '.join(self.live_reasons()))
            if mode == 'auto':
                require(self.policy()['allow_auto'] and not self.broker.demo, '자동 모드는 실주문 검증 후 서버에서 별도로 활성화합니다')
            self.set('mode', mode)
            self.event('운용 모드 변경 · ' + mode)

    def pause(self, paused):
        if paused:
            self.stop_orders.set()  # Stop the next order even while one request is in flight.
        with self.lock:
            if not paused:
                self.stop_orders.clear()
            self.set('paused', bool(paused))
            self.event('신규 주문 중지' if paused else '신규 주문 중지 해제')

    def simulate(self, plan_id):
        with self.lock:
            row = self.db.execute('SELECT body FROM plans WHERE id=?', (plan_id,)).fetchone()
            require(row is not None, '주문안을 찾을 수 없습니다')
            p = json.loads(row[0])
            if p['state'] != 'draft':
                return p
            require(self.clock() < parse_time(p['expires_at']), '주문안이 만료되었습니다')
            require(p['signal_hash'] == digest(self.signal_loader()), '모델·신호 변경: 다시 계산하세요')
            a = self.sync()
            require(identity(a) == p['account_identity'], '계좌 변경: 다시 계산하세요')
            prices = {v['symbol']: number(v['price']) for v in a['positions']}
            day = self.clock().astimezone(ZoneInfo('America/New_York')).date().isoformat()
            p, claimed = self.claim_plan(p, 'simulating')
            if not claimed:
                return p
            for n, item in enumerate(p['orders']):
                price = prices[item['symbol']]
                fillable = price <= number(item['limit']) if item['side'] == 'buy' else price >= number(item['limit'])
                self.store_order(item | {'id': p['id']+'-'+str(n), 'plan_id': p['id'], 'day': day,
                    'at': self.clock().isoformat(), 'state': 'simulated', 'filled': str(item['quantity'] if fillable else 0),
                    'fill_price': str(price) if fillable else '0', 'demo': True})
            p['state'] = 'simulated'
            self.store_plan(p)
            self.event('관찰 기록 · 조회 가격 가상 체결 · 호가 잔량/실제 체결 보장 아님')
            return p

    def execute(self, plan_id, automatic=False):
        with self.lock:
            row = self.db.execute('SELECT body FROM plans WHERE id=?', (plan_id,)).fetchone()
            require(row is not None, '주문안을 찾을 수 없습니다')
            p = json.loads(row[0])
            # Durable one-shot idempotency: duplicate approval returns the existing state.
            if p['state'] != 'draft':
                return p
            require(not self.get('paused', False), '신규 주문이 중지되어 있습니다')
            mode = self.get('mode', 'observe')
            require(mode == ('auto' if automatic else 'confirm'), '반자동 모드에서 주문안을 확인하세요')
            require(self.clock() < parse_time(p['expires_at']), '주문안이 만료되었습니다. 다시 계산하세요')
            require(p['orders'] and not p['limits'], '주문 없음 또는 운용 한도 초과')
            policy = self.policy()
            require(p['policy_hash'] == digest(policy), '운용 설정이 변경되었습니다. 다시 계산하세요')
            signal = self.signal_loader()
            require(p['signal_hash'] == digest(signal), '모델·신호가 변경되었습니다. 다시 계산하세요')
            if not self.broker.demo:
                require(not self.live_reasons(signal), ' / '.join(self.live_reasons(signal)))
                require(not automatic or policy['allow_auto'], '자동 운용이 비활성화되었습니다')
            self.check_window()
            require(not any(o['state'] in ACTIVE for o in self.orders()), '이전 주문 처리 확인이 필요합니다')
            a = self.sync()
            require(identity(a) == p['account_identity'] and not has_open_orders(a), '잔고·매수가능금액·미체결이 바뀌었습니다. 다시 계산하세요')
            prices = {v['symbol']: number(v['price']) for v in a['positions']}
            for o in p['orders']:
                require((prices[o['symbol']] <= number(o['limit']) if o['side'] == 'buy' else prices[o['symbol']] >= number(o['limit'])), '가격이 주문 범위를 벗어났습니다. 다시 계산하세요')
            day = self.clock().astimezone(ZoneInfo('America/New_York')).date().isoformat()
            p, claimed = self.claim_plan(p, 'executing', automatic=automatic, day=day)
            if not claimed:
                return p
            for n, item in enumerate(p['orders']):
                if self.stop_orders.is_set() or self.get('paused', False):
                    p['state'] = 'needs_review'
                    self.store_plan(p)
                    self.event('중지 요청으로 남은 주문 전송 중단')
                    return p
                o = item | {'id': p['id'] + '-' + str(n), 'plan_id': p['id'], 'day': day,
                    'at': self.clock().isoformat(), 'state': 'submitting', 'filled': '0', 'demo': self.broker.demo}
                self.store_order(o)  # Commit before touching the broker.
                try:
                    if self.broker.demo:
                        o.update(state='simulated', filled=str(o['quantity']), fill_price=str(prices[o['symbol']]))
                    else:
                        o['broker_id'] = self.broker.submit(o)
                        o['broker_date'] = ''  # Resolve using the broker's date, not a guessed timezone.
                        o['state'] = 'accepted'
                    self.store_order(o)
                except OrderRejected as e:
                    o['state'] = 'rejected'
                    self.store_order(o)
                    self.set('paused', True)
                    self.stop_orders.set()
                    p['state'] = 'needs_review'
                    self.store_plan(p)
                    self.event(str(e) + ' · 남은 주문 중단')
                    return p
                except Exception:
                    o['state'] = 'unknown'
                    self.store_order(o)
                    self.set('paused', True)
                    self.stop_orders.set()
                    p['state'] = 'needs_review'
                    self.store_plan(p)
                    self.event('주문 결과 미확인 · 자동 재전송 중단 · 메리츠 앱과 대조 필요')
                    return p
            p['state'] = 'simulated' if self.broker.demo else 'submitted'
            self.store_plan(p)
            self.event('가상 체결 기록 · 실제 잔고 변경 없음' if self.broker.demo else '주문 접수 · 체결 확인 대기')
            return p

    def loan_timing(self):
        from .timing import timing
        try:
            signal = self.signal_loader()
            result = timing(signal.get('state'), signal.get('loan_timing', {}).get('previous_state'), signal.get('model_fingerprint'))
            result['market_date'] = signal.get('market_date')
            result['valid_until'] = signal.get('valid_until')
            result['available'] = not self.broker.demo and signal.get('mode') == 'live' and result['supported'] and parse_time(signal['valid_until']) > self.clock()
            return result
        except Exception:
            return timing(None, None, None) | {'available': False}

    def status(self):
        with self.lock:
            row = self.db.execute('SELECT body FROM plans ORDER BY rowid DESC LIMIT 1').fetchone()
            policy = self.policy()
            return {'demo': self.broker.demo, 'mode': self.get('mode', 'observe'), 'paused': self.get('paused', False),
                'account': self.get('account'), 'plan': json.loads(row[0]) if row else None,
                'orders': self.orders(), 'history': [json.loads(r[0]) for r in self.db.execute('SELECT body FROM marks ORDER BY day DESC LIMIT 365')],
                'events': [dict(r) for r in self.db.execute('SELECT at,message FROM events ORDER BY id DESC LIMIT 30')],
                'blocked_reasons': [] if self.broker.demo else self.live_reasons(),
                'auto_available': not self.broker.demo and policy['allow_auto'] and not self.live_reasons(),
                'policy': {k: policy[k] for k in ('max_order_usd', 'max_daily_usd', 'reserve_usd', 'execution')},
                  'loan_timing': self.loan_timing(), 'error': self.error}

    def tick(self):
        """Periodic reads. Automatic trading remains disabled by default and after a restart."""
        with self.lock:
            self.sync()
            if self.get('mode', 'observe') != 'auto' or self.get('paused', False):
                return
            if self.live_reasons():
                self.mode('observe')
                self.event('모델·실행 설정 변경으로 자동 모드 해제')
                return
            self.check_window()
            signal = self.signal_loader()
            day = signal['market_date'] + ':' + signal['model_fingerprint']
            if self.db.execute('SELECT 1 FROM automatic_days WHERE day=?', (day,)).fetchone():
                return
            p = self.create_plan()
            if not p['orders'] or p['limits']:
                return
            self.db.execute('INSERT INTO automatic_days VALUES (?)', (day,))
            self.db.commit()
            self.execute(p['id'], automatic=True)
