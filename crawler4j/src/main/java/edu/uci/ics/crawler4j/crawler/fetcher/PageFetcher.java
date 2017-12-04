/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.uci.ics.crawler4j.crawler.fetcher;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Date;

import javax.net.ssl.SSLContext;

import org.apache.http.*;
import org.apache.http.auth.*;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.*;
import org.apache.http.config.*;
import org.apache.http.conn.socket.*;
import org.apache.http.conn.ssl.*;
import org.apache.http.impl.client.*;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.ssl.SSLContexts;
import org.slf4j.*;

import edu.uci.ics.crawler4j.CrawlerConfiguration;
import edu.uci.ics.crawler4j.crawler.exceptions.PageBiggerThanMaxSizeException;
import edu.uci.ics.crawler4j.url.*;

/**
 * @author Yasser Ganjisaffar
 */
public class PageFetcher {
    protected static final Logger logger = LoggerFactory.getLogger(PageFetcher.class);

    protected final CrawlerConfiguration configuration;

    protected final Object mutex = new Object();

    protected PoolingHttpClientConnectionManager connectionManager;

    protected CloseableHttpClient httpClient;

    protected long lastFetchTime = 0;

    protected IdleConnectionMonitorThread connectionMonitorThread = null;

    public PageFetcher(CrawlerConfiguration configuration) {
        super();
        this.configuration = configuration;
        RequestConfig requestConfig = RequestConfig.custom().setExpectContinueEnabled(false)
                .setCookieSpec(configuration.getCookiePolicy()).setRedirectsEnabled(false)
                .setSocketTimeout(configuration.getSocketTimeout()).setConnectTimeout(configuration
                        .getConnectionTimeout()).build();

        RegistryBuilder<ConnectionSocketFactory> connRegistryBuilder = RegistryBuilder.create();
        connRegistryBuilder.register("http", PlainConnectionSocketFactory.INSTANCE);
        if (configuration.isIncludeHttpsPages()) {
            try { // Fixing: https://code.google.com/p/crawler4j/issues/detail?id=174
                  // By always trusting the ssl certificate
                SSLContext sslContext = SSLContexts.custom().loadTrustMaterial(null,
                        new TrustStrategy() {
                            @Override
                            public boolean isTrusted(final X509Certificate[] chain,
                                    String authType) {
                                return true;
                            }
                        }).build();
                SSLConnectionSocketFactory sslsf = new SniSSLConnectionSocketFactory(sslContext,
                        NoopHostnameVerifier.INSTANCE);
                connRegistryBuilder.register("https", sslsf);
            } catch (Exception e) {
                logger.warn("Exception thrown while trying to register https");
                logger.debug("Stacktrace", e);
            }
        }

        Registry<ConnectionSocketFactory> connRegistry = connRegistryBuilder.build();
        connectionManager = new SniPoolingHttpClientConnectionManager(connRegistry, configuration
                .getDnsResolver());
        connectionManager.setMaxTotal(configuration.getMaxTotalConnections());
        connectionManager.setDefaultMaxPerRoute(configuration.getMaxConnectionsPerHost());

        HttpClientBuilder clientBuilder = HttpClientBuilder.create();
        clientBuilder.setDefaultRequestConfig(requestConfig);
        clientBuilder.setConnectionManager(connectionManager);
        clientBuilder.setUserAgent(configuration.getUserAgentString());
        clientBuilder.setDefaultHeaders(configuration.getDefaultHeaders());

        if (configuration.getProxyHost() != null) {
            if (configuration.getProxyUsername() != null) {
                BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(new AuthScope(configuration.getProxyHost(),
                        configuration.getProxyPort()), new UsernamePasswordCredentials(configuration
                                .getProxyUsername(), configuration.getProxyPassword()));
                clientBuilder.setDefaultCredentialsProvider(credentialsProvider);
            }

            HttpHost proxy = new HttpHost(configuration.getProxyHost(), configuration
                    .getProxyPort());
            clientBuilder.setProxy(proxy);
            logger.debug("Working through Proxy: {}", proxy.getHostName());
        }

        httpClient = configuration.getAuthentication().login(clientBuilder);
        if (connectionMonitorThread == null) {
            connectionMonitorThread = new IdleConnectionMonitorThread(connectionManager);
        }
        connectionMonitorThread.start();
    }

