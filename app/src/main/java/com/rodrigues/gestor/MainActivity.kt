package com.rodrigues.gestor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.rodrigues.gestor.notifications.DeviceRegistrar
import com.rodrigues.gestor.notifications.GestorConnectionService
import com.rodrigues.gestor.notifications.NotificationHelper
import com.rodrigues.gestor.ui.GestorApp
import com.rodrigues.gestor.ui.theme.RodriguesGestorTheme

class MainActivity : ComponentActivity() {
    private var requestedOrderId by mutableStateOf<String?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        GestorConnectionService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrderId = intent?.getStringExtra(EXTRA_ORDER_ID)
        NotificationHelper.createChannels(this)
        requestNotificationsIfNeeded()
        FirebaseMessaging.getInstance().token.addOnSuccessListener(DeviceRegistrar::register)
        GestorConnectionService.start(this)

        setContent {
            RodriguesGestorTheme {
                GestorApp(
                    requestedOrderId = requestedOrderId,
                    onRequestedOrderConsumed = { requestedOrderId = null }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        GestorConnectionService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedOrderId = intent.getStringExtra(EXTRA_ORDER_ID)
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_ORDER_ID = "open_order_id"
    }
}
