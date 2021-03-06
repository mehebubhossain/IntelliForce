/*
 * Copyright 2014 Mark Borner
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package au.com.borner.salesforce.client.rest;

import au.com.borner.salesforce.client.rest.domain.*;
import com.intellij.openapi.diagnostic.Logger;
import org.apache.http.*;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.conn.socket.LayeredConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLContexts;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.util.List;

/**
 * The Rest Connection Manager
 *
 * @author mark
 */
public class ConnectionManager {

    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER = "Bearer ";
    private static final String HTTPS = "https";
    private static final int PORT_443 = 443;

    private Logger logger = Logger.getInstance(getClass());
    private final HttpClient httpClient;

    private boolean loggedIn = false;
    private String token;
    private String instanceHost;

    public ConnectionManager() {
        loggedIn = false;
        SSLContext sslContext = SSLContexts.createSystemDefault();
        LayeredConnectionSocketFactory sslSocketFactory = new SSLConnectionSocketFactory(sslContext);

        PoolingHttpClientConnectionManager pcm = new PoolingHttpClientConnectionManager();
        pcm.setMaxTotal(100);
        pcm.setDefaultMaxPerRoute(50);

        HttpCompressionRequestInterceptor requestInterceptor = new HttpCompressionRequestInterceptor();
        HttpCompressionResponseInterceptor responseInterceptor = new HttpCompressionResponseInterceptor();

        httpClient = HttpClients.custom()
                .setConnectionManager(pcm)
                .setSSLSocketFactory(sslSocketFactory)
                .addInterceptorFirst(requestInterceptor)
                .addInterceptorFirst(responseInterceptor)
                .build();
    }

    public void setSessionDetails(@Nullable String token, @Nullable String instanceHost) {
        if (token == null || instanceHost == null) return;
        this.token = token;
        this.instanceHost = instanceHost;
        this.loggedIn = true;
    }

    // HTTP get methods

    public <Response extends AbstractJSONObject> Response executeGet(String path, Class<Response> responseClass) {
        return executeGet(path, null, responseClass);
    }

    public <Response extends AbstractJSONObject> Response executeGet(String path, List<NameValuePair> parameters, Class<Response> responseClass) {
        HttpGet httpGet = new HttpGet(buildUri(path, parameters));
        return execute(httpGet, responseClass);
    }

    // ------------------

    // HTTP post methods

    public <Response extends AbstractJSONObject, Request extends AbstractJSONObject> Response executePost(String path, Request bodyObject, Class<Response> responseClass) {
        HttpPost httpPost = new HttpPost(buildUri(path, null));
        String body = bodyObject.toString();
        httpPost.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        return execute(httpPost, responseClass);
    }

    // ------------------

    // HTTP delete methods (which always return a VoidResponse)

    public VoidResponse executeDelete(String path) {
        HttpDelete httpDelete = new HttpDelete(buildUri(path, null));
        return execute(httpDelete, VoidResponse.class);
    }

    // ------------------

    private URI buildUri(String path, List<NameValuePair> parameters) {
        try {
            if (parameters == null) {
                return new URIBuilder()
                        .setScheme(HTTPS)
                        .setHost(instanceHost)
                        .setPort(PORT_443)
                        .setPath(path)
                        .build();
            } else {
                return new URIBuilder()
                        .setScheme(HTTPS)
                        .setHost(instanceHost)
                        .setPort(PORT_443)
                        .setPath(path)
                        .setParameters(parameters)
                        .build();
            }
        } catch (Exception e) {
            throw new ConnectionException("Invalid URI: " + path, e);
        }
    }

    protected <Response extends AbstractJSONObject> Response execute(HttpRequestBase request, Class<Response> responseClass) {
        if (!loggedIn) {
            throw new ConnectionSessionException("You are not logged into Salesforce");
        }
        try {
            request.setHeader("Accept-Encoding", "gzip");
            return doExecute(request, responseClass);
        } catch (ConnectionSessionException cse) {
            logger.debug("Connection Session Exception thrown", cse);
            loggedIn = false;
            throw  cse;
        }
    }


