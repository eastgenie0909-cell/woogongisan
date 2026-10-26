package com.woogong.app

/** Versioned envelope shared by target, status, manifest and backtest. */
data class ModelIdentity(val schema:Int, val modelId:String, val fingerprint:String) {
 fun matches(other:ModelIdentity):Boolean =
  schema==1 && other.schema==1 && modelId.isNotBlank() &&
  fingerprint.matches(Regex("[0-9a-f]{64}")) && this==other
}
