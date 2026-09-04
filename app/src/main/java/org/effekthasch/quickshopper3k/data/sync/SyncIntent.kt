package org.effekthasch.quickshopper3k.data.sync

import org.json.JSONArray
import org.json.JSONObject

sealed interface SyncIntent {
    val type: String
    fun payloadToJson(): JSONObject

    data class AddItem(
        val itemUuid: String,
        val name: String,
        val description: String = "",
        val synonyms: String = ""
    ) : SyncIntent {
        override val type get() = "add_item"
        override fun payloadToJson() = jsonOf(
            "item_uuid" to itemUuid,
            "name" to name,
            "description" to description,
            "synonyms" to synonyms
        )
    }

    data class DeleteItem(val itemUuid: String) : SyncIntent {
        override val type get() = "delete_item"
        override fun payloadToJson() = jsonOf("item_uuid" to itemUuid)
    }

    data class RenameItem(val itemUuid: String, val name: String) : SyncIntent {
        override val type get() = "rename_item"
        override fun payloadToJson() = jsonOf("item_uuid" to itemUuid, "name" to name)
    }

    data class SetItemDescription(val itemUuid: String, val description: String) : SyncIntent {
        override val type get() = "set_item_description"
        override fun payloadToJson() = jsonOf("item_uuid" to itemUuid, "description" to description)
    }

    data class SetItemSynonyms(val itemUuid: String, val synonyms: String) : SyncIntent {
        override val type get() = "set_item_synonyms"
        override fun payloadToJson() = jsonOf("item_uuid" to itemUuid, "synonyms" to synonyms)
    }

    data class SetItemState(val itemUuid: String, val state: String, val previousState: String) : SyncIntent {
        override val type get() = "set_item_state"
        override fun payloadToJson() =
            jsonOf("item_uuid" to itemUuid, "state" to state, "previous_state" to previousState)
    }

    data class SetItemAmount(val itemUuid: String, val amount: Int) : SyncIntent {
        override val type get() = "set_item_amount"
        override fun payloadToJson() = jsonOf("item_uuid" to itemUuid, "amount" to amount)
    }

    data class AddStore(val storeUuid: String, val name: String, val specialty: Boolean) : SyncIntent {
        override val type get() = "add_store"
        override fun payloadToJson() = jsonOf("store_uuid" to storeUuid, "name" to name, "specialty" to specialty)
    }

    data class DeleteStore(val storeUuid: String) : SyncIntent {
        override val type get() = "delete_store"
        override fun payloadToJson() = jsonOf("store_uuid" to storeUuid)
    }

    data class UpdateStore(val storeUuid: String, val name: String, val specialty: Boolean) : SyncIntent {
        override val type get() = "update_store"
        override fun payloadToJson() = jsonOf("store_uuid" to storeUuid, "name" to name, "specialty" to specialty)
    }

    data class SetAvailability(val storeUuid: String, val itemUuid: String, val available: Boolean) : SyncIntent {
        override val type get() = "set_availability"
        override fun payloadToJson() =
            jsonOf("store_uuid" to storeUuid, "item_uuid" to itemUuid, "available" to available)
    }

    data class SetItemOrder(val storeUuid: String, val itemUuid: String, val sortOrder: Int) : SyncIntent {
        override val type get() = "set_item_order"
        override fun payloadToJson() =
            jsonOf("store_uuid" to storeUuid, "item_uuid" to itemUuid, "sort_order" to sortOrder)
    }

    data class BulkAddItems(val items: List<AddItem>) : SyncIntent {
        override val type get() = "bulk_add_items"
        override fun payloadToJson() = JSONObject().put("items", JSONArray().apply {
            items.forEach { put(it.payloadToJson()) }
        })
    }

    data class BulkSetState(val itemUuids: List<String>, val state: String) : SyncIntent {
        override val type get() = "bulk_set_state"
        override fun payloadToJson() = JSONObject()
            .put("item_uuids", JSONArray(itemUuids))
            .put("state", state)
    }

