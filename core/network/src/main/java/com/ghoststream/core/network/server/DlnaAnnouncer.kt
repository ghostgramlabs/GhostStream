package com.ghoststream.core.network.server

import android.util.Log
import kotlinx.coroutines.*
import java.net.*
import java.util.*

/**
 * Handles SSDP (Simple Service Discovery Protocol) for DLNA.
 * Periodically announces the MediaServer presence and responds to discovery queries.
 */
class DlnaAnnouncer(
    private val deviceName: String,
    private val deviceUuid: String,
    private val serverPort: Int,
    val ipAddress: String,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private var announceJob: Job? = null
    private var listenJob: Job? = null
    
    private val multicastGroup = "239.255.255.250"
    private val multicastPort = 1900
    private val searchTarget = "urn:schemas-upnp-org:service:ContentDirectory:1"
    private val deviceType = "urn:schemas-upnp-org:device:MediaServer:1"
    
    private val usnRoot = "uuid:$deviceUuid::upnp:rootdevice"
    private val usnDevice = "uuid:$deviceUuid::$deviceType"
    private val usnService = "uuid:$deviceUuid::$searchTarget"
    
    private val location = "http://$ipAddress:$serverPort/dlna/description.xml"

    fun start() {
        Log.i(TAG, "Starting DLNA Announcer for $deviceName at $location")
        
        // 1. Periodic NOTIFY loop
        announceJob = scope.launch {
            while (isActive) {
                sendNotify()
                delay(30_000) // Announce every 30 seconds
            }
        }
        
        // 2. M-SEARCH listener
        listenJob = scope.launch {
            startListening()
        }
    }

    fun stop() {
        Log.i(TAG, "Stopping DLNA Announcer")
        announceJob?.cancel()
        listenJob?.cancel()
        
        // Send byebye
        scope.launch {
            sendByebye()
        }
    }

    private suspend fun sendNotify() = withContext(Dispatchers.IO) {
        try {
            val targets = listOf("upnp:rootdevice", deviceType, searchTarget, "uuid:$deviceUuid")
            val usns = listOf(usnRoot, usnDevice, usnService, "uuid:$deviceUuid")
            
            targets.zip(usns).forEach { (nt, usn) ->
                val packet = """
                    NOTIFY * HTTP/1.1
                    HOST: $multicastGroup:$multicastPort
                    CACHE-CONTROL: max-age=1800
                    LOCATION: $location
                    NT: $nt
                    NTS: ssdp:alive
                    SERVER: Android/DirectServe UPnP/1.1
                    USN: $usn
                    
                """.trimIndent().replace("\n", "\r\n") + "\r\n"
                
                sendUdp(packet)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SSDP NOTIFY", e)
        }
    }

    private suspend fun sendByebye() = withContext(Dispatchers.IO) {
        try {
            val targets = listOf("upnp:rootdevice", deviceType, searchTarget, "uuid:$deviceUuid")
            val usns = listOf(usnRoot, usnDevice, usnService, "uuid:$deviceUuid")
            
            targets.zip(usns).forEach { (nt, usn) ->
                val packet = """
                    NOTIFY * HTTP/1.1
                    HOST: $multicastGroup:$multicastPort
                    NT: $nt
                    NTS: ssdp:byebye
                    USN: $usn
                    
                """.trimIndent().replace("\n", "\r\n") + "\r\n"
                
                sendUdp(packet)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SSDP byebye", e)
        }
    }

    private suspend fun startListening() = withContext(Dispatchers.IO) {
        var socket: MulticastSocket? = null
        try {
            socket = MulticastSocket(multicastPort)
            val group = InetAddress.getByName(multicastGroup)
            socket.joinGroup(group)
            
            val buffer = ByteArray(2048)
            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                
                val message = String(packet.data, 0, packet.length)
                if (message.startsWith("M-SEARCH")) {
                    handleSearch(message, packet.address, packet.port)
                }
            }
        } catch (e: Exception) {
            if (isActive) Log.e(TAG, "SSDP Listener error", e)
        } finally {
            socket?.close()
        }
    }

    private fun handleSearch(message: String, address: InetAddress, port: Int) {
        // Only respond if searching for root, media server, or content directory
        val isTarget = message.contains("ST: ssdp:all") || 
                       message.contains("ST: upnp:rootdevice") || 
                       message.contains("ST: $deviceType") || 
                       message.contains("ST: $searchTarget")
        
        if (!isTarget) return
        
        scope.launch(Dispatchers.IO) {
            try {
                val response = """
                    HTTP/1.1 200 OK
                    CACHE-CONTROL: max-age=1800
                    DATE: ${Date()}
                    EXT:
                    LOCATION: $location
                    SERVER: Android/DirectServe UPnP/1.1
                    ST: $deviceType
                    USN: $usnDevice
                    
                """.trimIndent().replace("\n", "\r\n") + "\r\n"
                
                val data = response.toByteArray()
                val responsePacket = DatagramPacket(data, data.size, address, port)
                DatagramSocket().use { it.send(responsePacket) }
                Log.d(TAG, "Responded to M-SEARCH from ${address.hostAddress}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send M-SEARCH response", e)
            }
        }
    }

    private fun sendUdp(message: String) {
        try {
            val group = InetAddress.getByName(multicastGroup)
            val data = message.toByteArray()
            val packet = DatagramPacket(data, data.size, group, multicastPort)
            DatagramSocket().use { it.send(packet) }
        } catch (e: Exception) {
            Log.w(TAG, "UDP Send failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "DlnaAnnouncer"
    }
}
