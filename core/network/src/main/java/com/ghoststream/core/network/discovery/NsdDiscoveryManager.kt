package com.ghoststream.core.network.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.ghoststream.core.model.DebugLogSink
import com.ghoststream.core.model.NearbyDevice
import com.ghoststream.core.model.NearbyDiscoveryState
import com.ghoststream.core.model.NoOpDebugLogSink
import com.ghoststream.core.model.buildFriendlyDisplayUrl
import java.net.Inet4Address
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class NsdDiscoveryManager(
    context: Context,
    private val endpointResolver: SessionEndpointResolver = SessionEndpointResolver(),
    private val debugLogSink: DebugLogSink = NoOpDebugLogSink,
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val stateLock = Any()
    private val _discoveryState = MutableStateFlow(NearbyDiscoveryState())
    private val devicesByService = linkedMapOf<String, NearbyDevice>()
    private val resolvingServices = linkedSetOf<String>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    val discoveryState: StateFlow<NearbyDiscoveryState> = _discoveryState.asStateFlow()

    fun start() {
        val manager = nsdManager
        if (manager == null) {
            _discoveryState.value = NearbyDiscoveryState(
                helperText = "Nearby discovery is not available on this device.",
                lastError = "NSD unavailable",
            )
            return
        }
        if (discoveryListener != null) return

        synchronized(stateLock) {
            devicesByService.clear()
            resolvingServices.clear()
        }
        _discoveryState.value = NearbyDiscoveryState(
            isDiscovering = true,
            helperText = "Looking for nearby GhostStream devices on this network...",
        )

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                if (discoveryListener === this) {
                    discoveryListener = null
                }
                debugLogSink.log("NsdDiscovery", "start discovery failed code=$errorCode serviceType=$serviceType")
                _discoveryState.value = NearbyDiscoveryState(
                    helperText = "Nearby discovery is unavailable right now. You can still use QR or a direct link.",
                    lastError = "Start failed ($errorCode)",
                )
                runCatching { manager.stopServiceDiscovery(this) }
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                if (discoveryListener === this) {
                    discoveryListener = null
                }
                debugLogSink.log("NsdDiscovery", "stop discovery failed code=$errorCode serviceType=$serviceType")
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                debugLogSink.log("NsdDiscovery", "discovery started serviceType=$serviceType")
                _discoveryState.update { current ->
                    current.copy(
                        isDiscovering = true,
                        helperText = if (current.devices.isEmpty()) {
                            "Looking for nearby GhostStream devices on this network..."
                        } else {
                            "Nearby GhostStream devices are ready to open."
                        },
                        lastError = null,
                    )
                }
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                if (discoveryListener === this) {
                    discoveryListener = null
                }
                debugLogSink.log("NsdDiscovery", "discovery stopped serviceType=$serviceType")
                _discoveryState.update { current -> current.copy(isDiscovering = false) }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType?.startsWith(GHOSTSTREAM_SERVICE_TYPE) != true) return
                debugLogSink.log("NsdDiscovery", "service found serviceName=${serviceInfo.serviceName}")
                resolveService(manager, serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val serviceName = serviceInfo.serviceName ?: return
                debugLogSink.log("NsdDiscovery", "service lost serviceName=$serviceName")
                synchronized(stateLock) {
                    devicesByService.remove(serviceName)
                    resolvingServices.remove(serviceName)
                }
                publishDevices()
            }
        }

        discoveryListener = listener
        runCatching {
            manager.discoverServices(GHOSTSTREAM_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            if (discoveryListener === listener) {
                discoveryListener = null
            }
            debugLogSink.log("NsdDiscovery", "discoverServices crashed", it)
            _discoveryState.value = NearbyDiscoveryState(
                helperText = "Nearby discovery is unavailable right now. You can still use QR or a direct link.",
                lastError = it.message,
            )
        }
    }

    fun stop() {
        val manager = nsdManager ?: return
        val listener = discoveryListener ?: return
        discoveryListener = null
        synchronized(stateLock) {
            devicesByService.clear()
            resolvingServices.clear()
        }
        runCatching {
            manager.stopServiceDiscovery(listener)
        }.onFailure {
            debugLogSink.log("NsdDiscovery", "stopServiceDiscovery failed", it)
        }
        _discoveryState.value = NearbyDiscoveryState()
    }

    fun refresh() {
        stop()
        start()
    }

    private fun resolveService(
        manager: NsdManager,
        serviceInfo: NsdServiceInfo,
    ) {
        val serviceName = serviceInfo.serviceName ?: return
        val shouldResolve = synchronized(stateLock) {
            resolvingServices.add(serviceName)
        }
        if (!shouldResolve) return

        manager.resolveService(
            serviceInfo,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    synchronized(stateLock) {
                        resolvingServices.remove(serviceName)
                    }
                    debugLogSink.log("NsdDiscovery", "resolve failed serviceName=$serviceName code=$errorCode")
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    synchronized(stateLock) {
                        resolvingServices.remove(serviceName)
                    }

                    val addresses = resolved.extractResolvableAddresses()
                    val address = addresses.firstOrNull() ?: return
                    val hostname = resolved.reflectedHostname()
                    val endpoint = endpointResolver.resolve(
                        hostname = hostname,
                        address = address,
                        port = resolved.port,
                    )
                    val metadata = DiscoveryMetadataCodec.decode(resolved.attributes.orEmpty())
                    val device = NearbyDevice(
                        id = metadata?.sessionId ?: "$serviceName:$address:${resolved.port}",
                        serviceName = serviceName,
                        friendlyUrl = endpoint.displayUrl ?: buildFriendlyDisplayUrl(hostname, resolved.port),
                        launchUrl = endpoint.launchUrl,
                        hostname = endpoint.hostname,
                        address = endpoint.address,
                        port = endpoint.port,
                        sessionId = metadata?.sessionId,
                        authRequired = metadata?.authRequired ?: false,
                        browserSupported = metadata?.browserSupported ?: true,
                        streamingSupported = metadata?.streamingSupported ?: true,
                    )
                    synchronized(stateLock) {
                        devicesByService[serviceName] = device
                    }
                    debugLogSink.log(
                        "NsdDiscovery",
                        "resolved serviceName=$serviceName hostname=$hostname address=$address port=${resolved.port} friendlyUrl=${device.friendlyUrl}",
                    )
                    publishDevices()
                }
            },
        )
    }

    private fun publishDevices() {
        val devices = synchronized(stateLock) {
            devicesByService.values.sortedBy { it.serviceName.lowercase() }
        }
        _discoveryState.value = NearbyDiscoveryState(
            isDiscovering = discoveryListener != null,
            devices = devices,
            helperText = if (devices.isEmpty()) {
                "Open GhostStream on another device on the same Wi-Fi or hotspot to see it here."
            } else {
                "Tap a nearby GhostStream session to open it in your browser."
            },
        )
    }
}

private fun NsdServiceInfo.extractResolvableAddresses(): List<String> {
    val addresses = buildList {
        if (Build.VERSION.SDK_INT >= 34) {
            hostAddresses
                .filterIsInstance<Inet4Address>()
                .mapNotNullTo(this) { address -> address.hostAddress?.trim() }
        } else {
            @Suppress("DEPRECATION")
            val hostAddress = host?.hostAddress?.trim()
            if (!hostAddress.isNullOrBlank()) {
                add(hostAddress)
            }
        }
    }
    return addresses.filter { address ->
        address.isNotBlank() &&
            !address.startsWith("127.") &&
            address != "0.0.0.0"
    }
}

private fun NsdServiceInfo.reflectedHostname(): String? {
    val raw = runCatching {
        javaClass.methods
            .firstOrNull { method -> method.name == "getHostname" && method.parameterCount == 0 }
            ?.invoke(this) as? String
    }.getOrNull()
    return raw
        ?.trim()
        ?.removeSuffix(".")
        ?.takeIf { it.isNotBlank() }
}
