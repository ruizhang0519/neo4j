/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.bolt;

import java.net.SocketAddress;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ServerChannel;

import org.neo4j.bolt.logging.BoltMessageLogger;

/**
 * A channel through which Bolt messaging can occur.
 */
public class BoltChannel implements AutoCloseable, BoltConnectionDescriptor
{
    private final String connector;
    private final ChannelHandlerContext channelHandlerContext;
    private final BoltMessageLogger messageLogger;

    public static BoltChannel open( String connector, ChannelHandlerContext channelHandlerContext,
                                    BoltMessageLogger messageLogger )
    {
        return new BoltChannel( connector, channelHandlerContext, messageLogger );
    }

    private BoltChannel( String connector, ChannelHandlerContext channelHandlerContext,
                         BoltMessageLogger messageLogger )
    {
        this.connector = connector;
        this.channelHandlerContext = channelHandlerContext;
        this.messageLogger = messageLogger;
        messageLogger.serverEvent( "OPEN" );
    }

    public ChannelHandlerContext channelHandlerContext()
    {
        return channelHandlerContext;
    }

    public Channel rawChannel()
    {
        return channelHandlerContext.channel();
    }

    public BoltMessageLogger log()
    {
        return messageLogger;
    }

    @Override
    public void close()
    {
        Channel rawChannel = rawChannel();
        if ( rawChannel.isOpen() )
        {
            messageLogger.serverEvent( "CLOSE" );
            rawChannel.close().syncUninterruptibly();
        }
    }

    @Override
    public String id()
    {
        return rawChannel().id().asLongText();
    }

    @Override
    public String connector()
    {
        return connector;
    }
    @Override
    public SocketAddress clientAddress()
    {
        return channelHandlerContext.channel().remoteAddress();
    }

    @Override
    public SocketAddress serverAddress()
    {
        return channelHandlerContext.channel().localAddress();
    }

}
