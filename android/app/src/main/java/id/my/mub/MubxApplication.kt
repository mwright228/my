package id.my.mub

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class MubxApplication : Application() {

    companion object {
        const val VPN_CHANNEL_ID = "mubx_vpn_channel"
        lateinit var instance: MubxApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        id.my.mub.data.ProfileStore.init(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.vpn_channel_name)
            val descriptionText = getString(R.string.vpn_channel_desc)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(VPN_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
