package org.effekthasch.quickshopper3k.data.sync

import org.json.JSONObject
import org.effekthasch.quickshopper3k.data.local.SyncDao
import org.effekthasch.quickshopper3k.data.local.SyncIntentQueueEntity
import org.effekthasch.quickshopper3k.util.Ulid

class IntentEmitter(private val syncDao: SyncDao) {

    suspend fun emit(intent: SyncIntent) {
        val ulid = Ulid.generate()
        val json = JSONObject().apply {
            put("device_ulid", ulid)
            put("type", intent.type)
            put("payload", intent.payloadToJson())
        }
        syncDao.enqueueIntent(SyncIntentQueueEntity(ulid = ulid, intentJson = json.toString()))
    }
}
