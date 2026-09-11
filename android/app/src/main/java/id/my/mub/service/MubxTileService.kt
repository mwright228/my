package id.my.mub.service

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import androidx.annotation.SuppressLint
import id.my.mub.data.VpnState
import id.my.mub.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.N)
class MubxTileService : TileService() {

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        scope = CoroutineScope(Dispatchers.Main + Job())
        scope?.launch {
            MubxVpnService.vpnState.collectLatest { state ->
                updateTileState(state)
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val currentState = MubxVpnService.vpnState.value

        if (currentState is VpnState.Connected || currentState is VpnState.Connecting) {
            val intent = Intent(this, MubxVpnService::class.java).apply {
                action = MubxVpnService.ACTION_DISCONNECT
            }
            startService(intent)
        } else {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent == null) {
                // VPN permission already granted
                val intent = Intent(this, MubxVpnService::class.java).apply {
                    action = MubxVpnService.ACTION_CONNECT
                }
                startService(intent)
            } else {
                // Need to open main activity to request consent
                val activityIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val pendingIntent = PendingIntent.getActivity(
                        this,
                        0,
                        activityIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    startActivityAndCollapse(pendingIntent)
                } else {
                    @SuppressLint("StartActivityAndCollapseDeprecated")
                    @Suppress("DEPRECATION")
                    fun launchLegacyTileActivity() {
                        startActivityAndCollapse(activityIntent)
                    }
                    launchLegacyTileActivity()
                }
            }
        }
    }

    private fun updateTileState(state: VpnState) {
        val tile = qsTile ?: return
        when (state) {
            is VpnState.Connected -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "MUB-X"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "${state.rxSpeedMbps.toInt()} Mbps"
                }
            }
            is VpnState.Connecting -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = "MUB-X"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Connecting..."
                }
            }
            is VpnState.Disconnecting -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = "MUB-X"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Disconnecting..."
                }
            }
            is VpnState.Disconnected, is VpnState.Error -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "MUB-X"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Tap to Connect"
                }
            }
        }
        tile.updateTile()
    }
}
