package com.woogong.app
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object WidgetSummary {
 fun expired(at:Instant,now:Instant)=at.isAfter(now)||at.isBefore(now.minusSeconds(3600))
 fun account(result:JSONObject?,failed:Boolean=false):String {
  val a=result?.optJSONObject("account")?:return "계좌 조회 대기"
  return try {
   val at=Instant.parse(a.getString("as_of"));val old=failed||expired(at,Instant.now())
   val p=a.getJSONArray("positions");var value=a.getDouble("cash_usd")
   for(i in 0 until p.length()){val row=p.getJSONObject(i);value+=row.getDouble("quantity")*row.getDouble("price")}
   value=value*a.getDouble("fx")+a.getDouble("cash_krw");require(value.isFinite())
   val time=DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.of("Asia/Seoul")).format(at)
   "${if(result.optBoolean("demo"))"예시 " else ""}미국주식·현금 ${"%,.0f".format(value)}원\n조회 $time${if(old)" · 갱신 필요" else ""}"
  }catch(_:Exception){"계좌 조회 대기"}
 }
 fun timing(t:JSONObject?,fresh:Boolean):String {
  val valid=try{fresh&&t?.optBoolean("supported")==true&&Instant.now().isBefore(Instant.parse(t.optString("valid_until")))}catch(_:Exception){false}
  return "차입 연구 · "+if(valid)t!!.optString("label") else "판정 보류"
 }
}
