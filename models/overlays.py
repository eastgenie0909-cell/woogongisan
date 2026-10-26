"""Causal Cross5/GLD21 then D25, matching audited V1.3-D semantics."""
import numpy as np
from .core_v1 import evaluate as core_evaluate

def cross_defense(p, btc, profile):
    target, ind = core_evaluate(p, btc)
    c = profile['cross']
    prices = p[['TQQQ', 'SOXL', 'BRK.B', 'GLD']].copy()
    prices['BTC'] = btc.shift(1).reindex(p.index)
    ret = prices.pct_change(c['look'], fill_method=None).reindex(target.index)
    goldret = prices.GLD.pct_change(c['gold'], fill_method=None).reindex(target.index)
    regular = ind.state.isin([2, 3, 4]).to_numpy()
    chosen = np.where(ind.defense_fast > ind.defense_slow, 'GLD', 'BRK.B')
    selected = np.where(chosen == 'GLD', ret.GLD, ret['BRK.B'])
    raw = (regular & (ret['BRK.B'].to_numpy() < 0) & (ret.GLD.to_numpy() < 0)
           & ((ret[['TQQQ', 'SOXL', 'BTC']] < 0).sum(axis=1).to_numpy() >= c['riskmin'])
           & (selected <= c['loss']) & ((chosen != 'GLD') | (goldret.to_numpy() < 0)))
    raw &= ret.notna().all(axis=1).to_numpy() & goldret.notna().to_numpy()
    cash = np.zeros(len(target), dtype=bool)
    age = cool = 0
    for i in range(len(target)):
        if not regular[i]:
            age = cool = 0
            continue
        if age:
            if age < c['minhold'] or (raw[i] and age < c['maxhold']):
                cash[i] = True
                age += 1
            else:
                age = 0
                cool = c['cooldown']
        elif cool:
            cool -= 1
        elif raw[i]:
            cash[i] = True
            age = 1
    target.loc[cash, :] = [0, 0, 0, 1, 0, 0]
    d = profile['defense']
    sma = p.QQQ.rolling(d['qqq_sma']).mean().reindex(target.index)
    weak = p.QQQ.reindex(target.index) < sma
    defensive = target[['BRK.B', 'GLD']].sum(axis=1)
    mask = weak & (defensive > 1e-9)
    if not d['applies_to_forced_BRK']:
        mask &= ind.state.isin([2, 3, 4])
    moved = defensive.loc[mask] * d['cash_fraction']
    target.loc[mask, ['BRK.B', 'GLD']] *= 1 - d['cash_fraction']
    target.loc[mask, 'CASH'] += moved
    ind['cross_trigger'] = raw
    ind['cross_cash'] = cash
    ind['qqq_sma'] = sma
    ind['defensive_cash'] = mask
    ind['cash_fraction'] = np.where(mask, d['cash_fraction'], 0.)
    assert np.isfinite(target).all().all() and target.min().min() > -1e-12
    assert np.allclose(target.sum(axis=1), 1)
    return target, ind
