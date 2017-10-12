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

package org.eclipse.jetty.websocket.core.extensions;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Queue;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.Generator;
import org.eclipse.jetty.websocket.core.io.BatchMode;
import org.eclipse.jetty.websocket.core.IncomingFrames;
import org.eclipse.jetty.websocket.core.OutgoingFrames;
import org.eclipse.jetty.websocket.core.Parser;

/**
 * Represents the stack of Extensions.
 */
@ManagedObject("Extension Stack")
public class ExtensionStack extends ContainerLifeCycle implements IncomingFrames, OutgoingFrames
{
    private static final Logger LOG = Log.getLogger(ExtensionStack.class);

    private final Queue<FrameEntry> entries = new ArrayDeque<>();
    private final IteratingCallback flusher = new Flusher();
    private final WebSocketExtensionRegistry factory;
    private List<Extension> extensions;
    private IncomingFrames nextIncoming;
    private OutgoingFrames nextOutgoing;

    public ExtensionStack(WebSocketExtensionRegistry factory)
    {
        this.factory = factory;
    }

    @Override
    protected void doStart() throws Exception
    {
        if (extensions==null)
            throw new IllegalStateException("Extensions not negotiated!");

        super.doStart();

        // Wire up Extensions
        if ((extensions != null) && (extensions.size() > 0))
        {
            ListIterator<Extension> exts = extensions.listIterator();

            // Connect outgoings
            while (exts.hasNext())
            {
                Extension ext = exts.next();
                ext.setNextOutgoingFrames(nextOutgoing);
                nextOutgoing = ext;
                addBean(ext);
            }

            // Connect incomingFrames
            while (exts.hasPrevious())
            {
                Extension ext = exts.previous();
                ext.setNextIncomingFrames(nextIncoming);
                nextIncoming = ext;
            }
        }
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        super.dump(out,indent);

        IncomingFrames websocket = getLastIncoming();
        OutgoingFrames network = getLastOutgoing();

        // TODO use the utility methods for this
        out.append(indent).append(" +- Stack").append(System.lineSeparator());
        out.append(indent).append("     +- Network  : ").append(network.toString()).append(System.lineSeparator());
        for (Extension ext : extensions)
        {
            out.append(indent).append("     +- Extension: ").append(ext.toString()).append(System.lineSeparator());
        }
        out.append(indent).append("     +- Websocket: ").append(websocket.toString()).append(System.lineSeparator());
    }

    @ManagedAttribute(name = "Extension List", readonly = true)
    public List<Extension> getExtensions()
    {
        return extensions;
    }

    private IncomingFrames getLastIncoming()
    {
        IncomingFrames last = nextIncoming;
        boolean done = false;
        while (!done)
        {
            if (last instanceof AbstractExtension)
            {
                last = ((AbstractExtension)last).getNextIncoming();
            }
            else
            {
                done = true;
            }
        }
        return last;
    }

    private OutgoingFrames getLastOutgoing()
    {
        OutgoingFrames last = nextOutgoing;
        boolean done = false;
        while (!done)
        {
            if (last instanceof AbstractExtension)
            {
                last = ((AbstractExtension)last).getNextOutgoing();
            }
            else
            {
                done = true;
            }
        }
        return last;
    }

    /**
     * Get the list of negotiated extensions, each entry being a full "name; params" extension configuration
     * 
     * @return list of negotiated extensions
     */
    public List<ExtensionConfig> getNegotiatedExtensions()
    {
        List<ExtensionConfig> ret = new ArrayList<>();
        if (extensions == null)
        {
            return ret;
        }

        for (Extension ext : extensions)
        {
            if (ext.getName().charAt(0) == '@')
            {
                // special, internal-only extensions, not present on negotiation level
                continue;
            }
            ret.add(ext.getConfig());
        }
        return ret;
    }

    @ManagedAttribute(name = "Next Incoming Frames Handler", readonly = true)
    public IncomingFrames getNextIncoming()
    {
        return nextIncoming;
    }

    @ManagedAttribute(name = "Next Outgoing Frames Handler", readonly = true)
    public OutgoingFrames getNextOutgoing()
    {
        return nextOutgoing;
    }

    public boolean hasNegotiatedExtensions()
    {
        return (this.extensions != null) && (this.extensions.size() > 0);
    }

    @Override
    public void incomingFrame(Frame frame, Callback callback)
    {
        if (!isRunning())
            throw new IllegalStateException(getState());
        nextIncoming.incomingFrame(frame, callback);
    }

    /**
     * Perform the extension negotiation.
     * <p>
     * For the list of negotiated extensions, use {@link #getNegotiatedExtensions()}
     * 
     * @param configs
     *            the configurations being requested
     */
    public void negotiate(DecoratedObjectFactory objectFactory, WebSocketPolicy policy, ByteBufferPool bufferPool, List<ExtensionConfig> configs)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Extension Configs={}",configs);

        this.extensions = new ArrayList<>();

        String rsvClaims[] = new String[3];

