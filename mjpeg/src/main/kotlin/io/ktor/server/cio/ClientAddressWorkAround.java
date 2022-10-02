package io.ktor.server.cio;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;

import io.ktor.server.request.ApplicationRequest;
import io.ktor.server.routing.RoutingApplicationRequest;

// Waiting for https://youtrack.jetbrains.com/issue/KTOR-430 to get port also
// Tested on ktor-server-cio:2.0.2. May broke on other versions
public class ClientAddressWorkAround {
    public static InetSocketAddress getInetSocketAddress(ApplicationRequest request) {
        try {
            Field privateApplicationRequestField = RoutingApplicationRequest.class.getDeclaredField("$$delegate_0");
            privateApplicationRequestField.setAccessible(true);
            CIOApplicationRequest cioApplicationRequest = (CIOApplicationRequest) privateApplicationRequestField.get(request);

            Field privateRemoteAddressField = CIOApplicationRequest.class.getDeclaredField("remoteAddress");
            privateRemoteAddressField.setAccessible(true);
            return (InetSocketAddress) privateRemoteAddressField.get(cioApplicationRequest);
        } catch (Throwable ignore) {
        }
        return null;
    }
}