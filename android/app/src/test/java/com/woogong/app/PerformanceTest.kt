package com.woogong.app
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
class PerformanceTest {
 private fun data()=JSONObject("""{"market_date":"2026-10-27","performance":{"available":true,"version":"model-tqqq-brkb-v1","currency":"USD","market_date":"2026-10-27","windows":{"1m":{"start":"2026-09-25","end":"2026-10-27","returns":{"model":-0.08,"tqqq":-0.1,"brkb":0.07},"points":[{"date":"2026-09-25","model":0,"tqqq":0,"brkb":0},{"date":"2026-10-27","model":-0.08,"tqqq":-0.1,"brkb":0.07}]}}}}""")
 @Test fun threeSeriesShareZeroBaselineAndExactReturn(){val w=Performance.read(data(),"1m")!!;assertEquals(0.0,w.points.first().model,0.0);assertEquals(-.08,w.last.model,0.0);assertEquals(-.1,w.low,0.0);assertEquals(.07,w.high,0.0)}
 @Test fun differentCurrencyOrDateCannotBeCompared(){val s=data();s.getJSONObject("performance").put("currency","KRW");assertNull(Performance.read(s,"1m"));assertNull(Performance.read(data().put("market_date","2026-10-26"),"1m"))}
 @Test fun unequalStartingPointOrFinalReturnIsRejected(){val s=data();val w=s.getJSONObject("performance").getJSONObject("windows").getJSONObject("1m");w.getJSONArray("points").getJSONObject(0).put("tqqq",.02);assertNull(Performance.read(s,"1m"));val other=data();other.getJSONObject("performance").getJSONObject("windows").getJSONObject("1m").getJSONObject("returns").put("model",.4);assertNull(Performance.read(other,"1m"))}
 @Test fun missingDataDoesNotProduceAccountOrFictionalChart(){assertNull(Performance.read(JSONObject(),"1m"));assertNull(Performance.read(data(),"all"))}
}
