/*-
 * #%L
 * mastodon-automahout
 * %%
 * Copyright (C) 2025 Ko Sugawara
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package org.mastodon.mamut.automahout.elephant;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.sun.net.httpserver.HttpServer;

/** {@link ElephantIntegration.ServerIdleWatch}: training ended, read off the server's state over HTTP. */
public class ServerIdleWatchTest
{
	/** A server answering {@code GET /state/process} with the given states in turn (the last repeats). */
	private static HttpServer serving( final AtomicInteger asked, final int... states ) throws Exception
	{
		final HttpServer server = HttpServer.create( new InetSocketAddress( "127.0.0.1", 0 ), 0 );
		server.createContext( "/state/process", ex -> {
			final int i = Math.min( asked.getAndIncrement(), states.length - 1 );
			final byte[] body = ( "{\"success\":true,\"state\":" + states[ i ] + "}" ).getBytes( StandardCharsets.UTF_8 );
			ex.getResponseHeaders().add( "Content-Type", "application/json" );
			ex.sendResponseHeaders( 200, body.length );
			try (OutputStream os = ex.getResponseBody())
			{
				os.write( body );
			}
		} );
		server.start();
		return server;
	}

	private static String url( final HttpServer s )
	{
		return "http://127.0.0.1:" + s.getAddress().getPort();
	}

	@Test
	public void idleTwiceInARowAfterTrainingRanIsTheEnd() throws Exception
	{
		final AtomicInteger asked = new AtomicInteger();
		final HttpServer s = serving( asked, 1, 1, 0, 0 );
		try
		{
			final ElephantIntegration.ServerIdleWatch w = new ElephantIntegration.ServerIdleWatch( url( s ), 0, 0, 0 );
			assertFalse( w.idleAgain() ); // running
			assertFalse( w.idleAgain() ); // running
			assertFalse( "one idle answer is not enough", w.idleAgain() );
			assertTrue( w.idleAgain() );
		}
		finally
		{
			s.stop( 0 );
		}
	}

	@Test
	public void nothingIsAskedBeforeTheRequestHadTimeToStart() throws Exception
	{
		final AtomicInteger asked = new AtomicInteger();
		final HttpServer s = serving( asked, 0 );
		try
		{
			final ElephantIntegration.ServerIdleWatch w = new ElephantIntegration.ServerIdleWatch( url( s ),
					System.currentTimeMillis(), 60_000, 0 );
			for ( int i = 0; i < 5; i++ )
				assertFalse( w.idleAgain() );
			assertTrue( asked.get() == 0 );
		}
		finally
		{
			s.stop( 0 );
		}
	}

	@Test
	public void anUnreachableServerIsNotIdle()
	{
		final ElephantIntegration.ServerIdleWatch w = new ElephantIntegration.ServerIdleWatch( "http://127.0.0.1:9", 0, 0, 0 );
		for ( int i = 0; i < 3; i++ )
			assertFalse( w.idleAgain() );
	}
}
