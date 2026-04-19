package com.ghoststream.core.network.server

import android.util.Log
import com.ghoststream.core.model.SharedFolder
import com.ghoststream.core.model.SharedItem
import java.util.UUID

/**
 * Handles DLNA XML descriptions and ContentDirectory service logic.
 * Translates application models into UPnP/DLNA compliant XML.
 */
class DlnaService(
    private val deviceName: String,
    private val deviceUuid: String,
    private val serverUrl: String
) {
    fun getDeviceDescription(): String {
        return """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
                <specVersion>
                    <major>1</major>
                    <minor>0</minor>
                </specVersion>
                <device>
                    <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
                    <friendlyName>$deviceName</friendlyName>
                    <manufacturer>DirectServe</manufacturer>
                    <manufacturerURL>https://github.com/ghostgramlabs/DirectServe</manufacturerURL>
                    <modelDescription>Professional Mobile Media Server</modelDescription>
                    <modelName>DirectServe Media Server</modelName>
                    <modelNumber>1.0</modelNumber>
                    <modelURL>https://github.com/ghostgramlabs/DirectServe</modelURL>
                    <UDN>uuid:$deviceUuid</UDN>
                    <serviceList>
                        <service>
                            <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                            <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
                            <SCPDURL>/dlna/ContentDirectory.xml</SCPDURL>
                            <controlURL>/dlna/control/ContentDirectory</controlURL>
                            <eventSubURL>/dlna/event/ContentDirectory</eventSubURL>
                        </service>
                        <service>
                            <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                            <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
                            <SCPDURL>/dlna/ConnectionManager.xml</SCPDURL>
                            <controlURL>/dlna/control/ConnectionManager</controlURL>
                            <eventSubURL>/dlna/event/ConnectionManager</eventSubURL>
                        </service>
                    </serviceList>
                    <presentationURL>/</presentationURL>
                </device>
            </root>
        """.trimIndent()
    }

    fun getContentDirectoryScpd(): String {
        return """
            <?xml version="1.0"?>
            <scpd xmlns="urn:schemas-upnp-org:service-1-0">
                <specVersion><major>1</major><minor>0</minor></specVersion>
                <actionList>
                    <action>
                        <name>GetSortCapabilities</name>
                        <argumentList>
                            <argument>
                                <name>SortCaps</name>
                                <direction>out</direction>
                                <relatedStateVariable>SortCapabilities</relatedStateVariable>
                            </argument>
                        </argumentList>
                    </action>
                    <action>
                        <name>Browse</name>
                        <argumentList>
                            <argument><name>ObjectID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ObjectID</relatedStateVariable></argument>
                            <argument><name>BrowseFlag</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_BrowseFlag</relatedStateVariable></argument>
                            <argument><name>Filter</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Filter</relatedStateVariable></argument>
                            <argument><name>StartingIndex</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Index</relatedStateVariable></argument>
                            <argument><name>RequestedCount</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
                            <argument><name>SortCriteria</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SortCriteria</relatedStateVariable></argument>
                            <argument><name>Result</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Result</relatedStateVariable></argument>
                            <argument><name>NumberReturned</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
                            <argument><name>TotalMatches</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
                            <argument><name>UpdateID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_UpdateID</relatedStateVariable></argument>
                        </argumentList>
                    </action>
                </actionList>
                <serviceStateTable>
                    <stateVariable sendEvents="no"><name>SortCapabilities</name><dataType>string</dataType></stateVariable>
                    <stateVariable sendEvents="no"><name>A_ARG_TYPE_ObjectID</name><dataType>string</dataType></stateVariable>
                    <stateVariable sendEvents="no"><name>A_ARG_TYPE_BrowseFlag</name><dataType>string</dataType></stateVariable>
                    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Filter</name><dataType>string</dataType></stateVariable>
                    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Index</name><dataType>ui4</dataType></stateVariable>
                    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Count</name><dataType>ui4</dataType></stateVariable>
                    <stateVariable sendEvents="no"><name>A_ARG_TYPE_SortCriteria</name><dataType>string</dataType></stateVariable>
                    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Result</name><dataType>string</dataType></stateVariable>
                    <stateVariable sendEvents="no"><name>A_ARG_TYPE_UpdateID</name><dataType>ui4</dataType></stateVariable>
                </serviceStateTable>
            </scpd>
        """.trimIndent()
    }

    fun getConnectionManagerScpd(): String {
        return """
            <?xml version="1.0"?>
            <scpd xmlns="urn:schemas-upnp-org:service-1-0">
                <specVersion><major>1</major><minor>0</minor></specVersion>
                <actionList>
                    <action>
                        <name>GetProtocolInfo</name>
                        <argumentList>
                            <argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument>
                            <argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument>
                        </argumentList>
                    </action>
                </actionList>
                <serviceStateTable>
                    <stateVariable sendEvents="no"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>
                    <stateVariable sendEvents="no"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>
                </serviceStateTable>
            </scpd>
        """.trimIndent()
    }

    fun buildSoapResponse(content: String): String {
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    $content
                </s:Body>
            </s:Envelope>
        """.trimIndent()
    }

    fun buildDidlLite(folders: List<SharedFolder>, items: List<SharedItem>): String {
        val sb = StringBuilder()
        sb.append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
        sb.append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
        sb.append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" ")
        sb.append("xmlns:sec=\"http://www.sec.co.kr/\">")

        folders.forEach { folder ->
            sb.append("<container id=\"folder:${folder.id}\" parentID=\"0\" restricted=\"1\">")
            sb.append("<dc:title>${esc(folder.displayName)}</dc:title>")
            sb.append("<upnp:class>object.container.storageFolder</upnp:class>")
            sb.append("</container>")
        }

        items.forEach { item ->
            val upnpClass = when (item.category) {
                com.ghoststream.core.model.MediaCategory.VIDEO -> "object.item.videoItem"
                com.ghoststream.core.model.MediaCategory.PHOTO -> "object.item.imageItem"
                com.ghoststream.core.model.MediaCategory.MUSIC -> "object.item.audioItem.musicTrack"
                else -> "object.item"
            }
            
            val mimeType = item.mimeType ?: "application/octet-stream"
            val streamUrl = "$serverUrl/stream/${item.id}"
            
            sb.append("<item id=\"item:${item.id}\" parentID=\"${if (item.sourceFolderId != null) "folder:${item.sourceFolderId}" else "0"}\" restricted=\"1\">")
            sb.append("<dc:title>${esc(item.displayName)}</dc:title>")
            sb.append("<upnp:class>$upnpClass</upnp:class>")
            sb.append("<res protocolInfo=\"http-get:*:$mimeType:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\">$streamUrl</res>")
            if (item.thumbnailKey != null) {
                sb.append("<upnp:albumArtURI>${serverUrl}/thumb/${item.id}</upnp:albumArtURI>")
            }
            sb.append("</item>")
        }

        sb.append("</DIDL-Lite>")
        return sb.toString()
    }

    private fun esc(s: String): String {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    companion object {
        private const val TAG = "DlnaService"
    }
}
