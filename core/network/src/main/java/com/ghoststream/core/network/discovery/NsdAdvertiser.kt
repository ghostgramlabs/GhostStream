package com.ghoststream.core.network.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.ghoststream.core.model.DebugLogSink
import com.ghoststream.core.model.NoOpDebugLogSink
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class NsdAdvertiser(
    context: Context,
    private val endpointResolver: SessionEndpointResolver = SessionEndpointResolver(),
    private val debugLogSink: DebugLogSink = NoOpDebugLogSink,
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null

    suspend fun start(session: AdvertisedSessionInfo): AdvertisedServiceRegistration? {
        val manager = nsdManager ?: return null
        stop()
        return withTimeoutOrNull(3_000L) {
            suspendCancellableCoroutine { continuation ->
                val serviceInfo = NsdServiceInfo().apply {
                    serviceType = GHOSTSTREAM_SERVICE_TYPE
                    serviceName = buildAdvertisedServiceName(session.deviceLabel)
                    port = session.port
                    DiscoveryMetadataCodec.applyTo(
                        this,
                        DiscoveryMetadata(
                            sessionId = session.sessionId,
                            authRequired = session.authRequired,
                            browserSupported = session.browserSupported,
                            streamingSupported = session.streamingSupported,
                        ),
                    )
                }

                val listener = object : NsdManager.RegistrationListener {
                    override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                        if (registrationListener === this) {
                            registrationListener = null
                        }
                        debugLogSink.log("NsdAdvertiser", "registration failed code=$errorCode service=${serviceInfo?.serviceName}")
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }

                    override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                        if (registrationListener === this) {
                            registrationListener = null
                        }
                        debugLogSink.log("NsdAdvertiser", "unregistration failed code=$errorCode service=${serviceInfo?.serviceName}")
                    }

                    override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                        registrationListener = this
                        val hostname = serviceInfo.reflectedHostname()
                        val resolved = endpointResolver.resolve(
                            hostname = hostname,
                            address = "127.0.0.1",
                            port = session.port,
                        )
                        debugLogSink.log(
                            "NsdAdvertiser",
                            "registered serviceName=${serviceInfo.serviceName} hostname=$hostname displayUrl=${resolved.displayUrl}",
                        )
                        if (continuation.isActive) {
                            continuation.resume(
                                AdvertisedServiceRegistration(
                                    serviceName = serviceInfo.serviceName ?: buildAdvertisedServiceName(session.deviceLabel),
                                    hostname = hostname,
                                    displayUrl = resolved.displayUrl,
                                ),
                            )
                        }
                    }

                    override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                        if (registrationListener === this) {
                            registrationListener = null
                        }
                        debugLogSink.log("NsdAdvertiser", "service unregistered serviceName=${serviceInfo.serviceName}")
                    }
                }

                registrationListener = listener
                runCatching {
                    manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
                }.onFailure {
                    if (registrationListener === listener) {
                        registrationListener = null
                    }
                    debugLogSink.log("NsdAdvertiser", "registerService crashed", it)
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }

                continuation.invokeOnCancellation {
                    if (registrationListener === listener) {
                        runCatching { manager.unregisterService(listener) }
                        registrationListener = null
                    }
                }
            }
        }.also {
            if (it == null) {
                debugLogSink.log("NsdAdvertiser", "registration skipped or timed out")
            }
        }
    }

    suspend fun stop() {
        val manager = nsdManager ?: return
        val listener = registrationListener ?: return
        registrationListener = null
        runCatching {
            manager.unregisterService(listener)
        }.onFailure {
            debugLogSink.log("NsdAdvertiser", "unregisterService failed", it)
        }
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
