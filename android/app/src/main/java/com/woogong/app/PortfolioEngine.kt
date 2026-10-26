package com.woogong.app

import kotlin.math.*
import java.time.LocalDate

data class Position(val symbol:String,val quantity:Double,val average:Double=0.0,val price:Double=0.0)
data class Account(val broker:String="메리츠증권",val positions:List<Position> = emptyList(),
 val cashUsd:Double=0.0,val cashKrw:Double=0.0,val capitalKrw:Double=0.0,val fx:Double=0.0,
 val priceDate:String="",val fxDate:String="",val confirmedDate:String="",
 val fee:Double=.001,val slippage:Double=.001,val reserveUsd:Double=0.0,val capitalKnown:Boolean=capitalKrw>0,val flowKrw:Double=0.0)
data class Valuation(val stocksUsd:Double,val totalUsd:Double,val totalKrw:Double,val cashKrw:Double,val profitKrw:Double?)
data class DailyMark(val date:String,val priceDate:String,val fxDate:String,val totalKrw:Double,val capitalKrw:Double,
 val cashKrw:Double,val stocksUsd:Double,val fx:Double,val positions:List<Position>,val source:String,val capitalKnown:Boolean=true,val flowKrw:Double=0.0)
data class OrderRow(val symbol:String,val current:Double,val targetWeight:Double,val price:Double,val buy:Long,val sell:Long,val after:Double)
data class TradePlan(val rows:List<OrderRow>,val budget:Double,val cashAfter:Double,val desiredCash:Double,
 val estimatedCost:Double,val outsideStrategy:Double,val bandGap:Double,val fractionalRemainder:Boolean)

object PortfolioEngine {
 val symbols=listOf("TQQQ","SOXL","BITO","BRK.B","GLD")
 fun validDate(s:String):Boolean=try{LocalDate.parse(s);true}catch(_:Exception){false}
 fun validate(a:Account){
  require(listOf(a.cashUsd,a.cashKrw,a.fx,a.fee,a.slippage,a.reserveUsd).all{it.isFinite()&&it>=0}){"현금·환율·비용은 0 이상의 숫자여야 합니다"}
  require(a.capitalKrw.isFinite()&&a.flowKrw.isFinite()){"순투입금이 올바르지 않습니다"}
  require(a.fee<.1&&a.slippage<.1){"수수료와 가격 여유는 각각 10% 미만이어야 합니다"}
  require(a.positions.size<=30&&a.positions.map{it.symbol}.distinct().size==a.positions.size){"중복 종목 또는 종목 수 초과"}
  require(a.positions.all{it.symbol.matches(Regex("[A-Z][A-Z0-9.\\-]{0,14}"))&&listOf(it.quantity,it.average,it.price).all{v->v.isFinite()&&v>=0}}){"종목명·수량·가격을 확인하세요"}
  require(validDate(a.priceDate)&&validDate(a.fxDate)){"날짜는 YYYY-MM-DD 형식으로 입력하세요"}
 }
 fun value(a:Account):Valuation {
  validate(a);require(a.fx>0){"원화 평가를 위한 환율을 입력하세요"}
  require(a.positions.filter{it.quantity>0}.all{it.price>0}){"보유 종목의 기준가격을 입력하세요"}
  val stocks=a.positions.sumOf{it.quantity*it.price};val usd=stocks+a.cashUsd
  val total=usd*a.fx+a.cashKrw
  require(total.isFinite()){"평가액 범위를 초과했습니다"}
  return Valuation(stocks,usd,total,a.cashUsd*a.fx+a.cashKrw,if(a.capitalKnown)total-a.capitalKrw else null)
 }
 // One mutable end-of-day mark per Seoul calendar day, including same-day corrections.
 fun upsert(history:List<DailyMark>,mark:DailyMark):List<DailyMark> = (history.filter{it.date!=mark.date}+mark).sortedBy{it.date}
 fun movement(current:DailyMark,previous:DailyMark):Double=current.totalKrw-previous.totalKrw-(current.flowKrw-previous.flowKrw)
 fun plan(a:Account,weights:Map<String,Double>):TradePlan {
  value(a)
  require(weights.keys== (symbols+"CASH").toSet()&&weights.values.all{it.isFinite()&&it>=0}&&abs(weights.values.sum()-1)<1e-8){"모델의 목표 비중을 확인하세요"}
  val positions=a.positions.associateBy{it.symbol}
  for(s in symbols)if((weights[s]?:0.0)>0)require((positions[s]?.price?:0.0)>0){"$s 기준가격이 필요합니다"}
  val outside=a.positions.filter{it.symbol !in symbols}.sumOf{it.quantity*it.price}
  val budget=a.cashUsd+a.positions.filter{it.symbol in symbols}.sumOf{it.quantity*it.price}
  require(budget>0&&budget<1e12){"달러 운용자산을 입력하세요"}
  val reserve=min(budget,max(a.reserveUsd,budget*weights.getValue("CASH")))
  var cash=a.cashUsd;var cost=0.0
  val buys=mutableMapOf<String,Long>();val sells=mutableMapOf<String,Long>()
  val desired=mutableMapOf<String,Double>()
  for(s in symbols){
   val p=positions[s]?:Position(s,0.0);val w=weights.getValue(s)
   desired[s]=if(w>0)floor(budget*w/p.price) else 0.0
   val count=floor(max(0.0,p.quantity-desired.getValue(s))+1e-9).toLong()
   sells[s]=count
   val gross=count*p.price;val proceeds=gross*(1-a.slippage)*(1-a.fee)
   cash+=proceeds;cost+=gross-proceeds
  }
  val priority=symbols.sortedByDescending{s->budget*weights.getValue(s)-(positions[s]?.let{it.quantity*it.price}?:0.0)}
  for(s in priority){
   val p=positions[s]?:Position(s,0.0)
   val need=floor(max(0.0,desired.getValue(s)-p.quantity)+1e-9).toLong()
   val unit=p.price*(1+a.slippage)*(1+a.fee)
   val count=if(need>0&&unit>0)min(need,floor(max(0.0,cash-reserve)/unit+1e-9).toLong()) else 0L
   buys[s]=count;cash-=count*unit;cost+=count*(unit-p.price)
  }
  val rows=symbols.map{s->val p=positions[s]?:Position(s,0.0);OrderRow(s,p.quantity,weights.getValue(s),p.price,buys[s]?:0,sells[s]?:0,p.quantity+(buys[s]?:0)-(sells[s]?:0))}
  val gap=(symbols.map{s->abs(weights.getValue(s)-(positions[s]?.let{it.quantity*it.price}?:0.0)/budget)}+abs(weights.getValue("CASH")-a.cashUsd/budget)).max()
  require(cash>=-1e-7&&rows.all{it.after>=-1e-8}){"주문안 자금 검증 실패"}
  return TradePlan(rows,budget,max(0.0,cash),reserve,cost,outside,gap,a.positions.any{it.symbol in symbols&&abs(it.quantity-round(it.quantity))>1e-8})
 }
}
