package info.dvkr.screenstream.rtsp.internal.onvif

import info.dvkr.screenstream.rtsp.settings.RtspSettings
import java.util.Calendar
import java.util.TimeZone
import kotlin.uuid.Uuid

internal object OnvifMessages {
    private const val SOAP_ENV = "http://www.w3.org/2003/05/soap-envelope"
    private const val WSA = "http://schemas.xmlsoap.org/ws/2004/08/addressing"
    private const val WSD = "http://schemas.xmlsoap.org/ws/2005/04/discovery"
    private const val DEVICE = "http://www.onvif.org/ver10/device/wsdl"
    private const val NETWORK = "http://www.onvif.org/ver10/network/wsdl"
    private const val MEDIA = "http://www.onvif.org/ver10/media/wsdl"
    private const val SCHEMA = "http://www.onvif.org/ver10/schema"
    private const val DISCOVERY_TO = "urn:schemas-xmlsoap-org:ws:2005:04:discovery"
    private const val ANONYMOUS_TO = "http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous"
    private const val DEFAULT_WIDTH = 1920
    private const val DEFAULT_HEIGHT = 1080
    private const val DEFAULT_FPS = 30

    fun hello(deviceInfo: OnvifDeviceInfo, endpoint: OnvifServiceEndpoint, messageNumber: Long): String =
        discoveryEnvelope(
            action = "$WSD/Hello",
            appSequence = appSequence(deviceInfo, messageNumber),
            body = """
                <d:Hello>
                  <wsa:EndpointReference>
                    <wsa:Address>${deviceInfo.endpointUrn}</wsa:Address>
                  </wsa:EndpointReference>
                  <d:Types>dn:NetworkVideoTransmitter tds:Device</d:Types>
                  <d:Scopes>${deviceInfo.scopesXml()}</d:Scopes>
                  <d:XAddrs>${endpoint.deviceServiceUrl}</d:XAddrs>
                  <d:MetadataVersion>1</d:MetadataVersion>
                </d:Hello>
            """.trimIndent()
        )

    fun bye(deviceInfo: OnvifDeviceInfo, messageNumber: Long): String =
        discoveryEnvelope(
            action = "$WSD/Bye",
            appSequence = appSequence(deviceInfo, messageNumber),
            body = """
                <d:Bye>
                  <wsa:EndpointReference>
                    <wsa:Address>${deviceInfo.endpointUrn}</wsa:Address>
                  </wsa:EndpointReference>
                </d:Bye>
            """.trimIndent()
        )

    fun probeMatch(deviceInfo: OnvifDeviceInfo, endpoint: OnvifServiceEndpoint, relatesTo: String?, messageNumber: Long): String {
        val relatesToXml = relatesTo?.let { "<wsa:RelatesTo>${it.escapeXml()}</wsa:RelatesTo>" }.orEmpty()
        return discoveryEnvelope(
            action = "$WSD/ProbeMatches",
            to = ANONYMOUS_TO,
            appSequence = appSequence(deviceInfo, messageNumber),
            headerExtra = relatesToXml,
            body = """
                <d:ProbeMatches>
                  <d:ProbeMatch>
                    <wsa:EndpointReference>
                      <wsa:Address>${deviceInfo.endpointUrn}</wsa:Address>
                    </wsa:EndpointReference>
                    <d:Types>dn:NetworkVideoTransmitter tds:Device</d:Types>
                    <d:Scopes>${deviceInfo.scopesXml()}</d:Scopes>
                    <d:XAddrs>${endpoint.deviceServiceUrl}</d:XAddrs>
                    <d:MetadataVersion>1</d:MetadataVersion>
                  </d:ProbeMatch>
                </d:ProbeMatches>
            """.trimIndent()
        )
    }

    fun getDeviceInformation(deviceInfo: OnvifDeviceInfo): String =
        soapEnvelope(
            """
            <tds:GetDeviceInformationResponse>
              <tds:Manufacturer>${deviceInfo.manufacturer}</tds:Manufacturer>
              <tds:Model>${deviceInfo.model}</tds:Model>
              <tds:FirmwareVersion>${deviceInfo.firmwareVersion}</tds:FirmwareVersion>
              <tds:SerialNumber>${deviceInfo.serialNumber}</tds:SerialNumber>
              <tds:HardwareId>${deviceInfo.hardwareId}</tds:HardwareId>
            </tds:GetDeviceInformationResponse>
        """.trimIndent()
        )

