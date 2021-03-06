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
package org.picketbox.core.http.filters;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.picketbox.core.PicketBoxConfiguration;
import org.picketbox.core.PicketBoxManager;
import org.picketbox.core.PicketBoxMessages;
import org.picketbox.core.authentication.AuthenticationManager;
import org.picketbox.core.authentication.PicketBoxConstants;
import org.picketbox.core.authentication.http.HTTPAuthenticationScheme;
import org.picketbox.core.authentication.http.HTTPAuthenticationSchemeLoader;
import org.picketbox.core.authentication.http.impl.HTTPBasicAuthenticationSchemeLoader;
import org.picketbox.core.authentication.http.impl.HTTPClientCertAuthenticationSchemeLoader;
import org.picketbox.core.authentication.http.impl.HTTPDigestAuthenticationSchemeLoader;
import org.picketbox.core.authentication.http.impl.HTTPFormAuthenticationSchemeLoader;
import org.picketbox.core.authentication.impl.PropertiesFileBasedAuthenticationManager;
import org.picketbox.core.authentication.impl.SimpleCredentialAuthenticationManager;
import org.picketbox.core.authorization.AuthorizationManager;
import org.picketbox.core.exceptions.AuthenticationException;

/**
 * A {@link Filter} that delegates to the PicketBox Security Infrastructure
 *
 * @author anil saldhana
 * @since Jul 10, 2012
 */
public class DelegatingSecurityFilter implements Filter {
    private PicketBoxManager securityManager;

    private FilterConfig filterConfig;

    @Override
    public void init(FilterConfig fc) throws ServletException {
        this.filterConfig = fc;

        ServletContext sc = filterConfig.getServletContext();

        Map<String, Object> contextData = new HashMap<String, Object>();
        contextData.put(PicketBoxConstants.SERVLET_CONTEXT, sc);

        // Let us try the servlet context
        String authValue = sc.getInitParameter(PicketBoxConstants.AUTHENTICATION_KEY);
        AuthorizationManager authorizationManager = null;
        HTTPAuthenticationScheme authenticationScheme = null;

        if (authValue != null && authValue.isEmpty() == false) {
            // Look for auth mgr also
            String authMgrStr = sc.getInitParameter(PicketBoxConstants.AUTH_MGR);
            // Look for auth mgr also
            String authzMgrStr = sc.getInitParameter(PicketBoxConstants.AUTHZ_MGR);

            if (authzMgrStr != null) {
                authorizationManager = getAuthzMgr(authzMgrStr);
                authorizationManager.start();
                contextData.put(PicketBoxConstants.AUTHZ_MGR, authorizationManager);
            }

            contextData.put(PicketBoxConstants.AUTH_MGR, getAuthMgr(authMgrStr));

            authenticationScheme = getAuthenticationScheme(authValue, contextData);
        } else {
            String loader = filterConfig.getInitParameter(PicketBoxConstants.AUTH_SCHEME_LOADER);
            if (loader == null) {
                throw PicketBoxMessages.MESSAGES.missingRequiredInitParameter(PicketBoxConstants.AUTH_SCHEME_LOADER);
            }
            String authManagerStr = filterConfig.getInitParameter(PicketBoxConstants.AUTH_MGR);
            if (authManagerStr != null && authManagerStr.isEmpty() == false) {
                AuthenticationManager am = getAuthMgr(authManagerStr);
                contextData.put(PicketBoxConstants.AUTH_MGR, am);
            }
            String authzManagerStr = filterConfig.getInitParameter(PicketBoxConstants.AUTHZ_MGR);
            if (authzManagerStr != null && authzManagerStr.isEmpty() == false) {
                authorizationManager = getAuthzMgr(authzManagerStr);
                authorizationManager.start();
                contextData.put(PicketBoxConstants.AUTHZ_MGR, authorizationManager);
            }
            HTTPAuthenticationSchemeLoader authLoader = (HTTPAuthenticationSchemeLoader) SecurityActions.instance(getClass(),
                    loader);
            authenticationScheme = authLoader.get(contextData);
        }

        this.securityManager = new PicketBoxConfiguration().authentication(authenticationScheme)
                .authorization(authorizationManager).buildAndStart();

        sc.setAttribute(PicketBoxConstants.PICKETBOX_MANAGER, this.securityManager);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException,
            ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        logout(httpRequest, httpResponse);

        authenticate(httpRequest, httpResponse);

        authorize(httpRequest, httpResponse);

        if (!response.isCommitted()) {
            chain.doFilter(httpRequest, response);
        }
    }

    private void authorize(HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException {
        boolean authorize = this.securityManager.authorize(httpRequest, httpResponse);

        if (!authorize) {
            if (!httpResponse.isCommitted()) {
                httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN);
            }
        }
    }

    private void authenticate(HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws ServletException {
        if (httpResponse.isCommitted()) {
            return;
        }

        try {
            this.securityManager.authenticate(httpRequest, httpResponse);
        } catch (AuthenticationException e) {
            throw new ServletException(e);
        }
    }

    private void logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws ServletException {
        this.securityManager.logout(httpRequest, httpResponse);
    }

    @Override
    public void destroy() {
        this.filterConfig = null;
        this.securityManager.stop();
    }

    private HTTPAuthenticationScheme getAuthenticationScheme(String value, Map<String, Object> contextData)
            throws ServletException {
        if (value.equals(PicketBoxConstants.BASIC)) {
            return new HTTPBasicAuthenticationSchemeLoader().get(contextData);
        }
        if (value.equals(PicketBoxConstants.DIGEST)) {
            return new HTTPDigestAuthenticationSchemeLoader().get(contextData);
        }
        if (value.equals(PicketBoxConstants.CLIENT_CERT)) {
            return new HTTPClientCertAuthenticationSchemeLoader().get(contextData);
        }

        return new HTTPFormAuthenticationSchemeLoader().get(contextData);
    }

    private AuthenticationManager getAuthMgr(String value) {
        if (value.equalsIgnoreCase("Credential")) {
            return new SimpleCredentialAuthenticationManager();
        }
        if (value.equalsIgnoreCase("Properties")) {
            return new PropertiesFileBasedAuthenticationManager();
        }

        if (value == null || value.isEmpty()) {
            return new PropertiesFileBasedAuthenticationManager();
        }

        return (AuthenticationManager) SecurityActions.instance(getClass(), value);
    }

    private AuthorizationManager getAuthzMgr(String value) {
        if (value.equalsIgnoreCase("Drools")) {
            return (AuthorizationManager) SecurityActions.instance(getClass(),
                    "org.picketbox.drools.authorization.PicketBoxDroolsAuthorizationManager");
        }

        return (AuthorizationManager) SecurityActions.instance(getClass(),
                "org.picketbox.drools.authorization.PicketBoxDroolsAuthorizationManager");
    }
}