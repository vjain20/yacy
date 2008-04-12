// JakartaCommonsHttpClient.java
// (C) 2008 by Daniel Raap; danielr@users.berlios.de
// first published 2.4.2008 on http://yacy.net
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate: 2008-03-14 01:16:04 +0100 (Fr, 14 Mrz 2008) $
// $LastChangedRevision: 4558 $
// $LastChangedBy: orbiter $
//
// LICENSE
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
package de.anomic.http;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.httpclient.ConnectMethod;
import org.apache.commons.httpclient.DefaultHttpMethodRetryHandler;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HostConfiguration;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.URIException;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.HeadMethod;
import org.apache.commons.httpclient.methods.InputStreamRequestEntity;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.multipart.ByteArrayPartSource;
import org.apache.commons.httpclient.methods.multipart.FilePart;
import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity;
import org.apache.commons.httpclient.methods.multipart.Part;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.apache.commons.httpclient.protocol.Protocol;
import org.apache.commons.httpclient.protocol.ProtocolSocketFactory;
import org.apache.commons.httpclient.util.DateUtil;

import de.anomic.kelondro.kelondroBase64Order;
import de.anomic.server.logging.serverLog;
import de.anomic.yacy.yacyVersion;

/**
 * HttpClient implementation which uses Jakarta Commons HttpClient 3.x {@link http://hc.apache.org/httpclient-3.x/}
 * 
 * @author danielr
 * 
 */
public class JakartaCommonsHttpClient {
    /**
     * "the HttpClient instance and connection manager should be shared among all threads for maximum efficiency."
     * (Concurrent execution of HTTP methods, http://hc.apache.org/httpclient-3.x/performance.html)
     */
    private final static MultiThreadedHttpConnectionManager conManager = new MultiThreadedHttpConnectionManager();
    private final static HttpClient apacheHttpClient = new HttpClient(conManager);

    static {
        /**
         * set options for client
         */
        // set user-agent
        final yacyVersion thisversion = yacyVersion.thisVersion();
        apacheHttpClient.getParams().setParameter(
                                                  HttpMethodParams.USER_AGENT,
                                                  "yacy/" + ((thisversion == null) ? "0.0" : thisversion.releaseNr) +
                                                          " (www.yacy.net; " +
                                                          de.anomic.http.HttpClient.getSystemOST() + ") " +
                                                          getCurrentUserAgent().replace(';', ':')); // last ; must be
                                                                                                    // before location
                                                                                                    // (this is parsed)
        // only one retry
        apacheHttpClient.getParams().setParameter(HttpMethodParams.RETRY_HANDLER,
                                                  new DefaultHttpMethodRetryHandler(1, false));

        /**
         * set options for connection manager
         */
        // conManager.getParams().setDefaultMaxConnectionsPerHost(4); // default 2
        conManager.getParams().setMaxTotalConnections(200); // Proxy may need many connections
        conManager.getParams().setConnectionTimeout(60000); // set a default timeout
        conManager.getParams().setDefaultMaxConnectionsPerHost(20); // prevent DoS by mistake
        // TODO should this be configurable?

        // accept self-signed or untrusted certificates
        Protocol.registerProtocol("https", new Protocol("https",
                (ProtocolSocketFactory) new EasySSLProtocolSocketFactory(), 443));

        /**
         * set network timeout properties. see: http://java.sun.com/j2se/1.5.0/docs/guide/net/properties.html These
         * properties specify the default connect and read timeout (resp.) for the protocol handler used by
         * java.net.URLConnection. the java.net.URLConnection is also used by JakartaCommons HttpClient, see
         * http://hc.apache.org/httpclient-3.x/apidocs/org/apache/commons/httpclient/util/HttpURLConnection.html
         */
        // specify the timeout, in milliseconds, to establish the connection to the host.
        // For HTTP connections, it is the timeout when establishing the connection to the HTTP server.
        System.setProperty("sun.net.client.defaultConnectTimeout", "10000");

        // specify the response timeout, in milliseconds, when reading from an input stream
        // after a connection is established with a resource
        System.setProperty("sun.net.client.defaultReadTimeout", "60000");
    }
    
    /**
     * every x milliseconds do a cleanup (close old connections)
     */
    private final static int cleanupIntervall = 60000;
    /**
     * time the last cleanup was started
     */
    private static long lastCleanup = 0;

    private final Map<HttpMethod, InputStream> openStreams = new HashMap<HttpMethod, InputStream>();
    private Header[] headers = new Header[0];
    private httpRemoteProxyConfig proxyConfig = null;
    private boolean followRedirects = true;

