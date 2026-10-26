package com.woogong.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random
import kotlin.math.abs

class PortfolioEngineTest {
 private fun account()=Account(positions=PortfolioEngine.symbols.map{Position(it,0.0,0.0,100.0)},cashUsd=1000.0,cashKrw=10000.0,capitalKrw=1000000.0,fx=1300.0,priceDate="2026-10-26",fxDate="2026-10-26")
 private fun weights(asset:String="GLD",cash:Double=0.0)=(PortfolioEngine.symbols+"CASH").associateWith{if(it=="CASH")cash else if(it==asset)1-cash else 0.0}
 private fun mark(day:String,value:Double,capital:Double)=DailyMark(day,day,day,value,capital,0.0,0.0,1300.0,emptyList(),"manual",flowKrw=capital)
 @Test fun valuesBothCurrencies(){val a=account().copy(positions=listOf(Position("GLD",2.0,90.0,100.0)));val v=PortfolioEngine.value(a);assertEquals(1570000.0,v.totalKrw,.001);assertEquals(570000.0,v.profitKrw!!,.001)}
 @Test fun capitalIsNotInvestmentProfit(){val first=mark("2026-10-25",1000.0,1000.0);val next=mark("2026-10-26",1250.0,1200.0);assertEquals(50.0,PortfolioEngine.movement(next,first),0.0)}
 @Test fun withdrawalIsNotInvestmentLoss(){assertEquals(30.0,PortfolioEngine.movement(mark("2026-10-26",830.0,800.0),mark("2026-10-25",1000.0,1000.0)),0.0)}
 @Test fun correctsSameDayWithoutDuplicates(){val a=mark("2026-10-26",100.0,100.0);val history=PortfolioEngine.upsert(listOf(a),a.copy(totalKrw=110.0));assertEquals(1,history.size);assertEquals(110.0,history[0].totalKrw,0.0)}
 @Test fun historyRemainsChronological(){val a=mark("2026-10-25",100.0,100.0);assertEquals("2026-10-25",PortfolioEngine.upsert(listOf(a.copy(date="2026-10-26")),a).first().date)}
 @Test fun feesPreventUnaffordableTenthShare(){val p=PortfolioEngine.plan(account(),weights());assertEquals(9L,p.rows.first{it.symbol=="GLD"}.buy);assertTrue(p.cashAfter>0);assertTrue(p.estimatedCost>0)}
 @Test fun reserveIsPreserved(){val p=PortfolioEngine.plan(account(),weights(cash=.25));assertTrue(p.cashAfter>=250.0);assertEquals(7L,p.rows.first{it.symbol=="GLD"}.buy)}
 @Test fun krwCashIsNotAutomaticallyConverted(){val p=PortfolioEngine.plan(account().copy(cashKrw=1e9),weights());assertEquals(1000.0,p.budget,0.0);assertEquals(9L,p.rows.first{it.symbol=="GLD"}.buy)}
 @Test fun outsideStocksAreExcludedFromTrading(){val a=account().copy(positions=account().positions+Position("AAPL",5.0,90.0,200.0));val p=PortfolioEngine.plan(a,weights());assertEquals(1000.0,p.outsideStrategy,0.0);assertEquals(1000.0,p.budget,0.0);assertFalse(p.rows.any{it.symbol=="AAPL"})}
 @Test fun sellsFundBuysAndNeverOversells(){val a=account().copy(cashUsd=0.0,positions=account().positions.map{if(it.symbol=="TQQQ")it.copy(quantity=10.0) else it});val p=PortfolioEngine.plan(a,weights());assertEquals(10L,p.rows.first{it.symbol=="TQQQ"}.sell);assertEquals(9L,p.rows.first{it.symbol=="GLD"}.buy);assertTrue(p.cashAfter>=0)}
 @Test fun cashOnlyTargetSellsWithoutBorrowing(){val a=account().copy(positions=account().positions.map{it.copy(quantity=1.0)});val p=PortfolioEngine.plan(a,weights(cash=1.0));assertTrue(p.rows.all{it.buy==0L&&it.sell==1L});assertTrue(p.cashAfter>1000)}
 @Test fun fractionalHoldingsRemainVisible(){val a=account().copy(positions=account().positions.map{if(it.symbol=="TQQQ")it.copy(quantity=1.4)else it});val p=PortfolioEngine.plan(a,weights(cash=1.0));assertEquals(.4,p.rows.first{it.symbol=="TQQQ"}.after,1e-8);assertTrue(p.fractionalRemainder)}
 @Test(expected=IllegalArgumentException::class) fun missingPriceBlocksValuation(){PortfolioEngine.value(account().copy(positions=listOf(Position("GLD",1.0))))}
 @Test(expected=IllegalArgumentException::class) fun missingTargetPriceBlocksPlan(){PortfolioEngine.plan(account().copy(positions=emptyList()),weights())}
 @Test(expected=IllegalArgumentException::class) fun invalidWeightsBlockPlan(){PortfolioEngine.plan(account(),weights().mapValues{.5})}
 @Test(expected=IllegalArgumentException::class) fun nonFiniteCashIsRejected(){PortfolioEngine.value(account().copy(cashUsd=Double.NaN))}
 @Test(expected=IllegalArgumentException::class) fun negativeHoldingsAreRejected(){PortfolioEngine.value(account().copy(positions=listOf(Position("GLD",-1.0,10.0,10.0))))}
 @Test fun unknownCapitalHidesProfit(){assertNull(PortfolioEngine.value(account().copy(capitalKrw=0.0,capitalKnown=false)).profitKrw)}
 @Test fun depositsDoNotInventUnknownHistoricalPrincipal(){assertNull(PortfolioEngine.value(account().copy(capitalKrw=200000.0,capitalKnown=false)).profitKrw)}
 @Test fun correctingHistoricalPrincipalDoesNotInventDailyLoss(){val a=mark("2026-10-25",1000.0,1000.0).copy(flowKrw=0.0);val b=a.copy(date="2026-10-26",totalKrw=1050.0,capitalKrw=1200.0);assertEquals(50.0,PortfolioEngine.movement(b,a),0.0)}
 @Test fun knownZeroPrincipalStillHasAbsoluteProfit(){assertEquals(1310000.0,PortfolioEngine.value(account().copy(capitalKrw=0.0,capitalKnown=true)).profitKrw!!,.001)}
 @Test fun randomizedPlansAreFunded(){
  val rng=Random(20261026)
  repeat(300){
   val positions=PortfolioEngine.symbols.map{Position(it,rng.nextInt(0,1000).toDouble(),10.0,rng.nextDouble(1.0,500.0))}
   val a=account().copy(positions=positions,cashUsd=rng.nextDouble(1.0,10000.0),fee=.003,slippage=.01)
   val raw=(PortfolioEngine.symbols+"CASH").associateWith{rng.nextDouble()};val sum=raw.values.sum();val w=raw.mapValues{it.value/sum}
   val p=PortfolioEngine.plan(a,w)
   assertTrue(p.cashAfter>=0)
   assertTrue(p.rows.all{it.after>=0&&it.sell<=it.current&&it.buy>=0})
   val final=p.rows.sumOf{it.after*it.price}+p.cashAfter
   assertTrue(abs(final+p.estimatedCost-p.budget)<1e-6)
  }
 }
}
