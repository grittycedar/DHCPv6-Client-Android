package be.mygod.dhcpv6client

import android.annotation.TargetApi
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.*
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.core.os.postDelayed
import androidx.lifecycle.MutableLiveData
import be.mygod.dhcpv6client.App.Companion.app
import be.mygod.dhcpv6client.widget.SmartSnackbar
import com.crashlytics.android.Crashlytics
import java.io.IOException

class Dhcp6cService : Service() {
    companion object {
        private const val TAG = "Dhcp6cService"
        var running = false
        val enabled = MutableLiveData<Boolean>().apply {
            value = BootReceiver.enabled
            observeForever {
                val intent = Intent(app, Dhcp6cService::class.java)
                if (it && !Dhcp6cService.running) {
                    if (app.backgroundUnavailable) @TargetApi(26) {
                        // this block can only be reached on API 26+
                        app.startForegroundService(intent)
                    } else app.startService(intent)
                } else if (!it && Dhcp6cService.running) app.stopService(intent)
                BootReceiver.enabled = it
            }
        }
    }

    private val connectivity by lazy { getSystemService<ConnectivityManager>()!! }
    private val request = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        var registered = false
        val working = HashMap<Network, LinkProperties>()
        val reporting = HashMap<Network, Long>()

        @TargetApi(23)
        private fun reportPeriodically(network: Network) {
            val delay = reporting[network] ?: return
            connectivity.reportNetworkConnectivity(network, true)
            Crashlytics.log(Log.INFO, TAG, "Requesting reprobe for $network (retry in $delay ms)")
            if (delay < 5 * 60 * 1000) {   // if 10 mins have passed, give up
                reporting[network] = delay * 2
                app.handler.postDelayed(delay, network) { reportPeriodically(network) }
            } else reporting.remove(network)
        }

        override fun onAvailable(network: Network) =
                onLinkPropertiesChanged(network, connectivity.getLinkProperties(network))
        override fun onLinkPropertiesChanged(network: Network, link: LinkProperties?) {
            val oldLink = working.put(network, link ?: return)
            val ifname = link.interfaceName
            if (ifname == null) {
                if (oldLink?.interfaceName != null) onLost(network)
                return
            }
            if (ifname != oldLink?.interfaceName) try {
                onLost(oldLink?.interfaceName)
                Dhcp6cManager.startInterface(ifname)
            } catch (e: IOException) {
                e.printStackTrace()
                if (e.message?.contains("connect:") == true) try {
                    Dhcp6cManager.forceRestartDaemon(working.values.map { it.interfaceName })
                } catch (e: IOException) {
                    e.printStackTrace()
                    Crashlytics.logException(e)
                } else {
                    SmartSnackbar.make(e.localizedMessage).show()
                    Crashlytics.logException(e)
                }
            } else if (link.linkAddresses.size > oldLink.linkAddresses.size) {
                Log.d(TAG, "Link addresses updated for $network: $oldLink => $link")
                // update connectivity on linkAddresses change
                if (Build.VERSION.SDK_INT < 23) @Suppress("DEPRECATION") connectivity.reportBadNetwork(network)
                else if (true != connectivity.getNetworkCapabilities(network)?.hasCapability(
                                NetworkCapabilities.NET_CAPABILITY_VALIDATED) && !reporting.containsKey(network)) {
                    reporting[network] = 2000
                    reportPeriodically(network)
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if ((Build.VERSION.SDK_INT < 23 ||
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) &&
                    reporting.remove(network) != null) app.handler.removeCallbacksAndMessages(network)
        }

        override fun onLost(network: Network?) {
            onLost(working.remove(network ?: return)?.interfaceName)
            app.handler.removeCallbacksAndMessages(network)
            reporting.remove(network)
        }
        private fun onLost(ifname: String?) {
            if (ifname == null) return
            synchronized(Dhcp6cDaemon.addressLookup) {
                if (Dhcp6cDaemon.addressLookup.remove(ifname) != null) Dhcp6cDaemon.postAddressUpdate()
            }
            Dhcp6cManager.stopInterface(ifname)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onBind(p0: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (app.backgroundUnavailable) @TargetApi(26) {
            getSystemService<NotificationManager>()?.createNotificationChannel(
                    NotificationChannel("service", Dhcp6cManager.DHCP6C, NotificationManager.IMPORTANCE_NONE).apply {
                        lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
                    })
            startForeground(1, NotificationCompat.Builder(this, "service").run {
                priority = NotificationCompat.PRIORITY_LOW
                build()
            })
        }
        if (!callback.registered) {
            try {
                connectivity.registerNetworkCallback(request, callback)
                callback.registered = true
            } catch (e: IOException) {
                SmartSnackbar.make(e.localizedMessage).show()
                Crashlytics.logException(e)
                e.printStackTrace()
                stopSelf(startId)
                enabled.value = false
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (callback.registered) {
            connectivity.unregisterNetworkCallback(callback)
            callback.working.clear()
            callback.registered = false
        }
        try {
            Dhcp6cManager.stopDaemonSync()
        } catch (e: IOException) {
            e.printStackTrace()
            Crashlytics.logException(e)
        }
        running = false
        super.onDestroy()
    }
}
