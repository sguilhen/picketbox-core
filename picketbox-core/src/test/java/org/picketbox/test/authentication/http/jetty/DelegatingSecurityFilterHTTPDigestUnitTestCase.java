/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.picketbox.test.authentication.http.jetty;

import static org.junit.Assert.assertEquals;

import java.net.URL;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.auth.DigestScheme;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.junit.Test;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.FilterHolder;
import org.mortbay.jetty.webapp.WebAppContext;
import org.picketbox.core.authentication.DigestHolder;
import org.picketbox.core.authentication.PicketBoxConstants;
import org.picketbox.core.authentication.http.HTTPBasicAuthentication;
import org.picketbox.core.authentication.http.impl.HTTPDigestAuthenticationSchemeLoader;
import org.picketbox.core.authentication.impl.SimpleCredentialAuthenticationManager;
import org.picketbox.core.http.filters.DelegatingSecurityFilter;
import org.picketbox.core.util.HTTPDigestUtil;
import org.picketbox.test.http.jetty.EmbeddedWebServerBase;

/**
 * Unit test the {@link DelegatingSecurityFilter} for {@link HTTPBasicAuthentication}
 *
 * @author anil saldhana
 * @since Jul 10, 2012
 */
public class DelegatingSecurityFilterHTTPDigestUnitTestCase extends EmbeddedWebServerBase {

    String urlStr = "http://localhost:11080/auth/";

    @Override
    protected void establishUserApps() {
        ClassLoader tcl = Thread.currentThread().getContextClassLoader();
        if (tcl == null) {
            tcl = getClass().getClassLoader();
        }

        final String WEBAPPDIR = "auth/webapp";

        final String CONTEXTPATH = "/auth";

        // for localhost:port/admin/index.html and whatever else is in the webapp directory
        final URL warUrl = tcl.getResource(WEBAPPDIR);
        final String warUrlString = warUrl.toExternalForm();

        Context context = new WebAppContext(warUrlString, CONTEXTPATH);
        server.setHandler(context);

        Thread.currentThread().setContextClassLoader(context.getClassLoader());

        System.setProperty(PicketBoxConstants.USERNAME, "Aladdin");
        System.setProperty(PicketBoxConstants.CREDENTIAL, "Open Sesame");

        FilterHolder filterHolder = new FilterHolder(DelegatingSecurityFilter.class);
        filterHolder.setInitParameter(PicketBoxConstants.AUTH_MGR, SimpleCredentialAuthenticationManager.class.getName());
        filterHolder.setInitParameter(PicketBoxConstants.AUTH_SCHEME_LOADER,
                HTTPDigestAuthenticationSchemeLoader.class.getName());
        context.addFilter(filterHolder, "/", 1);
    }

    @Test
    public void testDigestAuth() throws Exception {
        URL url = new URL(urlStr);

        DefaultHttpClient httpclient = null;
        try {
            String user = "Aladdin";
            String pass = "Open Sesame";

            httpclient = new DefaultHttpClient();

            HttpGet httpget = new HttpGet(url.toExternalForm());
            HttpResponse response = httpclient.execute(httpget);
            assertEquals(401, response.getStatusLine().getStatusCode());
            Header[] headers = response.getHeaders(PicketBoxConstants.HTTP_WWW_AUTHENTICATE);

            HttpEntity entity = response.getEntity();
            EntityUtils.consume(entity);

            Header header = headers[0];
            String value = header.getValue();
            value = value.substring(7).trim();

            String[] tokens = HTTPDigestUtil.quoteTokenize(value);
            DigestHolder digestHolder = HTTPDigestUtil.digest(tokens);

            DigestScheme digestAuth = new DigestScheme();
            digestAuth.overrideParamter("algorithm", "MD5");
            digestAuth.overrideParamter("realm", digestHolder.getRealm());
            digestAuth.overrideParamter("nonce", digestHolder.getNonce());
            digestAuth.overrideParamter("qop", "auth");
            digestAuth.overrideParamter("nc", "0001");
            digestAuth.overrideParamter("cnonce", DigestScheme.createCnonce());
            digestAuth.overrideParamter("opaque", digestHolder.getOpaque());

            httpget = new HttpGet(url.toExternalForm());
            Header auth = digestAuth.authenticate(new UsernamePasswordCredentials(user, pass), httpget);
            System.out.println(auth.getName());
            System.out.println(auth.getValue());

            httpget.setHeader(auth);

            System.out.println("executing request" + httpget.getRequestLine());
            response = httpclient.execute(httpget);
            entity = response.getEntity();

            System.out.println("----------------------------------------");
            StatusLine statusLine = response.getStatusLine();
            System.out.println(statusLine);
            if (entity != null) {
                System.out.println("Response content length: " + entity.getContentLength());
            }
            assertEquals(200, statusLine.getStatusCode());
            EntityUtils.consume(entity);
        } finally {
            // When HttpClient instance is no longer needed,
            // shut down the connection manager to ensure
            // immediate deallocation of all system resources
            httpclient.getConnectionManager().shutdown();
        }
    }
}