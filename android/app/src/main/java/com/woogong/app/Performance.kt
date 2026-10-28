package com.woogong.app

import java.time.LocalDate
import org.json.JSONObject

internal data class PerformancePoint(val date:String,val model:Double,val tqqq:Double,val brkb:Double){fun value(key:String)=when(key){"model"->model;"tqqq"->tqqq;else->brkb}}
internal data class PerformanceWindow(val start:String,val end:String,val points:List<PerformancePoint>){
 val last get()=points.last()
 val low get()=minOf(0.0,points.minOf{minOf(it.model,it.tqqq,it.brkb)})
 val high get()=maxOf(0.0,points.maxOf{maxOf(it.model,it.tqqq,it.brkb)})
 val span get()=(high-low).coerceAtLeast(.01)
}
internal object Performance {
 val periods=linkedMapOf("1m" to "1개월","3m" to "3개월","1y" to "1년","all" to "전체")
 fun percent(v:Double)="%+.2f%%".format(v*100)
 fun read(s:JSONObject,period:String):PerformanceWindow?=try{
  val data=s.getJSONObject("performance")
  require(data.optString("version")=="model-tqqq-brkb-v1"&&data.optBoolean("available")&&data.getString("currency")=="USD")
  require(data.getString("market_date")==s.getString("market_date"))
  val window=data.getJSONObject("windows").getJSONObject(period)
  val points=window.getJSONArray("points");require(points.length() in 2..20000)
  val rows=(0 until points.length()).map{val p=points.getJSONObject(it);PerformancePoint(p.getString("date"),p.getDouble("model"),p.getDouble("tqqq"),p.getDouble("brkb"))}
  require(rows.all{PortfolioEngine.validDate(it.date)&&listOf(it.model,it.tqqq,it.brkb).all{v->v.isFinite()&&v>=-1}})
  require(rows.zipWithNext().all{(a,b)->LocalDate.parse(a.date).isBefore(LocalDate.parse(b.date))})
  require(rows.first().date==window.getString("start")&&rows.last().date==window.getString("end")&&rows.last().date==s.getString("market_date"))
  require(listOf(rows.first().model,rows.first().tqqq,rows.first().brkb).all{kotlin.math.abs(it)<1e-10})
  val returns=window.getJSONObject("returns");require(listOf("model","tqqq","brkb").all{k->kotlin.math.abs(returns.getDouble(k)-rows.last().value(k))<1e-10})
  PerformanceWindow(rows.first().date,rows.last().date,rows)
 }catch(_:Exception){null}
}
