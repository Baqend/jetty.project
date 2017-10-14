//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.core.server;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.server.handshake.RFC6455OpeningHandshake;

public class ExampleWebSocketHandler extends HandlerWrapper
{
    final static Logger LOG = Log.getLogger(ExampleWebSocketHandler.class);

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        OpeningHandshake openingHandshake = getOpeningHandshakeImpl(baseRequest);
        if (LOG.isDebugEnabled())
            LOG.debug("handle {} openingHandshake={}", baseRequest, openingHandshake);

        if (openingHandshake != null && openingHandshake.upgrade(request, response))
            return;

        super.handle(target, baseRequest, request, response);
    }

    protected OpeningHandshake getOpeningHandshakeImpl(Request baseRequest)
    {
        HttpField version = baseRequest.getHttpFields().getField(HttpHeader.SEC_WEBSOCKET_VERSION);
        if (version != null && version.getIntValue() == RFC6455OpeningHandshake.VERSION)
            return new RFC6455OpeningHandshake();
        return null;
    }
}