    fun getSystemDateAndTime(): String {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        return soapEnvelope(
            """
            <tds:GetSystemDateAndTimeResponse>
              <tds:SystemDateAndTime>
                <tt:DateTimeType>Manual</tt:DateTimeType>
                <tt:DaylightSavings>false</tt:DaylightSavings>
                <tt:TimeZone>
                  <tt:TZ>UTC</tt:TZ>
                </tt:TimeZone>
                <tt:UTCDateTime>
                  <tt:Time>
                    <tt:Hour>${utc.get(Calendar.HOUR_OF_DAY)}</tt:Hour>
                    <tt:Minute>${utc.get(Calendar.MINUTE)}</tt:Minute>
                    <tt:Second>${utc.get(Calendar.SECOND)}</tt:Second>
                  </tt:Time>
                  <tt:Date>
                    <tt:Year>${utc.get(Calendar.YEAR)}</tt:Year>
                    <tt:Month>${utc.get(Calendar.MONTH) + 1}</tt:Month>
                    <tt:Day>${utc.get(Calendar.DAY_OF_MONTH)}</tt:Day>
                  </tt:Date>
                </tt:UTCDateTime>
              </tds:SystemDateAndTime>
            </tds:GetSystemDateAndTimeResponse>
        """.trimIndent()
        )
    }

    fun getScopes(deviceInfo: OnvifDeviceInfo): String =
        soapEnvelope(
            """
            <tds:GetScopesResponse>
              ${
                deviceInfo.scopes.joinToString("\n              ") { scope ->
                    """
                <tds:Scopes>
                  <tt:ScopeDef>Fixed</tt:ScopeDef>
                  <tt:ScopeItem>${scope.escapeXml()}</tt:ScopeItem>
                </tds:Scopes>
                """.trimIndent()
                }
            }
            </tds:GetScopesResponse>
        """.trimIndent()
        )

    fun getDiscoveryMode(): String =
        soapEnvelope(
            """
            <tds:GetDiscoveryModeResponse>
              <tds:DiscoveryMode>Discoverable</tds:DiscoveryMode>
            </tds:GetDiscoveryModeResponse>
        """.trimIndent()
        )

    fun getCapabilities(endpoint: OnvifServiceEndpoint, protocolPolicy: RtspSettings.Values.ProtocolPolicy): String =
        soapEnvelope(
            """
            <tds:GetCapabilitiesResponse>
              <tds:Capabilities>
                <tt:Device>
                  <tt:XAddr>${endpoint.deviceServiceUrl}</tt:XAddr>
                </tt:Device>
                <tt:Media>
                  <tt:XAddr>${endpoint.deviceServiceUrl}</tt:XAddr>
                  <tt:StreamingCapabilities>
                    <tt:RTPMulticast>false</tt:RTPMulticast>
                    <tt:RTP_TCP>false</tt:RTP_TCP>
                    <tt:RTP_RTSP_TCP>${protocolPolicy != RtspSettings.Values.ProtocolPolicy.UDP}</tt:RTP_RTSP_TCP>
                  </tt:StreamingCapabilities>
                </tt:Media>
              </tds:Capabilities>
            </tds:GetCapabilitiesResponse>
        """.trimIndent()
        )

    fun getServices(endpoint: OnvifServiceEndpoint): String =
        soapEnvelope(
            """
            <tds:GetServicesResponse>
              <tds:Service>
                <tds:Namespace>$DEVICE</tds:Namespace>
                <tds:XAddr>${endpoint.deviceServiceUrl}</tds:XAddr>
                <tds:Version>
                  <tt:Major>2</tt:Major>
                  <tt:Minor>0</tt:Minor>
                </tds:Version>
              </tds:Service>
              <tds:Service>
                <tds:Namespace>$MEDIA</tds:Namespace>
                <tds:XAddr>${endpoint.deviceServiceUrl}</tds:XAddr>
                <tds:Version>
                  <tt:Major>2</tt:Major>
                  <tt:Minor>0</tt:Minor>
                </tds:Version>
              </tds:Service>
            </tds:GetServicesResponse>
        """.trimIndent()
        )