    /**
     * constructs a new Client with given parameters
     * 
     * @param timeout in milliseconds
     * @param header
     * @param proxyConfig
     */
    public JakartaCommonsHttpClient(final int timeout, final httpHeader header, final httpRemoteProxyConfig proxyConfig) {
        super();
        setTimeout(timeout);
        setHeader(header);
        setProxy(proxyConfig);
    }

    /*
     * (non-Javadoc)
     * 
     * @see de.anomic.http.HttpClient#setProxy(de.anomic.http.httpRemoteProxyConfig)
     */
    public void setProxy(final httpRemoteProxyConfig proxyConfig) {
        if (proxyConfig != null && proxyConfig.useProxy()) {
            this.proxyConfig = proxyConfig;
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see de.anomic.http.HttpClient#setHeader(de.anomic.http.httpHeader)
     */
    public void setHeader(final httpHeader header) {
        headers = convertHeaders(header);
    }

    /*
     * (non-Javadoc)
     * 
     * @see de.anomic.http.HttpClient#setTimeout(int)
     */
    public void setTimeout(final int timeout) {
        apacheHttpClient.getParams().setIntParameter(HttpMethodParams.SO_TIMEOUT, timeout);
    }

    /**
     * should redirects automatically be followed?
     * 
     * @param follow
     */
    public void setFollowRedirects(final boolean follow) {
        followRedirects = follow;
    }

    /*
     * (non-Javadoc)
     * 
     * @see de.anomic.http.HttpClient#getUserAgent()
     */
    public String getUserAgent() {
        return getCurrentUserAgent();
    }

    /**
     * This method GETs a page from the server.
     * 
     * @param uri The URI to the page which should be GET.
     * @return InputStream of content (body)
     * @throws IOException
     */
    public JakartaCommonsHttpResponse GET(final String uri) throws IOException {
        final HttpMethod get = new GetMethod(uri);
        return execute(get);
    }

    /**
     * This method gets only the header of a page.
     * 
     * @param uri The URI to the page whose header should be get.
     * @param requestHeader Prefilled httpHeader.
     * @return Instance of response with the content.
     * @throws IOException
     */
    public JakartaCommonsHttpResponse HEAD(final String uri) throws IOException {
        assert uri != null : "precondition violated: uri != null";
        final HttpMethod head = new HeadMethod(uri);
        return execute(head);
    }

    /**
     * This method POSTs some data from an InputStream to a page.
     * 
     * This is for compatibility (an InputStream does not need to contain correct HTTP!)
     * 
     * @param uri The URI to the page which the post is sent to.
     * @param ins InputStream with the data to be posted to the server.
     * @return Instance of response with the content.
     * @throws IOException
     */
    public JakartaCommonsHttpResponse POST(final String uri, final InputStream ins) throws IOException {
        assert uri != null : "precondition violated: uri != null";
        assert ins != null : "precondition violated: ins != null";
        final PostMethod post = new PostMethod(uri);
        post.setRequestEntity(new InputStreamRequestEntity(ins));
        return execute(post);
    }

    /**
     * This method sends several files at once via a POST request. Only those files in the Hashtable files are written
     * whose names are contained in args.
     * 
     * @param uri The URI to the page which the post is sent to.
     * @param files HashMap with the names of data as key and the content (currently implemented is File, byte[] and
     *                String) of the files as value.
     * @return Instance of response with the content.
     * @throws IOException
     */
    public JakartaCommonsHttpResponse POST(final String uri, final List<Part> files) throws IOException {
        assert uri != null : "precondition violated: uri != null";
        final PostMethod post = new PostMethod(uri);

        final Part[] parts;
        if (files != null) {
            parts = new Part[files.size()];
            int i = 0;
            for (final Part part : files) {
                parts[i] = part;
                /*
                if (value instanceof File) {
                    final File file = (File) value;
                    if (file.isFile() && file.canRead()) {
                        // read file
                        final ByteArrayOutputStream fileData = new ByteArrayOutputStream();
                        serverFileUtils.copyToStream(new BufferedInputStream(new FileInputStream(file)),
                                                     new BufferedOutputStream(fileData));
                        value = fileData.toByteArray();
                    }
                }
                if (value instanceof byte[]) {
                    // file/binary data
                    parts[i] = new FilePart(key, new ByteArrayPartSource(key, (byte[]) value));
                } else if (value instanceof String) {
                    // simple text value
                    parts[i] = new StringPart(key, (String) value);
                } else {
                    // not supported
                    final String msg = "type of POST-data not supported: " + value.getClass();
                    serverLog.logSevere("HTTPC", msg);
                    throw new IOException("cannot POST data: " + msg);
                    // break; // post nothing is not what is expected by the caller
                }
                */
                i++;
            }
        } else {
            // nothing to POST
            parts = new Part[0];
        }
        post.setRequestEntity(new MultipartRequestEntity(parts, post.getParams()));
        return execute(post);
    }

    /*
     * (non-Javadoc)
     * 
     * @see de.anomic.http.HttpClient#CONNECT(java.lang.String, int, de.anomic.http.httpHeader)
     */
    public JakartaCommonsHttpResponse CONNECT(final String host, final int port) throws IOException {
        final HostConfiguration hostConfig = new HostConfiguration();
        hostConfig.setHost(host, port);
        final HttpMethod connect = new ConnectMethod(hostConfig);
        return execute(connect);
    }

    /*
     * (non-Javadoc)
     * 
     * @see de.anomic.http.HttpClient#closeStream()
     */
    public void closeStream() {
        // close all opened streams (by this instance)
        synchronized (openStreams) {
            for (final HttpMethod method : openStreams.keySet()) {
                try {
                    // close stream
                    openStreams.get(method).close();
                } catch (final IOException e) {
                    serverLog.logSevere("HTTPC", "connection cannot be closed! will unset variable.");
                    e.printStackTrace();
                } finally {
                    // remove from pool
                    openStreams.remove(method);
                }
                // free the connection
                method.releaseConnection();
            }
        }
    }

    /**
     * adds the yacy-header to the method
     * 
     * @param requestHeader
     * @param method
     */
    public void addHeader(final httpHeader requestHeader, final HttpMethod method) {
        assert method != null : "precondition violated: method != null";
        if (requestHeader != null) {
            addHeaders(convertHeaders(requestHeader), method);
        }
    }

    /**
     * adds every Header in the array to the method
     * 
     * @param requestHeaders
     * @param method must not be null
     */
    private static void addHeaders(final Header[] requestHeaders, final HttpMethod method) {
        if (method == null) {
            throw new NullPointerException("method not set");
        }
        if (requestHeaders != null) {
            for (final Header header : requestHeaders) {
                method.addRequestHeader(header);
            }
        }
    }

    /**
     * convert from yacy-header to apache.commons.httpclient.Header
     * 
     * @param requestHeader
     * @return
     */
    private static Header[] convertHeaders(final httpHeader requestHeader) {
        final Header[] headers;
        if (requestHeader == null) {
            headers = new Header[0];
        } else {
            headers = new Header[requestHeader.size()];
            int i = 0;
            for (final String name : requestHeader.keySet()) {
                headers[i] = new Header(name, requestHeader.get(name));
                i++;
            }
        }
        return headers;
    }

    /**
     * executes a method
     * 
     * @param method
     * @return
     * @throws IOException
     * @throws HttpException
     */
    private JakartaCommonsHttpResponse execute(final HttpMethod method) throws IOException, HttpException {
        assert method != null : "precondition violated: method != null";
        method.setFollowRedirects(followRedirects);
        // set header
        for (final Header header : headers) {
            method.setRequestHeader(header);
        }
        
        // set proxy
        final httpRemoteProxyConfig proxyConfig = getProxyConfig(method.getURI().getHost());
        addProxyAuth(method, proxyConfig);
        final HostConfiguration hostConfig = getProxyHostConfig(proxyConfig);
        
        // statistics
        HttpConnectionInfo.addConnection(generateConInfo(method));
        
        // execute (send request)
        if (hostConfig == null) {
            apacheHttpClient.executeMethod(method);
        } else {
            apacheHttpClient.executeMethod(hostConfig, method);
        }
        
        // return response
        return new JakartaCommonsHttpResponse(method);
    }

    /**
     * @param method
     * @return
     */
    private HttpConnectionInfo generateConInfo(final HttpMethod method) {
        int port = 80;
        String host = null;
        String protocol = null;
        try {
            port = method.getURI().getPort();
            host = method.getURI().getHost();
            protocol = method.getURI().getScheme();
        } catch (final URIException e) {
            // should not happen, because method is already executed
        }
        final String query = (method.getQueryString() != null) ? "?" + method.getQueryString() : "";
        return new HttpConnectionInfo(protocol, (port == -1 || port == 80) ? host : host + ":" + port, method.getName() + " " +
                method.getPath() + query, method.hashCode(), System.currentTimeMillis());
    }

    /**
     * if necessary adds a header for proxy-authentication
     * 
     * @param method
     * @param proxyConfig
     */
    private void addProxyAuth(final HttpMethod method, final httpRemoteProxyConfig proxyConfig) {
        if (proxyConfig != null && proxyConfig.useProxy()) {
            final String remoteProxyUser = proxyConfig.getProxyUser();
            if (remoteProxyUser != null && remoteProxyUser.length() > 0) {
                if (remoteProxyUser.contains(":")) {
                    serverLog.logWarning("HTTPC", "Proxy authentication contains invalid characters, trying anyway");
                }
                final String remoteProxyPwd = proxyConfig.getProxyPwd();
                final String credentials = kelondroBase64Order.standardCoder.encodeString(remoteProxyUser.replace(":",
                                                                                                                  "") +
                        ":" + remoteProxyPwd);
                method.setRequestHeader(httpHeader.PROXY_AUTHORIZATION, "Basic " + credentials);
            }
        }
    }

    /**
     * 
     * @param hostname
     * @return
     */
    private httpRemoteProxyConfig getProxyConfig(final String hostname) {
        final httpRemoteProxyConfig proxyConfig;
        if (this.proxyConfig != null) {
            // client specific
            proxyConfig = httpdProxyHandler.getProxyConfig(hostname, this.proxyConfig);
        } else {
            // default settings
            proxyConfig = httpdProxyHandler.getProxyConfig(hostname, 0);
        }
        return proxyConfig;
    }

    /**
     * @param proxyConfig
     * @return current host-config with additional proxy set or null if no proxy should be used
     */
    private HostConfiguration getProxyHostConfig(final httpRemoteProxyConfig proxyConfig) {
        // generate http-configuration
        if (proxyConfig != null && proxyConfig.useProxy()) {
            // new config based on client (default)
            final HostConfiguration hostConfig = new HostConfiguration(apacheHttpClient.getHostConfiguration());
            // add proxy
            hostConfig.setProxy(proxyConfig.getProxyHost(), proxyConfig.getProxyPort());
            return hostConfig;
        } else {
            return null;
        }
    }

    /**
     * Returns the given date in an HTTP-usable format. (according to RFC1123/RFC822)
     * 
     * @param date The Date-Object to be converted.
     * @return String with the date.
     */
    public static String date2String(final Date date) {
        if (date == null) {
            return "";
        }

        return DateUtil.formatDate(date);
    }

    /**
     * close all connections
     */
    public static void closeAllConnections() {
        conManager.closeIdleConnections(1);
        conManager.shutdown();
    }

    /**
     * gets the maximum number of connections allowed
     * 
     * @return
     */
    public static int maxConnections() {
        return conManager.getParams().getMaxTotalConnections();
    }

    /**
     * test
     * 
     * @param args
     */
    public static void main(final String[] args) {
        JakartaCommonsHttpResponse resp = null;
        String url = args[0];
        if (!(url.toUpperCase().startsWith("HTTP://"))) {
            url = "http://" + url;
        }
        try {
            if (args.length > 1 && "post".equals(args[1])) {
                // POST
                final ArrayList<Part> files = new ArrayList<Part>();
                files.add(new FilePart("myfile.txt", new ByteArrayPartSource("myfile.txt", "this is not a file ;)".getBytes())));
                files.add(new FilePart("anotherfile.raw", new ByteArrayPartSource("anotherfile.raw", "this is not a binary file ;)".getBytes())));
                System.out.println("POST " + files.size() + " elements to " + url);
                final JakartaCommonsHttpClient client = new JakartaCommonsHttpClient(1000, null, null);
                resp = client.POST(url, files);
                System.out.println("----- Header: -----");
                System.out.println(new String(resp.getResponseHeader().toString()));
                System.out.println("----- Body:   -----");
                System.out.println(new String(resp.getData()));
            } else if (args.length > 1 && "head".equals(args[1])) {
                // whead
                System.out.println("whead " + url);
                System.out.println("--------------------------------------");
                System.out.println(de.anomic.http.HttpClient.whead(url).toString());
            } else {
                // wget
                System.out.println("wget " + url);
                System.out.println("--------------------------------------");
                System.out.println(new String(de.anomic.http.HttpClient.wget(url)));
            }
        } catch (final IOException e) {
            e.printStackTrace();
        } finally {
            if (resp != null) {
                // release connection
                resp.closeStream();
            }
        }
    }

    /**
     * @return
     */
    public static String getCurrentUserAgent() {
        return (String) apacheHttpClient.getParams().getParameter(HttpMethodParams.USER_AGENT);
    }

    /**
     * number of active connections
     * 
     * @return
     */
    public static int connectionCount() {
        return conManager.getConnectionsInPool();
    }

    /**
     * remove unused connections
     */
    public static void cleanup() {
        // do it only once a while
        final long now = System.currentTimeMillis(); 
        if(now - lastCleanup > cleanupIntervall) {
            lastCleanup = now;
            conManager.closeIdleConnections(120000);
            conManager.deleteClosedConnections();
            HttpConnectionInfo.cleanUp();
        }
    }
}