    public PageFetchResult fetchPage(WebURL webUrl) throws InterruptedException, IOException,
            PageBiggerThanMaxSizeException {
        // Getting URL, setting headers & content
        PageFetchResult fetchResult = new PageFetchResult();
        String toFetchURL = webUrl.getURL();
        HttpUriRequest request = null;
        try {
            request = newHttpUriRequest(toFetchURL);
            // Applying Politeness delay
            synchronized (mutex) {
                long now = (new Date()).getTime();
                if ((now - lastFetchTime) < configuration.getPolitenessDelay()) {
                    long sleepDelay = (configuration.getPolitenessDelay() - (now - lastFetchTime));
                    logger.debug("Sleeping for politeness delay {}", sleepDelay);
                    Thread.sleep(sleepDelay);
                }
                lastFetchTime = (new Date()).getTime();
            }

            CloseableHttpResponse response = httpClient.execute(request);
            fetchResult.setEntity(response.getEntity());
            fetchResult.setResponseHeaders(response.getAllHeaders());

            // Setting HttpStatus
            int statusCode = response.getStatusLine().getStatusCode();

            // If Redirect ( 3xx )
            if (statusCode == HttpStatus.SC_MOVED_PERMANENTLY
                    || statusCode == HttpStatus.SC_MOVED_TEMPORARILY
                    || statusCode == HttpStatus.SC_MULTIPLE_CHOICES
                    || statusCode == HttpStatus.SC_SEE_OTHER
                    || statusCode == HttpStatus.SC_TEMPORARY_REDIRECT || statusCode == 308) {
                // https://issues.apache.org/jira/browse/HTTPCORE-389

                Header header = response.getFirstHeader("Location");
                if (header != null) {
                    String movedToUrl = URLCanonicalizer.getCanonicalURL(header.getValue(),
                            toFetchURL);
                    fetchResult.setMovedToUrl(movedToUrl);
                }
            } else if (statusCode >= 200 && statusCode <= 299) { // is 2XX, everything looks ok
                fetchResult.setFetchedUrl(toFetchURL);
                String uri = request.getURI().toString();
                if (!uri.equals(toFetchURL)) {
                    if (!URLCanonicalizer.getCanonicalURL(uri).equals(toFetchURL)) {
                        fetchResult.setFetchedUrl(uri);
                    }
                }

                // Checking maximum size
                if (fetchResult.getEntity() != null) {
                    long size = fetchResult.getEntity().getContentLength();
                    if (size == -1) {
                        Header length = response.getLastHeader("Content-Length");
                        if (length == null) {
                            length = response.getLastHeader("Content-length");
                        }
                        if (length != null) {
                            size = Integer.parseInt(length.getValue());
                        }
                    }
                    if (size > configuration.getMaxDownloadSize()) {
                        // fix issue #52 - consume entity
                        response.close();
                        throw new PageBiggerThanMaxSizeException(size);
                    }
                }
            }

            fetchResult.setStatusCode(statusCode);
            return fetchResult;

        } finally { // occurs also with thrown exceptions
            if ((fetchResult.getEntity() == null) && (request != null)) {
                request.abort();
            }
        }
    }

    public synchronized void shutDown() {
        if (connectionMonitorThread != null) {
            connectionManager.shutdown();
            connectionMonitorThread.shutdown();
        }
    }

    /**
     * Creates a new HttpUriRequest for the given url. The default is to create a HttpGet without
     * any further configuration. Subclasses may override this method and provide their own logic.
     *
     * @param url
     *            the url to be fetched
     * @return the HttpUriRequest for the given url
     */
    protected HttpUriRequest newHttpUriRequest(String url) {
        return new HttpGet(url);
    }

}