    fun getProfiles(metadata: OnvifVideoMetadata?): String {
        val width = metadata?.width?.takeIf { it > 0 } ?: DEFAULT_WIDTH
        val height = metadata?.height?.takeIf { it > 0 } ?: DEFAULT_HEIGHT
        val fps = metadata?.fps?.takeIf { it > 0 } ?: DEFAULT_FPS
        return soapEnvelope(
            """
            <trt:GetProfilesResponse>
              <trt:Profiles token="Profile_1" fixed="true">
                <tt:Name>ScreenStream</tt:Name>
                <tt:VideoSourceConfiguration token="VideoSource_1">
                  <tt:Name>Screen</tt:Name>
                  <tt:UseCount>1</tt:UseCount>
                  <tt:SourceToken>VideoSource_1</tt:SourceToken>
                  <tt:Bounds x="0" y="0" width="$width" height="$height"/>
                </tt:VideoSourceConfiguration>
                <tt:VideoEncoderConfiguration token="VideoEncoder_1">
                  <tt:Name>VideoEncoder</tt:Name>
                  <tt:UseCount>1</tt:UseCount>
                  <tt:Encoding>H264</tt:Encoding>
                  <tt:Resolution>
                    <tt:Width>$width</tt:Width>
                    <tt:Height>$height</tt:Height>
                  </tt:Resolution>
                  <tt:Quality>50</tt:Quality>
                  <tt:RateControl>
                    <tt:FrameRateLimit>$fps</tt:FrameRateLimit>
                    <tt:EncodingInterval>1</tt:EncodingInterval>
                    <tt:BitrateLimit>0</tt:BitrateLimit>
                  </tt:RateControl>
                  <tt:H264>
                    <tt:GovLength>$fps</tt:GovLength>
                    <tt:H264Profile>${metadata?.h264Profile ?: "Baseline"}</tt:H264Profile>
                  </tt:H264>
                  <tt:Multicast>
                    <tt:Address>
                      <tt:Type>IPv4</tt:Type>
                      <tt:IPv4Address>0.0.0.0</tt:IPv4Address>
                    </tt:Address>
                    <tt:Port>0</tt:Port>
                    <tt:TTL>1</tt:TTL>
                    <tt:AutoStart>false</tt:AutoStart>
                  </tt:Multicast>
                  <tt:SessionTimeout>PT0S</tt:SessionTimeout>
                </tt:VideoEncoderConfiguration>
              </trt:Profiles>
            </trt:GetProfilesResponse>
        """.trimIndent()
        )
    }

    fun getStreamUri(endpoint: OnvifServiceEndpoint): String =
        soapEnvelope(
            """
            <trt:GetStreamUriResponse>
              <trt:MediaUri>
                <tt:Uri>${endpoint.rtspEndpoint.rtspUrl.escapeXml()}</tt:Uri>
                <tt:InvalidAfterConnect>false</tt:InvalidAfterConnect>
                <tt:InvalidAfterReboot>false</tt:InvalidAfterReboot>
                <tt:Timeout>PT0S</tt:Timeout>
              </trt:MediaUri>
            </trt:GetStreamUriResponse>
        """.trimIndent()
        )

    fun getVideoSources(metadata: OnvifVideoMetadata?): String {
        val width = metadata?.width?.takeIf { it > 0 } ?: DEFAULT_WIDTH
        val height = metadata?.height?.takeIf { it > 0 } ?: DEFAULT_HEIGHT
        val fps = metadata?.fps?.takeIf { it > 0 } ?: DEFAULT_FPS
        return soapEnvelope(
            """
            <trt:GetVideoSourcesResponse>
              <trt:VideoSources token="VideoSource_1">
                <tt:Framerate>$fps</tt:Framerate>
                <tt:Resolution>
                  <tt:Width>$width</tt:Width>
                  <tt:Height>$height</tt:Height>
                </tt:Resolution>
              </trt:VideoSources>
            </trt:GetVideoSourcesResponse>
        """.trimIndent()
        )
    }

    fun fault(reason: String = "Action not supported"): String =
        soapEnvelope(
            """
            <env:Fault>
              <env:Code>
                <env:Value>env:Receiver</env:Value>
              </env:Code>
              <env:Reason>
                <env:Text xml:lang="en">${reason.escapeXml()}</env:Text>
              </env:Reason>
            </env:Fault>
        """.trimIndent()
        )

    private fun discoveryEnvelope(
        action: String,
        body: String,
        appSequence: String,
        headerExtra: String = "",
        to: String = DISCOVERY_TO
    ): String =
        """
            <?xml version="1.0" encoding="UTF-8"?>
            <env:Envelope xmlns:env="$SOAP_ENV" xmlns:wsa="$WSA" xmlns:d="$WSD" xmlns:tds="$DEVICE" xmlns:dn="$NETWORK">
              <env:Header>
                <wsa:MessageID>uuid:${Uuid.random()}</wsa:MessageID>
                $headerExtra
                <wsa:To>$to</wsa:To>
                <wsa:Action>$action</wsa:Action>
                $appSequence
              </env:Header>
              <env:Body>
                $body
              </env:Body>
            </env:Envelope>
        """.trimIndent()

    private fun soapEnvelope(body: String): String =
        """
            <?xml version="1.0" encoding="UTF-8"?>
            <env:Envelope xmlns:env="$SOAP_ENV" xmlns:tds="$DEVICE" xmlns:trt="$MEDIA" xmlns:tt="$SCHEMA">
              <env:Body>
                $body
              </env:Body>
            </env:Envelope>
        """.trimIndent()

    private fun appSequence(deviceInfo: OnvifDeviceInfo, messageNumber: Long): String =
        """<d:AppSequence InstanceId="${deviceInfo.instanceId}" MessageNumber="$messageNumber"/>"""

    private fun OnvifDeviceInfo.scopesXml(): String =
        scopes.joinToString(" ") { it.escapeXml() }

    private fun String.escapeXml(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
