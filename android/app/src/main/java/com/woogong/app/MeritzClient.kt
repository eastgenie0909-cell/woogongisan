package com.woogong.app

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

// Only authentication and read-only account routes exist in this client.
internal object MeritzRoutes {
 const val origin="https://openapi.imeritz.com:9443"
 val reads=setOf("/accounts/v1/me/valuation","/accounts/v1/me/holdings","/accounts/v1/me/overseas/currencies")
 fun check(method:String,path:String){require((method=="POST"&&path=="/oauth2/token")||(method=="GET"&&path in reads)){"지원하지 않는 메리츠 조회 경로"}}
 fun credential(value:String):String=value.trim().also{require(it.length in 16..256&&it.none{ch->ch.isWhitespace()||ch.isISOControl()}){"발급받은 App Key / App Secret을 확인하세요"}}
}

internal fun interface MeritzTransport {
 fun request(method:String,path:String,headers:Map<String,String>,params:Map<String,String>):JSONObject
}

internal class MeritzHttp:MeritzTransport {
 override fun request(method:String,path:String,headers:Map<String,String>,params:Map<String,String>):JSONObject {
  MeritzRoutes.check(method,path)
  val encoded=params.entries.joinToString("&"){URLEncoder.encode(it.key,"UTF-8")+"="+URLEncoder.encode(it.value,"UTF-8")}
  val conn=URI(MeritzRoutes.origin+path+if(method=="GET"&&encoded.isNotEmpty())"?$encoded" else "").toURL().openConnection() as HttpURLConnection
  try {
   conn.requestMethod=method;conn.connectTimeout=15000;conn.readTimeout=25000;conn.instanceFollowRedirects=false;conn.useCaches=false
   headers.forEach{(k,v)->conn.setRequestProperty(k,v)}
   if(method=="POST"){conn.setRequestProperty("Content-Type","application/x-www-form-urlencoded");conn.doOutput=true;conn.outputStream.use{it.write(encoded.toByteArray(Charsets.UTF_8))}}
   val status=conn.responseCode
   val text=(if(status in 200..299)conn.inputStream else conn.errorStream)?.bufferedReader()?.use{it.readText()}?:error("메리츠 응답 없음")
   require(text.length<5_000_000){"메리츠 응답 크기 초과"}
   val body=try{JSONObject(text)}catch(_:Exception){error("메리츠 응답 형식을 확인할 수 없습니다 (HTTP $status)")}
   // Return broker response codes, never raw payloads or credential-bearing exception text.
   if(status !in 200..299&&body.optString("rsp_cd") !in setOf("5766","5820","5822"))error("메리츠 연결 오류 (HTTP $status · ${safeCode(body.optString("rsp_cd"))})")
   return body
  } finally {conn.disconnect()}
 }
}

internal fun safeCode(s:String)=s.takeIf{it.matches(Regex("[A-Za-z0-9_-]{1,20}"))}?:"응답 확인 필요"
internal fun decimal(row:JSONObject,key:String):Double = row.get(key).toString().replace(",","").toDouble().also{require(it.isFinite()){"메리츠 숫자 응답 오류"}}

internal class MeritzClient(private val transport:MeritzTransport=MeritzHttp(),private val pause:()->Unit={Thread.sleep(160)}) {
 private val ok=setOf("0000","0001","5620","5762","5766","5820","5822")
 fun authenticate(key:String,secret:String):Pair<String,Long> {
  val obj=transport.request("POST","/oauth2/token",emptyMap(),mapOf("grant_type" to "client_credentials","client_id" to MeritzRoutes.credential(key),"client_secret" to MeritzRoutes.credential(secret),"scope" to "login"))
  val token=obj.optString("access_token");require(token.isNotBlank()){"메리츠 인증 실패 · ${safeCode(obj.optString("rsp_cd"))}"}
  val seconds=obj.get("expires_in").toString().toLong();require(seconds>60){"메리츠 토큰 유효기간 오류"}
  return token to (Instant.now().epochSecond+seconds-60)
 }
 private fun get(path:String,token:String,mac:String,params:Map<String,String> = emptyMap()):JSONObject {
  MeritzRoutes.check("GET",path);pause()
  val obj=transport.request("GET",path,mapOf("authorization" to "Bearer $token","mac_address" to mac),params)
  require(obj.optString("rsp_cd") in ok){"메리츠 조회 실패 · ${safeCode(obj.optString("rsp_cd"))}. 계좌 조회 권한을 확인하세요."}
  return obj
 }
 private fun rows(path:String,token:String,mac:String,params:Map<String,String> = emptyMap()):JSONArray {
  val result=JSONArray();val seen=mutableSetOf<String>();var query=params
  repeat(100){
   val obj=get(path,token,mac,query)
   val rows=obj.optJSONArray("data")?:if(obj.optString("rsp_cd") in setOf("5820","5822")&&obj.opt("data") in listOf(null,JSONObject.NULL))JSONArray() else error("메리츠 목록 응답 누락")
   for(i in 0 until rows.length())result.put(rows.getJSONObject(i))
   if(obj.optString("tr_cont")!="1"&&obj.optString("rsp_cd")!="5762")return result
   val next=obj.optString("tr_cont_key");require(next.isNotEmpty()&&seen.add(next)){"잔고 연속조회가 완료되지 않았습니다"}
   query=params+mapOf("tr_cont" to "1","tr_cont_key" to next)
  }
  error("잔고 연속조회 한도 초과")
 }
 fun snapshot(token:String,mac:String):JSONObject {
  require(mac.matches(Regex("[0-9A-F]{12}"))){"메리츠 요청 식별자 오류"}
  val valuation=get("/accounts/v1/me/valuation",token,mac).getJSONObject("data")
  val holdings=rows("/accounts/v1/me/holdings",token,mac,mapOf("nthn_rmnd_incu_yn" to "N"))
  val currencies=rows("/accounts/v1/me/overseas/currencies",token,mac)
  val positions=JSONArray()
  for(i in 0 until holdings.length()){
   val h=holdings.getJSONObject(i);val kind=h.getString("mksc_cls_name")
   val quantity=decimal(h,if(kind=="해외주식(소수점)")"hldn_issu_rmnd_qty" else "rmnd_qty")
   require(quantity>=0){"잔고 수량 확인 필요"};if(quantity==0.0)continue
   positions.put(JSONObject().put("symbol",h.getString("iscd")).put("name",h.optString("isnm")).put("kind",kind).put("quantity",quantity)
    .put("value_krw",decimal(h,"vltn_amt")).put("cost_krw",decimal(h,"byng_amt")).put("profit_krw",decimal(h,"vltn_prls_amt")))
  }
  val cash=JSONArray()
  for(i in 0 until currencies.length()){
   val row=currencies.getJSONObject(i)
   cash.put(JSONObject().put("currency",row.getString("crcd")).put("amount",decimal(row,"estm_frcr_amt")).put("fx",decimal(row,"vltn_exrt")))
  }
  // aset_vltn_amt already includes foreign cash. Do not add converted cash a second time.
  return JSONObject().put("source","meritz_direct").put("as_of",Instant.now().toString()).put("total_krw",decimal(valuation,"aset_vltn_amt"))
   .put("cash_krw",decimal(valuation,"tfam")).put("foreign_cash_krw",decimal(valuation,"frcr_tfam"))
   .put("securities_krw",decimal(valuation,"mksc_vltn_amt")).put("loans_krw",decimal(valuation,"loan_amt"))
   .put("positions",positions).put("currencies",cash)
 }
}
