/*
 * SIP Communicator, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package net.java.sip.communicator.service.protocol.media;

import java.io.*;
import java.net.*;

import net.java.sip.communicator.service.configuration.*;
import net.java.sip.communicator.service.neomedia.*;
import net.java.sip.communicator.service.netaddr.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.util.*;

/**
 * <tt>TransportManager</tt>s are responsible for allocating ports, gathering
 * local candidates and managing ICE whenever we are using it.
 *
 * @param <U> the peer extension class like for example <tt>CallPeerSipImpl</tt>
 * or <tt>CallPeerJabberImpl</tt>
 *
 * @author Emil Ivov
 * @author Lyubomir Marinov
 */
public abstract class TransportManager<U extends MediaAwareCallPeer<?, ?, ?>>
{
    /**
     * The <tt>Logger</tt> used by the <tt>TransportManager</tt>
     * class and its instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(TransportManager.class);

    /**
     * The minimum port number that we'd like our RTP sockets to bind upon.
     */
    private static int minMediaPort = 5000;

    /**
     * The maximum port number that we'd like our RTP sockets to bind upon.
     */
    private static int maxMediaPort = 6000;

    /**
     * The port that we should try to bind our next media stream's RTP socket
     * to.
     */
    protected static int nextMediaPortToTry = minMediaPort;

    /**
     * The {@link MediaAwareCallPeer} whose traffic we will be taking care of.
     */
    private U callPeer;

    /**
     * The RTP/RTCP socket couples that this <tt>TransportManager</tt> uses to
     * send and receive media flows through indexed by <tt>MediaType</tt>
     * (ordinal).
     */
    private final StreamConnector[] streamConnectors
        = new StreamConnector[MediaType.values().length];

    /**
     * Creates a new instance of this transport manager, binding it to the
     * specified peer.
     *
     * @param callPeer the {@link MediaAwareCallPeer} whose traffic we will be
     * taking care of.
     */
    protected TransportManager(U callPeer)
    {
        this.callPeer     = callPeer;
    }

    /**
     * Returns the <tt>StreamConnector</tt> instance that this media handler
     * should use for streams of the specified <tt>mediaType</tt>. The method
     * would also create a new <tt>StreamConnector</tt> if no connector has
     * been initialized for this <tt>mediaType</tt> yet or in case one
     * of its underlying sockets has been closed.
     *
     * @param mediaType the <tt>MediaType</tt> that we'd like to create a
     * connector for.
     * @return this media handler's <tt>StreamConnector</tt> for the specified
     * <tt>mediaType</tt>.
     *
     * @throws OperationFailedException in case we failed to initialize our
     * connector.
     */
    public StreamConnector getStreamConnector(MediaType mediaType)
        throws OperationFailedException
    {
        StreamConnector streamConnector = streamConnectors[mediaType.ordinal()];

        if(streamConnector == null || streamConnector.getProtocol() ==
            StreamConnector.Protocol.UDP)
        {
                if(streamConnector == null ||
                    streamConnector.getDataSocket().isClosed() ||
                    (streamConnector.getControlSocket() != null &&
                        streamConnector.getControlSocket().isClosed()))
                {
                    streamConnectors[mediaType.ordinal()]
                                     = streamConnector
                                     = createStreamConnector(mediaType);
                }
        }
        else if(streamConnector != null && streamConnector.getProtocol() ==
            StreamConnector.Protocol.TCP)
        {
            if(streamConnector.getDataTCPSocket().isClosed()
                || (streamConnector.getControlTCPSocket() != null &&
                    streamConnector.getControlTCPSocket().isClosed()))
            {
                streamConnectors[mediaType.ordinal()]
                                 = streamConnector
                                 = createStreamConnector(mediaType);
            }
        }
        return streamConnector;
    }