        for (ExtensionConfig config : configs)
        {
            Extension ext = factory.newInstance(objectFactory, policy, bufferPool, config);
            if (ext == null)
            {
                // Extension not present on this side
                continue;
            }

            // Check RSV
            if (ext.isRsv1User() && (rsvClaims[0] != null))
            {
                LOG.debug("Not adding extension {}. Extension {} already claimed RSV1",config,rsvClaims[0]);
                continue;
            }
            if (ext.isRsv2User() && (rsvClaims[1] != null))
            {
                LOG.debug("Not adding extension {}. Extension {} already claimed RSV2",config,rsvClaims[1]);
                continue;
            }
            if (ext.isRsv3User() && (rsvClaims[2] != null))
            {
                LOG.debug("Not adding extension {}. Extension {} already claimed RSV3",config,rsvClaims[2]);
                continue;
            }

            // Add Extension
            extensions.add(ext);
            addBean(ext);

            if (LOG.isDebugEnabled())
                LOG.debug("Adding Extension: {}",config);

            // Record RSV Claims
            if (ext.isRsv1User())
            {
                rsvClaims[0] = ext.getName();
            }
            if (ext.isRsv2User())
            {
                rsvClaims[1] = ext.getName();
            }
            if (ext.isRsv3User())
            {
                rsvClaims[2] = ext.getName();
            }
        }
    }

    @Override
    public void outgoingFrame(Frame frame, Callback callback, BatchMode batchMode)
    {
        if (!isRunning())
            throw new IllegalStateException(getState());
        FrameEntry entry = new FrameEntry(frame,callback,batchMode);
        if (LOG.isDebugEnabled())
            LOG.debug("Queuing {}",entry);
        offerEntry(entry);
        flusher.iterate();
    }

    public void setNextIncoming(IncomingFrames nextIncoming)
    {
        this.nextIncoming = nextIncoming;
    }

    public void setNextOutgoing(OutgoingFrames nextOutgoing)
    {
        this.nextOutgoing = nextOutgoing;
    }

    public void setPolicy(WebSocketPolicy policy)
    {
        for (Extension extension : extensions)
        {
            if (extension instanceof AbstractExtension)
            {
                ((AbstractExtension)extension).setPolicy(policy);
            }
        }
    }

    private void offerEntry(FrameEntry entry)
    {
        synchronized (this)
        {
            entries.offer(entry);
        }
    }

    private FrameEntry pollEntry()
    {
        synchronized (this)
        {
            return entries.poll();
        }
    }

    private int getQueueSize()
    {
        synchronized (this)
        {
            return entries.size();
        }
    }

    @Override
    public String toString()
    {
        StringBuilder s = new StringBuilder();
        s.append("ExtensionStack[");
        s.append("queueSize=").append(getQueueSize());
        s.append(",extensions=");
        if (extensions == null)
        {
            s.append("<null>");
        }
        else
        {
            s.append('[');
            boolean delim = false;
            for (Extension ext : extensions)
            {
                if (delim)
                {
                    s.append(',');
                }
                if (ext == null)
                {
                    s.append("<null>");
                }
                else
                {
                    s.append(ext.getName());
                }
                delim = true;
            }
            s.append(']');
        }
        s.append(",incoming=").append((this.nextIncoming == null)?"<null>":this.nextIncoming.getClass().getName());
        s.append(",outgoing=").append((this.nextOutgoing == null)?"<null>":this.nextOutgoing.getClass().getName());
        s.append("]");
        return s.toString();
    }

    private static class FrameEntry
    {
        private final Frame frame;
        private final Callback callback;
        private final BatchMode batchMode;

        private FrameEntry(Frame frame, Callback callback, BatchMode batchMode)
        {
            this.frame = frame;
            this.callback = callback;
            this.batchMode = batchMode;
        }

        @Override
        public String toString()
        {
            return frame.toString();
        }
    }

    private class Flusher extends IteratingCallback implements Callback
    {
        private FrameEntry current;

        @Override
        protected Action process() throws Exception
        {
            current = pollEntry();
            if (current == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Entering IDLE");
                return Action.IDLE;
            }
            if (LOG.isDebugEnabled())
                LOG.debug("Processing {}",current);
            nextOutgoing.outgoingFrame(current.frame,this,current.batchMode);
            return Action.SCHEDULED;
        }

        @Override
        protected void onCompleteSuccess()
        {
            // This IteratingCallback never completes.
        }
        
        @Override
        protected void onCompleteFailure(Throwable x)
        {
            // This IteratingCallback never fails.
            // The callback are those provided by WriteCallback (implemented
            // below) and even in case of writeFailed() we call succeeded().
        }

        @Override
        public void succeeded()
        {
            // Notify first then call succeeded(), otherwise
            // write callbacks may be invoked out of order.
            notifyCallbackSuccess(current.callback);
            super.succeeded();
        }


        @Override
        public void failed(Throwable cause)
        {
            // Notify first, the call succeeded() to drain the queue.
            // We don't want to call failed(x) because that will put
            // this flusher into a final state that cannot be exited,
            // and the failure of a frame may not mean that the whole
            // connection is now invalid.
            notifyCallbackFailure(current.callback,cause);
            super.failed(cause);
        }

        private void notifyCallbackSuccess(Callback callback)
        {
            try
            {
                if (callback != null)
                    callback.succeeded();
            }
            catch (Throwable x)
            {
                LOG.debug("Exception while notifying success of callback " + callback,x);
            }
        }

        private void notifyCallbackFailure(Callback callback, Throwable failure)
        {
            try
            {
                if (callback != null)
                    callback.failed(failure);
            }
            catch (Throwable x)
            {
                LOG.debug("Exception while notifying failure of callback " + callback,x);
            }
        }
    
        @Override
        public String toString()
        {
            return "ExtensionStack$Flusher[" + getState() + "]";
        }
    }
}
