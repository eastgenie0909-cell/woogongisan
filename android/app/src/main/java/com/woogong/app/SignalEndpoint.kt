package com.woogong.app

import java.net.URI

internal object SignalEndpoint {
 fun normalize(value:String):String {
  val u=URI(value.trim().trimEnd('/'))
  require(u.scheme=="https"&&u.host!=null&&u.userInfo==null&&u.rawQuery==null&&u.fragment==null){"모델 결과의 HTTPS 주소를 입력하세요"}
  if(u.host=="github.com"){
   val parts=u.path.trim('/').split('/')
   require(parts.size==2&&parts.all{it.matches(Regex("[A-Za-z0-9_.-]+"))}){"저장소 기본 주소 또는 Pages 주소를 입력하세요"}
   return "https://${parts[0]}.github.io/${parts[1].removeSuffix(".git") }"
  }
  return u.toString()
 }
}