    /**
     * Closes both the control and the data socket of the specified connector
     * and releases its reference (if it wasn't the case already).
     *
     * @param mediaType the type of the connector we'd like to close.
     */
    public void closeStreamConnector(MediaType mediaType)
    {
        StreamConnector connector
            = streamConnectors[mediaType.ordinal()];

        if(connector != null)
        {
            synchronized(connector)
            {
                DatagramSocket dataSocket = connector.getDataSocket();

                if (dataSocket != null)
                    dataSocket.close();

                DatagramSocket controlSocket = connector.getControlSocket();

                if (controlSocket != null)
                    controlSocket.close();

                try
                {
                    Socket dataTcpSocket = connector.getDataTCPSocket();
                    if(dataTcpSocket != null)
                        dataTcpSocket.close();

                    Socket controlTcpSocket = connector.getDataTCPSocket();
                    if(controlTcpSocket != null)
                        controlTcpSocket.close();
                }
                catch(IOException e)
                {
                    logger.info("Failed to close TCP socket", e);
                }

                streamConnectors[mediaType.ordinal()] = null;
            }
        }
    }

    /**
     * Creates a media <tt>StreamConnector</tt>. The method takes into account
     * the minimum and maximum media port boundaries.
     *
     * @param mediaType the <tt>MediaType</tt> of the stream for which a new
     * <tt>StreamConnector</tt> is to be created
     * @return a new <tt>StreamConnector</tt>.
     *
     * @throws OperationFailedException if we fail binding the the sockets.
     */
    protected StreamConnector createStreamConnector(MediaType mediaType)
        throws OperationFailedException
    {
        NetworkAddressManagerService nam
            = ProtocolMediaActivator.getNetworkAddressManagerService();

        InetAddress intendedDestination = getIntendedDestination(getCallPeer());

        InetAddress localHostForPeer = nam.getLocalHost(intendedDestination);

        //make sure our port numbers reflect the configuration service settings
        initializePortNumbers();

        //create the RTP socket.
        DatagramSocket rtpSocket = null;
        try
        {
            rtpSocket = nam.createDatagramSocket( localHostForPeer,
                            nextMediaPortToTry, minMediaPort, maxMediaPort);
        }
        catch (Exception exc)
        {
            throw new OperationFailedException(
                "Failed to allocate the network ports necessary for the call.",
                OperationFailedException.INTERNAL_ERROR, exc);
        }

        //make sure that next time we don't try to bind on occupied ports
        nextMediaPortToTry = rtpSocket.getLocalPort() + 1;

        //create the RTCP socket, preferably on the port following our RTP one.
        DatagramSocket rtcpSocket = null;
        try
        {
            rtcpSocket = nam.createDatagramSocket(localHostForPeer,
                            nextMediaPortToTry, minMediaPort, maxMediaPort);
        }
        catch (Exception exc)
        {
           throw new OperationFailedException(
                "Failed to allocate the network ports necessary for the call.",
                OperationFailedException.INTERNAL_ERROR,
                exc);
        }

        //make sure that next time we don't try to bind on occupied ports
        nextMediaPortToTry = rtcpSocket.getLocalPort() + 1;

        if (nextMediaPortToTry > maxMediaPort -1)// take RTCP into account.
            nextMediaPortToTry = minMediaPort;

        return new DefaultStreamConnector(rtpSocket, rtcpSocket);
    }

    /**
     * (Re)Sets the <tt>minPortNumber</tt> and <tt>maxPortNumber</tt> to their
     * defaults or to the values specified in the <tt>ConfigurationService</tt>.
     */
    protected void initializePortNumbers()
    {
        //first reset to default values
        minMediaPort = 5000;
        maxMediaPort = 6000;

        //then set to anything the user might have specified.
        ConfigurationService configuration
            = ProtocolMediaActivator.getConfigurationService();
        String minPortNumberStr
            = configuration.getString(
                    OperationSetBasicTelephony
                        .MIN_MEDIA_PORT_NUMBER_PROPERTY_NAME);

        if (minPortNumberStr != null)
        {
            try
            {
                minMediaPort = Integer.parseInt(minPortNumberStr);
            }
            catch (NumberFormatException ex)
            {
                logger.warn(minPortNumberStr
                            + " is not a valid min port number value. "
                            + "using min port " + minMediaPort);
            }
        }

        String maxPortNumberStr
            = configuration.getString(
                    OperationSetBasicTelephony
                        .MAX_MEDIA_PORT_NUMBER_PROPERTY_NAME);

        if (maxPortNumberStr != null)
        {
            try
            {
                maxMediaPort = Integer.parseInt(maxPortNumberStr);
            }
            catch (NumberFormatException ex)
            {
                logger.warn(maxPortNumberStr
                            + " is not a valid max port number value. "
                            +"using max port " + maxMediaPort,
                            ex);
            }
        }
    }

