package io.ktor.server.cio;

import java.lang.reflect.Field;

import io.ktor.http.cio.CIOHeaders;
import io.ktor.http.cio.HttpHeadersMap;
import io.ktor.http.cio.internals.CharArrayBuilder;

public class CIOHeadersResearch {
    public static String getHeadersAsString(CIOHeaders headers) {
        try {
            Field privateHeadersField = CIOHeaders.class.getDeclaredField("headers");
            privateHeadersField.setAccessible(true);
            HttpHeadersMap headersMap = (HttpHeadersMap) privateHeadersField.get(headers);
            Field privateBuilderField = HttpHeadersMap.class.getDeclaredField("builder");
            privateBuilderField.setAccessible(true);
            CharArrayBuilder charArrayBuilder = (CharArrayBuilder) privateBuilderField.get(headersMap);
            return charArrayBuilder.toString();
        } catch (Throwable ignore) {
        }
        return null;
    }
}
