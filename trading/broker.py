"""Meritz REST adapter. Credentials never enter public market files or clients.

Contract: https://openapi.imeritz.com/apiservice (2026-10-26).
No automatic retries of order requests, including token errors and timeouts.
"""
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import os
import time
import uuid
import httpx

OK = {'0000', '0001', '5620', '5762', '5766', '5820', '5822'}
SYMBOLS = ('TQQQ', 'SOXL', 'BITO', 'BRK.B', 'GLD')
EXCHANGES = {'0537': 'OQ', '0321': 'NY', '0066': 'AX'}


def number(value):
    try:
        n = Decimal(str(value).replace(',', ''))
        if not n.is_finite():
            raise ValueError('유한한 금액이 필요합니다')
        return n
    except (InvalidOperation, TypeError):
        raise ValueError('증권사 숫자 형식을 확인하세요') from None


def utc():
    return datetime.now(timezone.utc)


class BrokerError(ValueError):
    pass


class OrderRejected(BrokerError):
    """Known no-order result. A hold/unknown response is deliberately not this type."""
    pass


class Meritz:
    demo = False

    def __init__(self, client=None):
        self.key = os.environ.get('MERITZ_APP_KEY', '')
        self.secret = os.environ.get('MERITZ_APP_SECRET', '')
        self.client = client or httpx.Client(base_url='https://openapi.imeritz.com:9443', timeout=20, follow_redirects=False)
        self.token = ''
        self.expires = 0
        self.last = 0
        self.account_id = hashlib.sha256(self.key.encode()).hexdigest()[:16]

    def access_token(self):
        if not self.key or not self.secret:
            raise BrokerError('서버에 MERITZ_APP_KEY / MERITZ_APP_SECRET을 설정하세요')
        if time.monotonic() >= self.expires:
            r = self.client.post('/oauth2/token', data={'grant_type': 'client_credentials',
                'client_id': self.key, 'client_secret': self.secret, 'scope': 'login'})
            obj = r.json()
            if r.status_code != 200 or not obj.get('access_token'):
                raise BrokerError('메리츠 인증 실패: ' + str(obj.get('rsp_cd', r.status_code)))
            self.token = obj['access_token']
            self.expires = time.monotonic() + max(0, int(obj['expires_in']) - 60)
        return self.token

    def call(self, path, params=None, body=None):
        token = self.access_token()
        time.sleep(max(0, .15 - (time.monotonic() - self.last)))
        self.last = time.monotonic()
        headers = {'authorization': 'Bearer ' + token, 'mac_address': f'{uuid.getnode():012X}'}
        r = self.client.request('POST' if body is not None else 'GET', path, headers=headers,
                                params=params, json=body)
        obj = r.json()
        if not isinstance(obj, dict):
            raise BrokerError('증권사 응답 형식 오류')
        data = obj.get('data')
        if body is not None and isinstance(data, dict) and str(data.get('warn_cls_code', '')) != '0':
            error_type = OrderRejected if str(data.get('warn_cls_code')) in ('1', '3') else BrokerError
            raise error_type('증권사 경고·보류: ' + str(data.get('warn_cls_code', '?')) + ' · 메리츠 앱에서 확인하세요')
        if str(obj.get('rsp_cd', '')) not in OK:
            error_type = OrderRejected if body is not None and str(obj.get('rsp_cd')) in ('0760', '7936', 'EGW00121', 'EGW00123', 'EGW00200') else BrokerError
            raise error_type('메리츠 응답 코드 ' + str(obj.get('rsp_cd', '없음')))
        return obj

    def rows(self, path, params=None):
        query = dict(params or {})
        result, seen = [], set()
        for _ in range(100):
            obj = self.call(path, query)
            rows = obj.get('data')
            if not isinstance(rows, list):
                raise BrokerError('목록 응답 누락: ' + path)
            result.extend(rows)
            more = obj.get('tr_cont') == '1' or obj.get('rsp_cd') == '5762'
            if not more:
                return result
            key = obj.get('tr_cont_key')
            if not key or key in seen:
                raise BrokerError('연속조회 키 누락·반복: 불완전한 잔고를 사용하지 않습니다')
            seen.add(key)
            query.update(tr_cont='1', tr_cont_key=key)
        raise BrokerError('연속조회 한도 초과')

    def quote(self, symbol, vendor=None):
        vendors = [vendor] if vendor in EXCHANGES else list(EXCHANGES)
        for code in vendors:
            q = self.call('/market/v1/overseas/prices', {'mrkt_div_code': 'OV',
                'mrkt_cls_code': EXCHANGES[code], 'iscd': symbol, 'dely_rltm_cls_code': '0'}).get('data', {})
            if q.get('iscd') == symbol and q.get('crnc_code', '').strip() == 'USD' and number(q.get('prpr', 0)) > 0:
                return {'symbol': symbol, 'price': str(number(q['prpr'])), 'vendor': code}
        raise BrokerError(symbol + ' 실시간 USD 시세·거래소 확인 실패')

    def snapshot(self):
        holdings = self.rows('/accounts/v1/me/overseas/holdings')
        currencies = self.rows('/accounts/v1/me/overseas/currencies')
        estimates = self.rows('/trading/v1/overseas/orders/estimate', {'intg_marg_iqry_yn': 'N'})
        deposit = self.call('/accounts/v1/me/deposit')['data']
        orders = self.rows('/trading/v1/overseas/orders/today', {'cntg_cls_code': '0', 'seln_byov_clcd': '0'})
        positions = {}
        for h in holdings:
            if h.get('crcd') != 'USD' or h.get('ovrs_stck_mrkt_tr_cls_code') != '01' or h.get('ovrs_exch_vndr_idno') not in EXCHANGES or h.get('rmnd_clcd') not in ('1', '2') or h.get('loan_dtl_type_code') not in ('', '0000', None):
                raise BrokerError('현재 연결은 미국 USD 일반·소수점 잔고만 지원합니다')
            symbol = h['ovrs_stck_symb_iscd']
            if symbol in positions:
                p = positions[symbol]
                q = number(h['rmnd_dqty'])
                total = number(p['quantity']) + q
                p['average'] = str((number(p['average']) * number(p['quantity']) + number(h['avrg_unpr']) * q) / total) if total else '0'
                p['quantity'] = str(total)
                p['sellable'] = str(number(p['sellable']) + (number(h['seln_able_dqty']) if h['rmnd_clcd'] == '1' else 0))
            else:
                positions[symbol] = {'symbol': symbol, 'quantity': str(number(h['rmnd_dqty'])),
                    'sellable': str(number(h['seln_able_dqty']) if h['rmnd_clcd'] == '1' else 0),
                    'average': str(number(h['avrg_unpr'])), 'price': str(number(h['psnt_prc'])),
                    'vendor': h['ovrs_exch_vndr_idno']}
        usd = [c for c in currencies if c['crcd'] == 'USD']
        estimate = [c for c in estimates if c['crcd'] == 'USD']
        if len(usd) > 1 or len(estimate) > 1:
            raise BrokerError('USD 잔고 행 중복')
        if any(number(c['estm_frcr_amt']) != 0 for c in currencies if c['crcd'] != 'USD'):
            raise BrokerError('USD 외 통화 보유: 다중 통화 지원이 필요합니다')
        cash = str(number(usd[0]['estm_frcr_amt'])) if usd else '0'
        fx = str(number(usd[0]['vltn_exrt'])) if usd else '0'
        if number(fx) <= 0:
            from zoneinfo import ZoneInfo
            day = utc().astimezone(ZoneInfo('Asia/Seoul')).strftime('%Y%m%d')
            rates = self.rows('/forex/v1/rates', {'stnd_date': day, 'crcd': 'USD'})
            if len(rates) != 1:
                raise BrokerError('USD 평가환율 미도착')
            fx = str(number(rates[0]['deal_stnd_exrt']))
        for symbol in SYMBOLS:
            p = positions.setdefault(symbol, {'symbol': symbol, 'quantity': '0', 'sellable': '0', 'average': '0'})
            p.update(self.quote(symbol, p.get('vendor')))
        for symbol, p in positions.items():
            if symbol not in SYMBOLS:
                p.update(self.quote(symbol, p.get('vendor')))
        return {'account_id': self.account_id, 'source': 'meritz', 'demo': False, 'as_of': utc().isoformat(),
            'cash_usd': cash, 'cash_krw': str(number(deposit['tfam'])), 'fx': fx,
            'buying_power': str(number(estimate[0]['oder_crnc_oder_able_camt'])) if estimate else '0',
            'positions': list(positions.values()), 'orders': orders}

    def transactions(self, start, end):
        return self.rows('/accounts/v1/me/transactions', {'from': start, 'to': end, 'sort': 'ASC'})

    def history(self, start, end):
        rows = self.rows('/trading/v1/overseas/orders/history', {'oder_sta_date': start,
            'oder_fin_date': end, 'cntg_cls_code': '0', 'seln_byov_clcd': '0'})
        return [r | {'ovrs_stck_symb_iscd': r['iscd'].rsplit('.', 1)[0],
            'odqt': str(number(r['oder_dqty'])), 'cntg_qty': str(number(r['cntg_dqty'])),
            'cncl_cnfr_qty': '0'} for r in rows]

    def submit(self, order):
        body = {'ovrs_stck_symb_iscd': order['symbol'], 'ovrs_exch_vndr_idno': order['vendor'],
            'oder_cls_code': '01', 'oder_cond_cls_code': '0', 'odqt': order['quantity'],
            'ovrs_stck_oder_prc': order['limit'], 'warn_cnfr_yn': 'N'}
        obj = self.call('/trading/v1/overseas/orders/' + order['side'], body=body)
        data = obj.get('data', {})
        if not data.get('oder_no'):
            raise BrokerError('주문번호 미확인: 재전송하지 말고 계좌를 확인하세요')
        return str(data['oder_no'])


class PaperBroker:
    """Explicit fictional account. No network and no access to any credential."""
    demo = True
    account_id = 'paper-example-only'

    def snapshot(self):
        quantities = [12, 25, 43, 0, 8]
        prices = ['98.42', '35.74', '21.18', '465.20', '255.32']
        return {'account_id': self.account_id, 'source': 'paper', 'demo': True, 'as_of': utc().isoformat(),
            'cash_usd': '1428.56', 'cash_krw': '1200000', 'fx': '1350', 'buying_power': '1428.56', 'orders': [],
            'positions': [{'symbol': s, 'quantity': str(q), 'sellable': str(q), 'average': p,
                'price': p, 'vendor': '0537' if s == 'TQQQ' else '0321' if s == 'BRK.B' else '0066'}
                for s, q, p in zip(SYMBOLS, quantities, prices)]}

    def transactions(self, start, end):
        return []

    def submit(self, order):
        raise BrokerError('예시 연결은 실제 주문을 전송할 수 없습니다')
