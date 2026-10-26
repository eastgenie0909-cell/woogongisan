package com.woogong.app
import org.junit.Assert.*
import org.junit.Test

class TradingConnectionTest {
 @Test fun httpsOriginAccepted(){assertEquals("https://private.example:8443",TradingConnection.endpoint("https://private.example:8443/"))}
 @Test fun refusesCredentialLeaks(){
  for(url in listOf("http://example.com","https://user:secret@example.com","https://example.com?token=secret","https://example.com/path","https://example.com#secret","file:///tmp/a")){
   try{TradingConnection.endpoint(url);fail(url)}catch(_:IllegalArgumentException){}
  }
 }
}