    /**
     * Returns the <tt>InetAddress</tt> that we are using in one of our
     * <tt>StreamConnector</tt>s or, in case we don't have any connectors yet
     * the address returned by the our network address manager as the best local
     * address to use when contacting the <tt>CallPeer</tt> associated with this
     * <tt>MediaHandler</tt>. This method is primarily meant for use with the
     * o= and c= fields of a newly created session description. The point is
     * that we create our <tt>StreamConnector</tt>s when constructing the media
     * descriptions so we already have a specific local address assigned to them
     * at the time we get ready to create the c= and o= fields. It is therefore
     * better to try and return one of these addresses before trying the net
     * address manager again and running the slight risk of getting a different
     * address.
     *
     * @return an <tt>InetAddress</tt> that we use in one of the
     * <tt>StreamConnector</tt>s in this class.
     */
    public InetAddress getLastUsedLocalHost()
    {
        for (MediaType mediaType : MediaType.values())
        {
            StreamConnector streamConnector
                = streamConnectors[mediaType.ordinal()];

            if (streamConnector != null)
                return streamConnector.getDataSocket().getLocalAddress();
        }

        NetworkAddressManagerService nam
            = ProtocolMediaActivator.getNetworkAddressManagerService();
        InetAddress intendedDestination = getIntendedDestination(getCallPeer());

        return nam.getLocalHost(intendedDestination);
    }

    /**
     * Send empty UDP packet to target destination data/control ports
     * in order to open port on NAT or RTP proxy if any.
     *
     * @param target <tt>MediaStreamTarget</tt>
     * @param type the {@link MediaType} of the connector we'd like to send
     * the hole punching packet through.
     */
    public void sendHolePunchPacket(MediaStreamTarget target, MediaType type)
    {
        if (logger.isInfoEnabled())
            logger.info("Try to open port on NAT if any");
        try
        {
            StreamConnector connector = getStreamConnector(type);

            synchronized(connector)
            {
                if(connector.getProtocol() == StreamConnector.Protocol.TCP)
                {
                    return;
                }

                /* data port (RTP) */
                connector.getDataSocket().send(new DatagramPacket(
                        new byte[0], 0, target.getDataAddress().getAddress(),
                        target.getDataAddress().getPort()));

                /* control port (RTCP) */
                connector.getControlSocket().send(new DatagramPacket(
                        new byte[0], 0, target.getControlAddress().getAddress(),
                        target.getControlAddress().getPort()));
            }
        }
        catch(Exception e)
        {
            logger.error("Error cannot send to remote peer", e);
        }
    }

    /**
     * Returns the <tt>InetAddress</tt> that is most likely to be used as a
     * next hop when contacting the specified <tt>destination</tt>. This is
     * an utility method that is used whenever we have to choose one of our
     * local addresses to put in the Via, Contact or (in the case of no
     * registrar accounts) From headers.
     *
     * @param peer the CallPeer that we would contact.
     *
     * @return the <tt>InetAddress</tt> that is most likely to be to be used
     * as a next hop when contacting the specified <tt>destination</tt>.
     *
     * @throws IllegalArgumentException if <tt>destination</tt> is not a valid
     * host/ip/fqdn
     */
    protected abstract InetAddress getIntendedDestination(U peer);

    /**
     * Returns the {@link MediaAwareCallPeer} that this transport manager is
     * serving.
     *
     * @return the {@link MediaAwareCallPeer} that this transport manager is
     * serving.
     */
    public U getCallPeer()
    {
        return callPeer;
    }
}
