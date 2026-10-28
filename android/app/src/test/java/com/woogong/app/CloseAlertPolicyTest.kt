package com.woogong.app
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CloseAlertPolicyTest {
 private val now=Instant.parse("2026-10-28T00:00:00Z")
 private fun snapshot()=JSONObject().put("schema_version",1).put("model_id","woogong-1.3-d").put("model_fingerprint","a".repeat(64)).put("mode","live").put("market_date","2026-10-27").put("valid_until","2026-10-28T20:05:00Z")
 private fun health()=snapshot().put("state","ready")
 @Test fun newFreshCloseAlertsOnceAcrossPollingAndPush(){val key=CloseAlertPolicy.identity(snapshot(),health(),now);assertNotNull(key);assertTrue(CloseAlertPolicy.shouldNotify(key,null,true,true));assertFalse(CloseAlertPolicy.shouldNotify(key,key,true,true))}
 @Test fun offlineDemoErrorAndMixedModelNeverAlert(){
  assertNull(CloseAlertPolicy.identity(snapshot().put("mode","historical_demo"),health(),now))
  assertNull(CloseAlertPolicy.identity(snapshot(),health().put("state","error"),now))
  assertNull(CloseAlertPolicy.identity(snapshot(),health().put("model_fingerprint","b".repeat(64)),now))
  assertNull(CloseAlertPolicy.identity(snapshot(),health().put("market_date","2026-10-26"),now))
 }
 @Test fun expiryBoundaryIsExclusive(){assertNull(CloseAlertPolicy.identity(snapshot().put("valid_until",now.toString()),health(),now));assertNull(CloseAlertPolicy.identity(snapshot().put("valid_until","invalid"),health(),now))}
 @Test fun deniedPermissionAndUserOptOutDoNotTrigger(){val key=CloseAlertPolicy.identity(snapshot(),health(),now);assertFalse(CloseAlertPolicy.shouldNotify(key,null,true,false));assertFalse(CloseAlertPolicy.shouldNotify(key,null,false,true));assertFalse(CloseAlertPolicy.shouldNotify(null,null,true,true));assertTrue(CloseAlertPolicy.shouldNotify(key,null,true,true))}
 @Test fun newDateCanAlertAgain(){val old=CloseAlertPolicy.identity(snapshot(),health(),now);val new=snapshot().put("market_date","2026-10-28");val newHealth=health().put("market_date","2026-10-28");assertTrue(CloseAlertPolicy.shouldNotify(CloseAlertPolicy.identity(new,newHealth,now),old,true,true))}
}
