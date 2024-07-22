package com.clickhouse.client.api.internal;

import com.clickhouse.client.ClickHouseNode;
import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.ClientException;
import com.clickhouse.client.api.ServerException;
import com.clickhouse.client.config.ClickHouseClientOption;
import com.clickhouse.client.http.ClickHouseHttpProto;
import com.clickhouse.client.http.config.ClickHouseHttpOption;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.EntityTemplate;
import org.apache.hc.core5.io.IOCallback;
import org.apache.hc.core5.net.URIBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class HttpAPIClientHelper {
    private static final Logger LOG = LoggerFactory.getLogger(Client.class);

    private static int ERROR_BODY_BUFFER_SIZE = 1024; // Error messages are usually small

    private CloseableHttpClient httpClient;

    private Map<String, String> chConfiguration;

    private RequestConfig baseRequestConfig;

    public HttpAPIClientHelper(Map<String, String> configuration) {
        this.chConfiguration = configuration;
        this.httpClient = createHttpClient(configuration, null);
        this.baseRequestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(1000, TimeUnit.MILLISECONDS)
                .build();
    }

    public CloseableHttpClient createHttpClient(Map<String, String> chConfig, Map<String, Serializable> requestConfig) {
        final CloseableHttpClient httpclient = HttpClientBuilder.create()

                .build();


        return httpclient;
    }

    /**
     * Reads status line and if error tries to parse response body to get server error message.
     *
     * @param httpResponse - HTTP response
     * @return
     */
    public Exception readError(ClassicHttpResponse httpResponse) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(ERROR_BODY_BUFFER_SIZE)) {
            httpResponse.getEntity().writeTo(out);
            int serverCode = getHeaderInt(httpResponse.getFirstHeader(ClickHouseHttpProto.HEADER_EXCEPTION_CODE), 0);
            return new ServerException(serverCode, out.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ClientException("Failed to read response body", e);
        }
    }

    public ClassicHttpResponse executeRequest(ClickHouseNode server, Map<String, Object> requestConfig,
                                             IOCallback<OutputStream> writeCallback) {
        HttpHost target = new HttpHost(server.getHost(), server.getPort());

        URI uri;
        try {
            URIBuilder uriBuilder = new URIBuilder(server.getBaseUri());
            addQueryParams(uriBuilder, chConfiguration, requestConfig);
            uri = uriBuilder.build();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        HttpPost req = new HttpPost(uri);
        addHeaders(req, chConfiguration, requestConfig);


        RequestConfig httpReqConfig = RequestConfig.copy(baseRequestConfig)
                .build();
        req.setConfig(httpReqConfig);
        req.setEntity(new EntityTemplate(-1, CONTENT_TYPE, null, writeCallback));

        HttpClientContext context = HttpClientContext.create();

        try {
            ClassicHttpResponse httpResponse = httpClient.executeOpen(target, req, context);
            if (httpResponse.getCode() >= 400 && httpResponse.getCode() < 500) {
                try {
                    throw readError(httpResponse);
                } finally {
                    httpResponse.close();
                }
            } else if (httpResponse.getCode() >= 500) {
                httpResponse.close();
                return httpResponse;
            }
            return httpResponse;

        } catch (UnknownHostException e) {
            LOG.warn("Host '{}' unknown", target);
        } catch (ConnectException | NoRouteToHostException e) {
            LOG.warn("Failed to connect to '{}': {}", target, e.getMessage());
        } catch (ServerException e) {
            throw e;
        } catch (Exception e) {
            throw new ClientException("Failed to execute request", e);
        }

        return null;
    }

    private static final ContentType CONTENT_TYPE = ContentType.create(ContentType.TEXT_PLAIN.getMimeType(), "UTF-8");

    private void addHeaders(HttpPost req, Map<String, String> chConfig, Map<String, Object> requestConfig) {
        req.addHeader(HttpHeaders.CONTENT_TYPE, CONTENT_TYPE.getMimeType());

        if (requestConfig != null) {
            if (requestConfig.containsKey(ClickHouseClientOption.FORMAT.getKey())) {
                req.addHeader(ClickHouseHttpProto.HEADER_FORMAT, requestConfig.get(ClickHouseClientOption.FORMAT.getKey()));
            }
        }
    }
    private void addQueryParams(URIBuilder req, Map<String, String> chConfig, Map<String, Object> requestConfig) {
        if (requestConfig != null) {
            if (requestConfig.containsKey(ClickHouseHttpOption.WAIT_END_OF_QUERY.getKey())) {
                req.addParameter(ClickHouseHttpOption.WAIT_END_OF_QUERY.getKey(),
                        requestConfig.get(ClickHouseHttpOption.WAIT_END_OF_QUERY.getKey()).toString());
            }
            if (requestConfig.containsKey(ClickHouseClientOption.QUERY_ID.getKey())) {
                req.addParameter(ClickHouseHttpProto.QPARAM_QUERY_ID, requestConfig.get(ClickHouseClientOption.QUERY_ID.getKey()).toString());
            }
            if (requestConfig.containsKey("statement_params")) {
                Map<String, Object> params = (Map<String, Object>) requestConfig.get("statement_params");
                for (Map.Entry<String, Object> entry : params.entrySet()) {
                    req.addParameter("param_" + entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
        }
    }

    public static int getHeaderInt(Header header, int defaultValue) {
        return getHeaderVal(header, defaultValue, Integer::parseInt);
    }

    public static String getHeaderVal(Header header, String defaultValue) {
        return getHeaderVal(header, defaultValue, Function.identity());
    }

    public static <T> T getHeaderVal(Header header, T defaultValue, Function<String, T> converter) {
        if (header == null) {
            return defaultValue;
        }

        return converter.apply(header.getValue());
    }
}
