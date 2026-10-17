package org.spaceworks.app.notifications

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Private preference values contain no credentials, device token, message text or pending links. */
class AndroidPushPersistence(private val prefs:SharedPreferences):PushPersistence {
    override suspend fun consent(account:String)=withContext(Dispatchers.IO){prefs.getBoolean("consent.$account",false)}
    override suspend fun consent(account:String,value:Boolean){withContext(Dispatchers.IO){check(prefs.edit().putBoolean("consent.$account",value).commit())}}
    override suspend fun records(account:String):List<PushRecord> = withContext(Dispatchers.IO) {
        val value=prefs.getString("ids.$account",null)?:return@withContext emptyList()
        runCatching {
            val rows=JSONArray(value)
            require(rows.length()<=1000)
            (0 until rows.length()).map {i->val row=rows.getJSONObject(i);PushRecord(row.getInt("maker"),row.getInt("id"),row.getString("hash")).also {require(it.makerspaceId>0&&it.deviceId>0&&it.tokenHash.matches(Regex("[0-9a-f]{64}")))}}
        }.getOrElse {throw IllegalStateException("Notification setup needs to be reset.")}
    }
    override suspend fun records(account:String,value:List<PushRecord>){withContext(Dispatchers.IO){val rows=JSONArray();value.forEach {record->rows.put(JSONObject().put("maker",record.makerspaceId).put("id",record.deviceId).put("hash",record.tokenHash))};check(prefs.edit().putString("ids.$account",rows.toString()).commit())}}
    override suspend fun clear(account:String){withContext(Dispatchers.IO){check(prefs.edit().remove("ids.$account").remove("consent.$account").commit())}}
    companion object {
        fun accountKey(server:String,userId:Int):String=MessageDigest.getInstance("SHA-256").digest("$server\n$userId".toByteArray()).joinToString(""){"%02x".format(it)}
    }
}
