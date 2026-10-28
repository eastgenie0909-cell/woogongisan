import numpy as np
import pandas as pd
import pytest
from comparison import build


def fixture():
    dates=pd.bdate_range('2024-01-02','2026-10-27')
    nav=pd.Series(np.linspace(1,2,len(dates)),index=dates)
    prices={'TQQQ':{str(d.date()):float(100+i) for i,d in enumerate(dates)},
            'BRK.B':{str(d.date()):float(400+i/10) for i,d in enumerate(dates)}}
    return nav,{'market_date':'2026-10-27','basis':'dividend_and_split_adjusted_close','prices':prices}


def test_all_three_share_dates_zero_origin_and_exact_endpoint():
    nav,source=fixture();result=build(nav,source)
    for window in result['windows'].values():
        first,last=window['points'][0],window['points'][-1]
        assert all(first[k]==0 for k in ['model','tqqq','brkb'])
        assert last['date']==result['market_date']
        assert window['returns']=={k:last[k] for k in ['model','tqqq','brkb']}
    assert result['windows']['all']['returns']['model']==1
    assert result['windows']['1m']['start']=='2026-09-25'


def test_missing_benchmark_day_is_not_silently_removed():
    nav,source=fixture();del source['prices']['TQQQ']['2025-07-08']
    with pytest.raises(ValueError,match='complete'):build(nav,source)


def test_wrong_day_basis_and_nonpositive_prices_fail():
    for kind in ['date','basis','price']:
        nav,source=fixture()
        if kind=='date':source['market_date']='2026-10-26'
        elif kind=='basis':source['basis']='raw'
        else:source['prices']['BRK.B']['2025-07-08']=0
        with pytest.raises(ValueError):build(nav,source)


def test_multiplying_levels_does_not_change_percentage_comparison():
    nav,source=fixture();a=build(nav,source)
    for symbol in source['prices']:
        source['prices'][symbol]={d:v*3 for d,v in source['prices'][symbol].items()}
    b=build(nav*7,source)
    for period in a['windows']:
        np.testing.assert_allclose(list(a['windows'][period]['returns'].values()),list(b['windows'][period]['returns'].values()),atol=1e-12)
