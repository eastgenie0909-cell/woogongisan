package com.woogong.app

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class MeritzClientTest {
 private fun response(data:Any,more:Boolean=false,key:String="")=JSONObject().put("rsp_cd","5766").put("data",data).put("tr_cont",if(more)"1" else "0").put("tr_cont_key",key)
 private fun holding(kind:String="해외주식",quantity:Double=2.0)=JSONObject().put("iscd","TQQQ.OQ").put("isnm","TQQQ").put("mksc_cls_name",kind).put("rmnd_qty",quantity).put("hldn_issu_rmnd_qty","0.125").put("vltn_amt",220).put("byng_amt",200).put("vltn_prls_amt",20)
 private fun valuation()=JSONObject().put("aset_vltn_amt",1520).put("tfam",100).put("frcr_tfam",1200).put("mksc_vltn_amt",220).put("loan_amt",0)
 @Test fun directAccountUsesOnlyOfficialReadRoutesAndDoesNotDoubleCountCash(){
  val calls=mutableListOf<String>()
  val client=MeritzClient(MeritzTransport{method,path,headers,params->
   calls.add("$method $path");assertEquals("Bearer account-token",headers["authorization"]);assertEquals("AABBCCDDEEFF",headers["mac_address"])
   when(path){
    "/accounts/v1/me/valuation"->response(valuation())
    "/accounts/v1/me/holdings"->{assertEquals("N",params["nthn_rmnd_incu_yn"]);response(JSONArray().put(holding()))}
    "/accounts/v1/me/overseas/currencies"->response(JSONArray().put(JSONObject().put("crcd","USD").put("estm_frcr_amt","1").put("vltn_exrt","1200")))
    else->error("unexpected route")
   }
  },{})
  val result=client.snapshot("account-token","AABBCCDDEEFF")
  assertEquals(1520.0,result.getDouble("total_krw"),0.0)
  assertEquals(2.0,result.getJSONArray("positions").getJSONObject(0).getDouble("quantity"),0.0)
  assertEquals(3,calls.size);assertTrue(calls.all{it.startsWith("GET /accounts/")})
 }
 @Test fun fractionalHoldingsAndAllPagesArePreserved(){
  var pages=0
  val client=MeritzClient(MeritzTransport{_,path,_,params->when(path){
   "/accounts/v1/me/valuation"->response(valuation())
   "/accounts/v1/me/overseas/currencies"->response(JSONArray())
   else->{pages++;if(pages==1)response(JSONArray().put(holding()),true,"next-key") else {assertEquals("next-key",params["tr_cont_key"]);response(JSONArray().put(holding("해외주식(소수점)",0.0)))}}
  }},{})
  val positions=client.snapshot("token","AABBCCDDEEFF").getJSONArray("positions")
  assertEquals(2,positions.length());assertEquals(.125,positions.getJSONObject(1).getDouble("quantity"),0.0)
 }
 @Test fun repeatedContinuationCannotPublishPartialAccount(){
  val client=MeritzClient(MeritzTransport{_,path,_,_->if(path.endsWith("valuation"))response(valuation()) else response(JSONArray().put(holding()),true,"repeated")},{})
  try{client.snapshot("token","AABBCCDDEEFF");fail("partial snapshot accepted")}catch(_:IllegalArgumentException){}
 }
 @Test fun authenticationIsFormOnlyAndCredentialsAreNotAccountParameters(){
  val client=MeritzClient(MeritzTransport{method,path,headers,params->
   assertEquals("POST",method);assertEquals("/oauth2/token",path);assertTrue(headers.isEmpty());assertEquals("client_credentials",params["grant_type"])
   assertEquals(setOf("grant_type","client_id","client_secret","scope"),params.keys)
   JSONObject().put("access_token","read-token").put("expires_in","3600")
  },{})
  assertEquals("read-token",client.authenticate("sample-key-123456789","sample-secret-123456789").first)
 }
 @Test fun ordersAndOtherHostsCannotBeRequested(){
  for((method,path) in listOf("POST" to "/trading/v1/overseas/orders/buy","GET" to "https://other.example/accounts","GET" to "/oauth2/token")){
   try{MeritzRoutes.check(method,path);fail(path)}catch(_:IllegalArgumentException){}
  }
 }
 @Test fun brokerFailureIsNotAnEmptySuccessfulAccount(){
  val client=MeritzClient(MeritzTransport{_,_,_,_->JSONObject().put("rsp_cd","0760").put("data",JSONObject())},{})
  try{client.snapshot("token","AABBCCDDEEFF");fail("authorization error accepted")}catch(e:IllegalArgumentException){assertTrue(e.message!!.contains("0760"))}
 }
}
