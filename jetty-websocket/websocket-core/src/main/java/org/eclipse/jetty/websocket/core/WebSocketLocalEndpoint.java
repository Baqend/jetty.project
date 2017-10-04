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

package org.eclipse.jetty.websocket.core;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Logger;

/**
 * The interface a WSConnection has to the local WebSocket Endpoint.
 */
public interface WebSocketLocalEndpoint
{
    Logger getLog(); // TODO why?
    boolean isOpen(); // TODO Does the endpoint really know this?

    default void onOpen() {}

    default void onClose(CloseStatus close) {}

    default void onFrame(Frame frame) {}

    default void onError(Throwable cause) {}

    default void onText(Frame frame, Callback callback) {}

    default void onBinary(Frame frame, Callback callback) {}

    default void onContinuation(Frame frame, Callback callback) {}

    default void onPing(ByteBuffer payload) {}

    default void onPong(ByteBuffer payload) {}
}