    companion object {
        private val PARSERS: Map<String, (JSONObject) -> SyncIntent> = mapOf(
            "add_item" to { p ->
                AddItem(
                    p.str("item_uuid"),
                    p.str("name"),
                    p.optString("description"),
                    p.optString("synonyms")
                )
            },
            "delete_item" to { p -> DeleteItem(p.str("item_uuid")) },
            "rename_item" to { p -> RenameItem(p.str("item_uuid"), p.str("name")) },
            "set_item_description" to { p ->
                SetItemDescription(p.str("item_uuid"), p.str("description"))
            },
            "set_item_synonyms" to { p ->
                SetItemSynonyms(p.str("item_uuid"), p.str("synonyms"))
            },
            "set_item_state" to { p -> SetItemState(p.str("item_uuid"), p.str("state"), p.str("previous_state")) },
            "set_item_amount" to { p -> SetItemAmount(p.str("item_uuid"), p.getInt("amount")) },
            "add_store" to { p -> AddStore(p.str("store_uuid"), p.str("name"), p.getBoolean("specialty")) },
            "delete_store" to { p -> DeleteStore(p.str("store_uuid")) },
            "update_store" to { p -> UpdateStore(p.str("store_uuid"), p.str("name"), p.getBoolean("specialty")) },
            "set_availability" to { p ->
                SetAvailability(
                    p.str("store_uuid"),
                    p.str("item_uuid"),
                    p.getBoolean("available")
                )
            },
            "set_item_order" to { p -> SetItemOrder(p.str("store_uuid"), p.str("item_uuid"), p.getInt("sort_order")) },
            "bulk_add_items" to { p ->
                val arr = p.getJSONArray("items")
                BulkAddItems((0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    AddItem(
                        o.str("item_uuid"),
                        o.str("name"),
                        o.optString("description"),
                        o.optString("synonyms")
                    )
                })
            },
            "bulk_set_state" to { p ->
                val arr = p.getJSONArray("item_uuids")
                BulkSetState((0 until arr.length()).map { arr.getString(it) }, p.str("state"))
            }
        )

        fun fromPayload(type: String, payload: JSONObject): SyncIntent? = PARSERS[type]?.invoke(payload)
    }
}

data class SyncIntentEnvelope(
    val deviceUlid: String,
    val serverUlid: String?,
    val deviceId: String,
    val listId: String,
    val intent: SyncIntent
)

object SyncIntentSerializer {

    fun toJson(envelope: SyncIntentEnvelope): JSONObject = JSONObject().apply {
        put("device_ulid", envelope.deviceUlid)
        put("server_ulid", envelope.serverUlid ?: JSONObject.NULL)
        put("device_id", envelope.deviceId)
        put("list_id", envelope.listId)
        put("type", envelope.intent.type)
        put("payload", envelope.intent.payloadToJson())
    }

    fun fromJson(json: JSONObject): SyncIntentEnvelope? {
        val type = json.optString("type", "")
        val payload = json.optJSONObject("payload") ?: return null
        val intent = SyncIntent.fromPayload(type, payload) ?: return null
        return SyncIntentEnvelope(
            deviceUlid = json.str("device_ulid"),
            serverUlid = json.optString("server_ulid").takeIf { !json.isNull("server_ulid") },
            deviceId = json.str("device_id"),
            listId = json.str("list_id"),
            intent = intent
        )
    }

    fun toJsonArray(envelopes: List<SyncIntentEnvelope>): JSONArray =
        JSONArray().apply { envelopes.forEach { put(toJson(it)) } }

    fun fromJsonArray(array: JSONArray): List<SyncIntentEnvelope> =
        (0 until array.length()).mapNotNull { fromJson(array.getJSONObject(it)) }
}

private fun jsonOf(vararg pairs: Pair<String, Any>): JSONObject =
    JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }

private fun JSONObject.str(key: String): String = getString(key)
