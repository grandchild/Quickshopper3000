package org.effekthasch.quickshopper3k.data.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncIntentTest {

    private fun roundTrip(envelope: SyncIntentEnvelope): SyncIntentEnvelope {
        val json = SyncIntentSerializer.toJson(envelope)
        val result = SyncIntentSerializer.fromJson(json)
        assertNotNull(result)
        return result!!
    }

    private fun envelope(intent: SyncIntent, serverUlid: String? = null) = SyncIntentEnvelope(
        deviceUlid = "01ARZ3NDEKTSV4RRFFQ69G5FAV",
        serverUlid = serverUlid,
        deviceId = "device-001",
        listId = "list-001",
        intent = intent
    )

    @Test
    fun addItemRoundTrips() {
        val original = envelope(SyncIntent.AddItem("item-uuid-1", "Milk"))
        val restored = roundTrip(original)
        assertEquals(original, restored)
    }

    @Test
    fun deleteItemRoundTrips() {
        val original = envelope(SyncIntent.DeleteItem("item-uuid-1"))
        val restored = roundTrip(original)
        assertEquals(original, restored)
    }

    @Test
    fun renameItemRoundTrips() {
        val original = envelope(SyncIntent.RenameItem("item-uuid-1", "Oat Milk"))
        val restored = roundTrip(original)
        assertEquals(original, restored)
    }

    @Test
    fun setItemStateRoundTrips() {
        val original = envelope(SyncIntent.SetItemState("item-uuid-1", "DEACTIVATED", "ACTIVATED"))
        val restored = roundTrip(original)
        assertEquals(original, restored)
    }

    @Test
    fun setItemAmountRoundTrips() {
        val original = envelope(SyncIntent.SetItemAmount("item-uuid-1", 5))
        val restored = roundTrip(original)
        assertEquals(original, restored)
    }

    @Test
    fun addStoreRoundTrips() {
        val original = envelope(SyncIntent.AddStore("store-uuid-1", "Lidl", false))
        val restored = roundTrip(original)
        assertEquals(original, restored)
    }

    @Test
    fun addStoreSpecialtyRoundTrips() {
        val original = envelope(SyncIntent.AddStore("store-uuid-2", "Butcher", true))
        val restored = roundTrip(original)
        assertEquals(original, restored)
    }

    @Test
    fun deleteStoreRoundTrips() {
        val original = envelope(SyncIntent.DeleteStore("store-uuid-1"))
        val restored = roundTrip(original)
        assertEquals(original, restored)
    }

    @Test
    fun updateStoreRoundTrips() {
        val original = envelope(SyncIntent.UpdateStore("store-uuid-1", "Aldi Süd", true))
        val restored = roundTrip(original)
        assertEquals(original, restored)
    }

    @Test
    fun setAvailabilityRoundTrips() {
        val original = envelope(SyncIntent.SetAvailability("store-uuid-1", "item-uuid-1", false))
        val restored = roundTrip(original)
        assertEquals(original, restored)
    }

    @Test
    fun setItemOrderRoundTrips() {
        val original = envelope(SyncIntent.SetItemOrder("store-uuid-1", "item-uuid-1", 42))
        val restored = roundTrip(original)
        assertEquals(original, restored)
    }

    @Test
    fun bulkAddItemsRoundTrips() {
        val items = listOf("uuid-1" to "Bread", "uuid-2" to "Butter", "uuid-3" to "Eggs")
        val original = envelope(SyncIntent.BulkAddItems(items))
        val restored = roundTrip(original)
        assertEquals(original, restored)
    }

    @Test
    fun bulkAddItemsEmptyListRoundTrips() {
        val original = envelope(SyncIntent.BulkAddItems(emptyList()))
        val restored = roundTrip(original)
        assertEquals(original, restored)
    }

    @Test
    fun bulkSetStateRoundTrips() {
        val original = envelope(SyncIntent.BulkSetState(listOf("uuid-1", "uuid-2"), "POSTPONED"))
        val restored = roundTrip(original)
        assertEquals(original, restored)
    }

    @Test
    fun serverUlidNullSerializesAsJsonNull() {
        val env = envelope(SyncIntent.DeleteItem("x"), serverUlid = null)
        val json = SyncIntentSerializer.toJson(env)
        assertTrue(json.isNull("server_ulid"))
    }

    @Test
    fun serverUlidPresentRoundTrips() {
        val original = envelope(SyncIntent.DeleteItem("x"), serverUlid = "01BX5ZZKBKACTAV9WEVGEMMVRY")
        val restored = roundTrip(original)
        assertEquals("01BX5ZZKBKACTAV9WEVGEMMVRY", restored.serverUlid)
    }

    @Test
    fun unknownTypeReturnsNull() {
        val json = JSONObject().apply {
            put("device_ulid", "abc")
            put("server_ulid", JSONObject.NULL)
            put("device_id", "d")
            put("list_id", "l")
            put("type", "explode_phone")
            put("payload", JSONObject())
        }
        assertNull(SyncIntentSerializer.fromJson(json))
    }

    @Test
    fun missingPayloadReturnsNull() {
        val json = JSONObject().apply {
            put("device_ulid", "abc")
            put("server_ulid", JSONObject.NULL)
            put("device_id", "d")
            put("list_id", "l")
            put("type", "add_item")
        }
        assertNull(SyncIntentSerializer.fromJson(json))
    }

    @Test
    fun jsonArrayRoundTrips() {
        val envelopes = listOf(
            envelope(SyncIntent.AddItem("u1", "Milk")),
            envelope(SyncIntent.DeleteItem("u2"), serverUlid = "01BX5ZZKBKACTAV9WEVGEMMVRY"),
            envelope(SyncIntent.SetItemState("u3", "POSTPONED", "ACTIVATED"))
        )
        val arr = SyncIntentSerializer.toJsonArray(envelopes)
        assertEquals(3, arr.length())
        val restored = SyncIntentSerializer.fromJsonArray(arr)
        assertEquals(envelopes, restored)
    }

    @Test
    fun jsonArraySkipsMalformed() {
        val arr = JSONArray().apply {
            put(SyncIntentSerializer.toJson(envelope(SyncIntent.AddItem("u1", "Milk"))))
            put(JSONObject().put("type", "garbage"))
            put(SyncIntentSerializer.toJson(envelope(SyncIntent.DeleteItem("u2"))))
        }
        val restored = SyncIntentSerializer.fromJsonArray(arr)
        assertEquals(2, restored.size)
    }

    @Test
    fun typeStringMatchesCatalog() {
        val typeMap = mapOf(
            SyncIntent.AddItem("", "") to "add_item",
            SyncIntent.DeleteItem("") to "delete_item",
            SyncIntent.RenameItem("", "") to "rename_item",
            SyncIntent.SetItemState("", "", "") to "set_item_state",
            SyncIntent.SetItemAmount("", 0) to "set_item_amount",
            SyncIntent.AddStore("", "", false) to "add_store",
            SyncIntent.DeleteStore("") to "delete_store",
            SyncIntent.UpdateStore("", "", false) to "update_store",
            SyncIntent.SetAvailability("", "", false) to "set_availability",
            SyncIntent.SetItemOrder("", "", 0) to "set_item_order",
            SyncIntent.BulkAddItems(emptyList()) to "bulk_add_items",
            SyncIntent.BulkSetState(emptyList(), "") to "bulk_set_state"
        )
        typeMap.forEach { (intent, expected) ->
            assertEquals("Type mismatch for ${intent::class.simpleName}", expected, intent.type)
        }
    }

    @Test
    fun envelopeFieldsPreserved() {
        val env = SyncIntentEnvelope(
            deviceUlid = "DEVICE_ULID_123",
            serverUlid = "SERVER_ULID_456",
            deviceId = "my-device",
            listId = "my-list",
            intent = SyncIntent.AddItem("i1", "Cheese")
        )
        val json = SyncIntentSerializer.toJson(env)
        assertEquals("DEVICE_ULID_123", json.getString("device_ulid"))
        assertEquals("SERVER_ULID_456", json.getString("server_ulid"))
        assertEquals("my-device", json.getString("device_id"))
        assertEquals("my-list", json.getString("list_id"))
        assertEquals("add_item", json.getString("type"))
        assertEquals("i1", json.getJSONObject("payload").getString("item_uuid"))
        assertEquals("Cheese", json.getJSONObject("payload").getString("name"))
    }
}
