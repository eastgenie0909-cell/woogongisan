"""Read-only Meritz market-data qualification. Never queries accounts or submits orders."""
from datetime import datetime, timedelta, timezone
from pathlib import Path
import json
import sys
import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from pipeline import session
from trading.broker import Meritz

EXCHANGES = {'QQQ': 'OQ', 'TQQQ': 'OQ', 'BRK.B': 'NY', 'GLD': 'AX', 'SOXL': 'AX', 'BITO': 'AX'}


def check():
    expected, _ = session(datetime.now(timezone.utc))
    reference = pd.read_csv(ROOT/'data/equities.csv', index_col=0, parse_dates=True)
    broker = Meritz()
    result = {'checked_at': datetime.now(timezone.utc).isoformat(), 'expected_date': str(expected.date()),
              'read_only': True, 'orders_sent': 0, 'account_queried': False, 'symbols': [],
              'live_signal_approved': False, 'note': 'Authentication/data checks only; dividend/total-return basis still requires validation.'}
    for symbol, exchange in EXCHANGES.items():
        rows = broker.rows('/market/v1/overseas/candles/days', {
            'mrkt_div_code': 'OV', 'mrkt_cls_code': exchange, 'iscd': symbol,
            'dely_rltm_cls_code': '1', 'mod_stpr_cls_code': '0',
            'from': (expected - timedelta(days=180)).strftime('%Y%m%d'), 'to': expected.strftime('%Y%m%d')})
        prices = {}
        for row in rows:
            day = pd.to_datetime(str(row['date']), format='%Y%m%d')
            value = float(row['prpr'])
            if not np.isfinite(value) or value <= 0 or day > expected:
                raise ValueError(f'{symbol}: invalid candle')
            if day in prices and prices[day] != value:
                raise ValueError(f'{symbol}: conflicting candle')
            prices[day] = value
        observed = pd.Series(prices, dtype=float).sort_index()
        overlap = observed.index.intersection(reference[symbol].dropna().index)
        gap = None
        if len(overlap) >= 2:
            gap = float((observed.loc[overlap].pct_change()-reference.loc[overlap, symbol].pct_change()).abs().max())
        result['symbols'].append({'symbol': symbol, 'rows': len(rows), 'latest_date': str(observed.index[-1].date()) if len(observed) else None,
            'complete_close_arrived': expected in observed.index, 'overlap_sessions': len(overlap),
            'max_daily_return_gap': gap, 'overlap_consistent_with_reference': gap is not None and gap < 0.0002})
    return result


def main():
    report = ROOT/'runtime/meritz-market-check.json'
    report.parent.mkdir(exist_ok=True)
    try:
        result = check()
        result['state'] = 'read_only_check_completed'
        report.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        print('Read-only Meritz check completed. Report: runtime/meritz-market-check.json')
        for row in result['symbols']:
            print(row['symbol'], row['latest_date'], 'reference basis:', row['overlap_consistent_with_reference'])
        return 0
    except Exception:
        # Never log credentials, tokens, URLs with authentication or raw broker payloads.
        report.write_text(json.dumps({'state': 'check_failed', 'read_only': True, 'orders_sent': 0,
            'message': 'Meritz authentication/data check failed. Verify the key, secret and API permissions in Meritz.'}), encoding='utf-8')
        print('Meritz read-only check failed. No account request or order was sent.')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
