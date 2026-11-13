package com.rodrigues.gestor.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * Desativado na arquitetura offline somente para consulta.
 * Pendências de versões de desenvolvimento anteriores NÃO são enviadas
 * ao servidor sem nova confirmação explícita do gestor.
 */
class OfflineOrderSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()

    companion object {
        fun schedule(context: Context) {
            // Mantido por compatibilidade binária. Não gera novas filas.
        }
        fun cancelLegacy(context: Context) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork("gestor20_offline_sync_v1")
        }
    }
}
