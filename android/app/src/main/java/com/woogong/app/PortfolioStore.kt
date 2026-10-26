package com.woogong.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

data class PortfolioState(val account:Account?,val history:List<DailyMark>)
data class PricedAccount(val account:Account,val priceDate:String,val source:String,val recordable:Boolean)

object PortfolioStore {
 private const val KEY="portfolio_v1"
 fun today()=LocalDate.now(ZoneId.of("Asia/Seoul")).toString()
 private fun position(p:JSONObject)=Position(p.getString("symbol"),p.getDouble("quantity"),p.optDouble("average",0.0),p.optDouble("price",0.0))
 private fun positions(a:JSONArray)= (0 until a.length()).map{position(a.getJSONObject(it))}
 private fun json(p:Position)=JSONObject().put("symbol",p.symbol).put("quantity",p.quantity).put("average",p.average).put("price",p.price)
 private fun json(a:Account)=JSONObject().put("broker",a.broker).put("positions",JSONArray(a.positions.map{json(it)}))
  .put("cashUsd",a.cashUsd).put("cashKrw",a.cashKrw).put("capitalKrw",a.capitalKrw).put("fx",a.fx)
  .put("priceDate",a.priceDate).put("fxDate",a.fxDate).put("confirmedDate",a.confirmedDate)
  .put("fee",a.fee).put("slippage",a.slippage).put("reserveUsd",a.reserveUsd).put("capitalKnown",a.capitalKnown).put("flowKrw",a.flowKrw)
 private fun account(a:JSONObject)=Account(a.optString("broker","메리츠증권"),positions(a.getJSONArray("positions")),a.getDouble("cashUsd"),a.getDouble("cashKrw"),a.getDouble("capitalKrw"),a.getDouble("fx"),a.getString("priceDate"),a.getString("fxDate"),a.optString("confirmedDate"),a.optDouble("fee",.001),a.optDouble("slippage",.001),a.optDouble("reserveUsd",0.0),a.optBoolean("capitalKnown",a.getDouble("capitalKrw")>0),a.optDouble("flowKrw",0.0))
 @Synchronized fun read(c:Context):PortfolioState {
  val raw=Data.prefs(c).getString(KEY,null)?:return PortfolioState(null,emptyList())
  return decode(raw)
 }
 fun decode(raw:String):PortfolioState {
  require(raw.length<=10_000_000){"백업 파일이 너무 큽니다"}
  val obj=JSONObject(raw);require(obj.getInt("schema")==1){"지원하지 않는 자산 저장 형식"}
  require(!obj.optBoolean("demo",false)){"예시 자산은 실제 계좌 백업으로 복원할 수 없습니다"}
  val a=account(obj.getJSONObject("account"));PortfolioEngine.validate(a)
  val arr=obj.optJSONArray("history")?:JSONArray()
  val history=(0 until arr.length()).map{val h=arr.getJSONObject(it);DailyMark(h.getString("date"),h.getString("priceDate"),h.getString("fxDate"),h.getDouble("totalKrw"),h.getDouble("capitalKrw"),h.getDouble("cashKrw"),h.getDouble("stocksUsd"),h.getDouble("fx"),positions(h.getJSONArray("positions")),h.optString("source"),h.optBoolean("capitalKnown",h.getDouble("capitalKrw")>0),h.optDouble("flowKrw",0.0))}
  require(history.size<=20000&&history.all{h->PortfolioEngine.validDate(h.date)&&PortfolioEngine.validDate(h.priceDate)&&PortfolioEngine.validDate(h.fxDate)&&listOf(h.totalKrw,h.cashKrw,h.stocksUsd,h.fx,h.capitalKrw,h.flowKrw).all{it.isFinite()}&&h.totalKrw>=0&&h.fx>0&&h.positions.all{it.quantity.isFinite()&&it.quantity>=0&&it.price.isFinite()&&it.price>=0}}){"백업의 일별 기록을 확인하세요"}
  require(history.map{it.date}.distinct().size==history.size){"중복된 일별 기록입니다"}
  return PortfolioState(a,history.sortedBy{it.date})
 }
 @Synchronized fun write(c:Context,state:PortfolioState){
  val a=state.account?:return;PortfolioEngine.validate(a)
  val history=JSONArray(state.history.map{h->JSONObject().put("date",h.date).put("priceDate",h.priceDate).put("fxDate",h.fxDate).put("totalKrw",h.totalKrw).put("capitalKrw",h.capitalKrw).put("capitalKnown",h.capitalKnown).put("flowKrw",h.flowKrw).put("cashKrw",h.cashKrw).put("stocksUsd",h.stocksUsd).put("fx",h.fx).put("source",h.source).put("positions",JSONArray(h.positions.map{json(it)}))})
  check(Data.prefs(c).edit().putString(KEY,JSONObject().put("schema",1).put("account",json(a)).put("history",history).toString()).commit()){"자산 기록 저장 실패"}
 }
 fun priced(a:Account,s:JSONObject,status:JSONObject):PricedAccount {
  val live=!Data.stale(s,status);val day=s.optString("market_date")
  val useLive=live&&day>=a.priceDate
  val prices=s.optJSONObject("raw_close")?:JSONObject()
  var manual=false;var server=false
  val updated=a.positions.map{p->
   if(useLive&&prices.optDouble(p.symbol,0.0)>0){server=true;p.copy(price=prices.getDouble(p.symbol))}
   else{if(p.quantity>0)manual=true;p}
  }
  val date=if(manual||!server)a.priceDate else day
  val recentManual=a.priceDate==today()
  return PricedAccount(a.copy(positions=updated),date,if(server&&manual)"종가+수동" else if(server)"확정 종가" else "직접 입력",(live&&(!manual||recentManual))||recentManual)
 }
 @Synchronized fun recordDaily(c:Context,s:JSONObject=Data.load(c,"snapshot"),status:JSONObject=Data.load(c,"status")) {
  val state=read(c);val a=state.account?:return
  val priced=priced(a,s,status);if(!priced.recordable)return
  val v=try{PortfolioEngine.value(priced.account)}catch(_:IllegalArgumentException){return}
  val mark=DailyMark(today(),priced.priceDate,a.fxDate,v.totalKrw,a.capitalKrw,v.cashKrw,v.stocksUsd,a.fx,priced.account.positions,priced.source,a.capitalKnown,a.flowKrw)
  write(c,state.copy(account=priced.account.copy(priceDate=priced.priceDate),history=PortfolioEngine.upsert(state.history,mark)))
 }
 @Synchronized fun saveAccount(c:Context,a:Account){
  PortfolioEngine.validate(a);require(a.priceDate<=today()&&a.fxDate<=today()){"미래 날짜는 입력할 수 없습니다"}
  val old=read(c);write(c,old.copy(account=a.copy(confirmedDate=today(),flowKrw=old.account?.flowKrw?:0.0)))
  recordDaily(c)
 }
 @Synchronized fun cashFlow(c:Context,amount:Double,currency:String,deposit:Boolean){
  require(amount.isFinite()&&amount>0){"0보다 큰 금액을 입력하세요"}
  val state=read(c);val a=state.account?:error("잔고를 먼저 등록하세요")
  require(currency in listOf("KRW","USD")&&a.fx>0)
  val signed=amount*(if(deposit)1 else -1)
  val krw=signed*(if(currency=="USD")a.fx else 1.0)
  val updated=if(currency=="USD")a.copy(cashUsd=a.cashUsd+signed,capitalKrw=a.capitalKrw+krw,flowKrw=a.flowKrw+krw) else a.copy(cashKrw=a.cashKrw+signed,capitalKrw=a.capitalKrw+krw,flowKrw=a.flowKrw+krw)
  require(updated.cashUsd>=0&&updated.cashKrw>=0){"보유 현금보다 많이 출금할 수 없습니다"}
  write(c,state.copy(account=updated.copy(confirmedDate=today())))
  recordDaily(c)
 }
 fun export(c:Context):String=Data.prefs(c).getString(KEY,null)?:error("저장할 자산이 없습니다")
}