    private <T extends AbstractJSONObject> T doExecute(HttpRequestBase request, Class<T> responseClass) {
        HttpResponse response;
        try {
            request.setHeader(AUTHORIZATION, BEARER + token);
            response = httpClient.execute(request);
        } catch (Exception e) {
            throw new ConnectionException(String.format("Unable to execute request for %s", request.getURI().toString()), e);
        }

        String responseString = null;
        if (response.getEntity() != null) {
            try {
                responseString = EntityUtils.toString(response.getEntity());
            } catch (IOException e) {
                throw new ConnectionException("Unable to parse response entity!", e);
            }
        }

        checkResponseForError(response.getStatusLine().getStatusCode(), responseString);

        Constructor<T> constructor;
        try {
            constructor = responseClass.getConstructor(String.class);
            return constructor.newInstance(responseString);
        } catch (Exception e) {
            throw new ConnectionException("Unable to instantiate response class", e);
        }
    }

// I'm keeping this block of code here as an example of how to login using REST in case I ever need it
//
//    private static final String LOGIN_PATH = "/services/oauth2/token";
//
//    public void login() {
//        try {
//            URI uri = new URIBuilder()
//                    .setScheme("https")
//                    .setHost(InstanceUtils.getSoapLoginUrlForEnvironment(instanceCredentials.getEnvironment()))
//                    .setPort(443)
//                    .setPath(LOGIN_PATH)
//                    .setParameter("grant_type", "password")
//                    .setParameter("client_id", instanceCredentials.getConsumerKey())
//                    .setParameter("client_secret", instanceCredentials.getConsumerSecret())
//                    .setParameter("username", instanceCredentials.getUsername())
//                    .setParameter("password", instanceCredentials.getPassword() + instanceCredentials.getSecurityToken())
//                    .build();
//
//            HttpPost post = new HttpPost(uri);
//            HttpResponse httpResponse= httpClient.execute(post);
//            String responseString = EntityUtils.toString(httpResponse.getEntity());
//            checkResponseForError(httpResponse.getStatusLine().getStatusCode(), responseString);
//            LoginResponse response = new LoginResponse(responseString);
//            response.verify(instanceCredentials.getConsumerSecret());  // verifies the signature on the login response
//            instanceHost = response.getInstanceHost();
//            token = response.getToken();
//            loggedIn = true;
//
//        } catch (Exception e) {
//            loggedIn = false;
//            throw new ConnectionException("An error occurred while logging into Salesforce", e);
//        }
//    }

    private void checkResponseForError(int statusCode, String responseString)  {
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }

        ErrorsResponse errorsResponse = null;

        if (responseString != null && responseString.length() > 0) {
            try {
                errorsResponse = new ErrorsResponse(responseString);
            } catch (Exception e) {
                logger.warn("We had a problem parsing the error response....", e);
            }
        }

        switch (statusCode) {
            case 300:
                throw new ConnectionException(errorsResponse, statusCode, "The value returned when an external ID exists in more than one record");
            case 304:
                throw new ConnectionException(errorsResponse, statusCode, "The request content has not changed since a specified date and time");
            case 400:
                throw new ConnectionException(errorsResponse, statusCode, "The request couldn't be understood, usually because the JSON or XML body contains an error");
            case 401:
                throw new ConnectionSessionException(errorsResponse, statusCode, "The session ID or OAuth token used has expired or is invalid");
            case 403:
                throw new ConnectionException(errorsResponse, statusCode, "The request has been refused");
            case 404:
                throw new ConnectionException(errorsResponse, statusCode, "The requested resource couldn't be found");
            case 405:
                throw new ConnectionException(errorsResponse, statusCode, "The method specified in the Request-Line isn't allowed for the resource specified in the URI");
            case 415:
                throw new ConnectionException(errorsResponse, statusCode, "The entity in the request is in a format that's not supported by the specified method");
            case 500:
                throw new ConnectionException(errorsResponse, statusCode, "An error has occurred within Force.com, so the request couldn't be completed. Contact salesforce.com Customer Support");
            default:
                throw new ConnectionException(errorsResponse, statusCode, "An unknown error occurred");
        }
    }

}
