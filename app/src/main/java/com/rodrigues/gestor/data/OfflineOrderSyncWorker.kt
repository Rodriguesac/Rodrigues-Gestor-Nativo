package com.rodrigues.gestor.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Sincronização persistente. Nunca marca sucesso antes da confirmação remota.
 * Se outra origem alterou o status, preserva a ação como CONFLITO para revisão,
 * sem sobrescrever silenciosamente a alteração da equipe.
 *
 * O backend precisa receber idempotency_key e expected_status atômicos para
 * garantir 100% contra corridas entre a leitura e o PATCH; não afirmar isso
 * até a evolução do endpoint.
 */
class OfflineOrderSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        GestorCredentials.load(applicationContext)
        if (GestorCredentials.pin.length != 6) return@withContext Result.failure()

        val store = OfflineOrderStore(applicationContext)
        try {
            for (change in store.waiting()) {
                if (isStopped) return@withContext Result.retry()

                // Consulta sempre o estado remoto ANTES da transição offline.
                val server = SupabaseOrdersApi.fetchOrdersSnapshot()
                val current = server.firstOrNull { it.id == change.orderId }
                if (current == null) {
                    store.conflict(change.id, "Pedido não encontrado na janela de consulta. Conferir no Gestor.")
                    continue
                }
                val status = current.status.uppercase()
                val target = change.action.uppercase()
                val previous = change.previousStatus.uppercase()
                when {
                    status == target -> store.completed(change.id)
                    status != previous ->
                        store.conflict(change.id, "O servidor já mudou de $previous para $status. Confira antes de aplicar $target.")
                    else -> {
                        try {
                            SupabaseOrdersApi.updateStatusSync(change.orderId, change.action)
                            store.completed(change.id)
                        } catch (error: Throwable) {
                            // Não sabemos se o servidor aplicou a ação antes da
                            // queda da rede. Na próxima tentativa verificamos o
                            // status remoto e evitamos duplicar a operação.
                            return@withContext Result.retry()
                        }
                    }
                }
            }
            Result.success()
        } catch (_: Throwable) {
            Result.retry()
        } finally {
            store.close()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<OfflineOrderSyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "gestor20_offline_sync_v1", ExistingWorkPolicy.KEEP, request
            )
        }
    }
}
