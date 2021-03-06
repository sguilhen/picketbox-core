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
package org.picketbox.core.authentication.http;

import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionEvent;

import org.picketbox.core.PicketBoxMessages;
import org.picketbox.core.authentication.DigestHolder;
import org.picketbox.core.authentication.PicketBoxConstants;
import org.picketbox.core.exceptions.AuthenticationException;
import org.picketbox.core.nonce.NonceGenerator;
import org.picketbox.core.nonce.UUIDNonceGenerator;
import org.picketbox.core.util.HTTPDigestUtil;

/**
 * Class that handles HTTP/Digest Authentication
 *
 * @author anil saldhana
 * @since Jul 6, 2012
 */
public class HTTPDigestAuthentication extends AbstractHTTPAuthentication {
    protected String opaque = UUID.randomUUID().toString();

    protected String qop = PicketBoxConstants.HTTP_DIGEST_QOP_AUTH;

    // How long is the nonce valid? By default, it is set at 3 minutes
    protected long nonceMaxValid = 3 * 60 * 1000;

    protected NonceGenerator nonceGenerator = new UUIDNonceGenerator();

    /**
     * A simple lookup map of session id versus the nonces issued
     */
    protected ConcurrentMap<String, List<String>> idVersusNonce = new ConcurrentHashMap<String, List<String>>();

    public NonceGenerator getNonceGenerator() {
        return nonceGenerator;
    }

    public void setNonceGenerator(NonceGenerator nonceGenerator) {
        this.nonceGenerator = nonceGenerator;
    }

    public void setNonceMaxValid(String nonceMaxValidStr) {
        this.nonceMaxValid = Long.parseLong(nonceMaxValidStr);
    }

    public String getOpaque() {
        return opaque;
    }

    public void setOpaque(String opaque) {
        this.opaque = opaque;
    }

    private static enum NONCE_VALIDATION_RESULT {
        INVALID, STALE, VALID
    }

    ;

    /**
     * Authenticate an user
     *
     * @param servletReq
     * @param servletResp
     * @return
     * @throws AuthenticationException
     */
    public Principal authenticate(ServletRequest servletReq, ServletResponse servletResp) throws AuthenticationException {
        HttpServletRequest request = (HttpServletRequest) servletReq;
        HttpServletResponse response = (HttpServletResponse) servletResp;
        HttpSession session = request.getSession(true);
        String sessionId = session.getId();

        // Get the Authorization Header
        String authorizationHeader = request.getHeader(PicketBoxConstants.HTTP_AUTHORIZATION_HEADER);

        if (authorizationHeader != null && authorizationHeader.isEmpty() == false) {

            if (authorizationHeader.startsWith(PicketBoxConstants.HTTP_DIGEST)) {
                authorizationHeader = authorizationHeader.substring(7).trim();
            }
            String[] tokens = HTTPDigestUtil.quoteTokenize(authorizationHeader);

            int len = tokens.length;
            if (len == 0) {
                challengeClient(request, response, false);
                return null;
            }

            DigestHolder digest = HTTPDigestUtil.digest(tokens);

            // Pre-verify the client response
            if (digest.getUsername() == null || digest.getRealm() == null || digest.getNonce() == null
                    || digest.getUri() == null || digest.getClientResponse() == null) {
                challengeClient(request, response, false);
                return null;
            }

            // Validate Opaque
            if (digest.getOpaque() != null && digest.getOpaque().equals(this.opaque) == false) {
                challengeClient(request, response, false);
                return null;
            }

            // Validate realm
            if (digest.getRealm().equals(this.realmName) == false) {
                challengeClient(request, response, false);
                return null;
            }

            // Validate qop
            if (digest.getQop().equals(this.qop) == false) {
                challengeClient(request, response, false);
                return null;
            }

            digest.setRequestMethod(request.getMethod());

            // Validate the nonce
            NONCE_VALIDATION_RESULT nonceResult = validateNonce(digest, sessionId);

            if (nonceResult == NONCE_VALIDATION_RESULT.VALID) {
                if (authManager == null) {
                    throw PicketBoxMessages.MESSAGES.invalidNullAuthenticationManager();
                }

                return authManager.authenticate(digest);
            }
        }

        challengeClient(request, response, false);

        return null;
    }

    private boolean challengeClient(HttpServletRequest request, HttpServletResponse response, boolean isStale)
            throws AuthenticationException {
        HttpSession session = request.getSession();
        String sessionId = session.getId();

        String domain = request.getContextPath();
        if (domain == null)
            domain = "/";

        String newNonce = nonceGenerator.get();

        List<String> storedNonces = idVersusNonce.get(sessionId);
        if (storedNonces == null) {
            storedNonces = new ArrayList<String>();
            idVersusNonce.put(sessionId, storedNonces);
        }
        storedNonces.add(newNonce);

        StringBuilder str = new StringBuilder("Digest realm=\"");
        str.append(realmName).append("\",");
        str.append("domain=\"").append(domain).append("\",");
        str.append("nonce=\"").append(newNonce).append("\",");
        str.append("algorithm=MD5,");
        str.append("qop=").append(this.qop).append(",");
        str.append("opaque=\"").append(this.opaque).append("\",");
        str.append("stale=\"").append(isStale).append("\"");

        response.setHeader(PicketBoxConstants.HTTP_WWW_AUTHENTICATE, str.toString());

        try {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        } catch (IOException e) {
            throw new AuthenticationException(e);
        }
        return false;
    }

    private NONCE_VALIDATION_RESULT validateNonce(DigestHolder digest, String sessionId) {
        String nonce = digest.getNonce();

        List<String> storedNonces = idVersusNonce.get(sessionId);
        if (storedNonces == null) {
            return NONCE_VALIDATION_RESULT.INVALID;
        }
        if (storedNonces.contains(nonce) == false) {
            return NONCE_VALIDATION_RESULT.INVALID;
        }

        boolean hasExpired = nonceGenerator.hasExpired(nonce, nonceMaxValid);
        if (hasExpired)
            return NONCE_VALIDATION_RESULT.STALE;

        return NONCE_VALIDATION_RESULT.VALID;
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent se) {
        HttpSession session = se.getSession();
        String id = session.getId();
        idVersusNonce.remove(id);
    }
}