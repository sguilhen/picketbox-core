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
package org.picketbox.core.authentication.http.impl;

import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.picketbox.core.authentication.AuthenticationManager;
import org.picketbox.core.authentication.PicketBoxConstants;
import org.picketbox.core.authentication.http.HTTPAuthenticationScheme;
import org.picketbox.core.authentication.http.HTTPAuthenticationSchemeLoader;
import org.picketbox.core.authentication.http.HTTPClientCertAuthentication;

/**
 * A {@link HTTPAuthenticationSchemeLoader} that can load {@link HTTPClientCertAuthentication}
 *
 * @author anil saldhana
 * @since Jul 10, 2012
 */
public class HTTPClientCertAuthenticationSchemeLoader implements HTTPAuthenticationSchemeLoader {

    @Override
    public HTTPAuthenticationScheme get(Map<String, Object> contextData) throws ServletException {
        HTTPClientCertAuthentication ba = new HTTPClientCertAuthentication();
        ServletContext sc = (ServletContext) contextData.get(PicketBoxConstants.SERVLET_CONTEXT);
        ba.setServletContext(sc);
        ba.setAuthManager((AuthenticationManager) contextData.get(PicketBoxConstants.AUTH_MGR));
        return ba;
    }
}