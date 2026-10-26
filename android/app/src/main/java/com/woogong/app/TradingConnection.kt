package com.woogong.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

object TradingConnection {
 private const val ALIAS="woogong-trading-token"
 private val configLock=Any()
 private class Config(val url:String,val encrypted:String?,val generation:Long)
 private fun config(c:Context)=Config(url(c),Data.prefs(c).getString("trading_token",null),Data.prefs(c).getLong("trading_generation",0))
 private fun matches(c:Context,old:Config):Boolean {val current=config(c);return old.url==current.url&&old.encrypted==current.encrypted&&old.generation==current.generation}
 fun endpoint(value:String):String {
  val u=URI(value.trim().trimEnd('/'))
  require(u.scheme=="https"&&u.host!=null&&u.userInfo==null&&u.rawQuery==null&&u.fragment==null&&(u.path.isNullOrEmpty())){"개인 서버의 HTTPS 주소를 입력하세요"}
  return u.toString()
 }
 private fun key():SecretKey {
  val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
  (store.getKey(ALIAS,null) as? SecretKey)?.let{return it}
  return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
   init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
  }.generateKey()
 }
 fun url(c:Context)=Data.prefs(c).getString("trading_url","")!!
 fun save(c:Context,url:String,token:String){
  val base=endpoint(url);require(token.length>=32){"서버 연결 토큰은 32자 이상입니다"}
  synchronized(configLock){
  val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
  val bytes=cipher.doFinal(token.toByteArray(Charsets.UTF_8))
  check(Data.prefs(c).edit().putLong("trading_generation",Data.prefs(c).getLong("trading_generation",0)+1).remove("trading_widget").putBoolean("trading_sync_failed",true).putString("trading_url",base).putString("trading_token",Base64.encodeToString(cipher.iv+bytes,Base64.NO_WRAP)).commit())
  }
  WoogongWidget.updateAll(c)
 }
 fun clear(c:Context){synchronized(configLock){Data.prefs(c).edit().putLong("trading_generation",Data.prefs(c).getLong("trading_generation",0)+1).remove("trading_url").remove("trading_token").remove("trading_widget").remove("trading_sync_failed").apply()};WoogongWidget.updateAll(c)}
 private fun token(raw:String):String {
  val bytes=Base64.decode(raw,Base64.NO_WRAP);require(bytes.size>12)
  val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
  return cipher.doFinal(bytes.copyOfRange(12,bytes.size)).toString(Charsets.UTF_8)
 }
 fun call(c:Context,action:String,body:JSONObject=JSONObject()):JSONObject {
  require(action in listOf("status","sync","plan","mode","pause","approve","simulate","resolve"))
  val captured=synchronized(configLock){config(c)}
  val capturedToken=token(captured.encrypted?:error("서버 연결 토큰을 저장하세요"))
  val conn=URI(endpoint(captured.url)+"/api/"+action).toURL().openConnection() as HttpURLConnection
  try{
   conn.connectTimeout=15000;conn.readTimeout=45000;conn.instanceFollowRedirects=false;conn.useCaches=false
   conn.setRequestProperty("Authorization","Bearer "+capturedToken);conn.setRequestProperty("Content-Type","application/json")
   if(action!="status"){conn.requestMethod="POST";conn.doOutput=true;conn.outputStream.use{it.write(body.toString().toByteArray(Charsets.UTF_8))}}
   val code=conn.responseCode;val stream=if(code==200)conn.inputStream else conn.errorStream
   val text=stream?.bufferedReader()?.use{it.readText()}?:error("서버 응답 없음")
   require(text.length<5_000_000){"서버 응답 크기 초과"}
   val result=JSONObject(text);require(code==200){result.optString("error","서버 연결 오류 $code")}
   val summary=JSONObject().put("demo",result.optBoolean("demo")).put("account",result.optJSONObject("account")).put("loan_timing",result.optJSONObject("loan_timing"))
   synchronized(configLock){
   require(matches(c,captured)){"연결 설정이 변경되었습니다. 다시 조회하세요."}
   Data.prefs(c).edit().putString("trading_widget",summary.toString()).putBoolean("trading_sync_failed",result.optString("error").isNotEmpty()).apply()
   }
   WoogongWidget.updateAll(c)
   return result
  }catch(e:Exception){synchronized(configLock){if(matches(c,captured))Data.prefs(c).edit().putBoolean("trading_sync_failed",true).apply()};WoogongWidget.updateAll(c);throw e}finally{conn.disconnect()}
 }
}
