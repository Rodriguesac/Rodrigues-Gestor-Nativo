package com.rodrigues.gestor.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import java.util.UUID

/**
 * Caixa offline durável do Gestor 20. Dados sensíveis permanecem no
 * armazenamento privado do aplicativo. Desative backup em produção.
 *
 * O banco nunca considera que uma ação offline foi confirmada no servidor.
 */
class OfflineOrderStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "gestor20_offline_v1.db", null, 1) {

    data class PendingChange(
        val id: String,
        val orderId: String,
        val action: String,
        val previousStatus: String,
        val createdAt: Long,
        val state: String,
        val reason: String
    )

    private val gson = Gson()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS orders_cache (
                id TEXT PRIMARY KEY NOT NULL,
                json TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS pending_changes (
                id TEXT PRIMARY KEY NOT NULL,
                order_id TEXT NOT NULL,
                action TEXT NOT NULL,
                previous_status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                state TEXT NOT NULL DEFAULT 'queued',
                reason TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pending_state ON pending_changes(state,created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Futuras migrações aditivas; nunca apagar a fila silenciosamente.
    }

    @Synchronized
    fun storeOrders(orders: List<Order>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val timestamp = System.currentTimeMillis()
            orders.forEach { order ->
                if (order.id.isNotBlank()) {
                    val values = ContentValues().apply {
                        put("id", order.id)
                        put("json", gson.toJson(order))
                        put("updated_at", timestamp)
                    }
                    db.insertWithOnConflict("orders_cache", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun cachedOrders(): List<Order> {
        val output = mutableListOf<Order>()
        readableDatabase.rawQuery(
            "SELECT json FROM orders_cache ORDER BY updated_at DESC LIMIT 250", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                try {
                    output += gson.fromJson(cursor.getString(0), Order::class.java)
                } catch (_: Throwable) {
                    // Um registro corrompido não impede o acesso aos demais.
                }
            }
        }
        return output.sortedByDescending { it.createdMillis }
    }

    /** Recusa ações concorrentes offline para o mesmo pedido. */
    @Synchronized
    fun enqueue(orderId: String, action: String, currentStatus: String): Boolean {
        require(orderId.isNotBlank() && action.isNotBlank())
        val db = writableDatabase
        db.rawQuery(
            "SELECT id FROM pending_changes WHERE order_id = ? AND state IN ('queued','conflict') LIMIT 1",
            arrayOf(orderId)
        ).use { if (it.moveToFirst()) return false }
        val values = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("order_id", orderId)
            put("action", action)
            put("previous_status", currentStatus)
            put("created_at", System.currentTimeMillis())
            put("state", "queued")
            put("reason", "")
        }
        return db.insert("pending_changes", null, values) != -1L
    }

    @Synchronized
    fun waiting(): List<PendingChange> = readChanges("queued")

    @Synchronized
    fun conflicts(): List<PendingChange> = readChanges("conflict")

    private fun readChanges(state: String): List<PendingChange> {
        val list = mutableListOf<PendingChange>()
        readableDatabase.rawQuery(
            "SELECT id,order_id,action,previous_status,created_at,state,reason FROM pending_changes WHERE state=? ORDER BY created_at ASC",
            arrayOf(state)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list += PendingChange(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getString(3), cursor.getLong(4), cursor.getString(5),
                    cursor.getString(6)
                )
            }
        }
        return list
    }

    @Synchronized
    fun completed(id: String) {
        writableDatabase.delete("pending_changes", "id=?", arrayOf(id))
    }

    @Synchronized
    fun conflict(id: String, reason: String) {
        val values = ContentValues().apply {
            put("state", "conflict")
            put("reason", reason.take(450))
        }
        writableDatabase.update("pending_changes", values, "id=?", arrayOf(id))
    }

    @Synchronized
    fun clearAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("orders_cache", null, null)
            db.delete("pending_changes", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
