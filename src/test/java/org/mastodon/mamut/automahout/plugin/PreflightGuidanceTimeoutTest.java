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
package org.mastodon.mamut.automahout.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.mastodon.mamut.automahout.vision.LlmMessage;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.automahout.vision.VlmBackend;

/**
 * The pre-flight dialog must open even when the model does not answer.
 *
 * <p>Reported as a freeze: the log window printed the image profile and then nothing, and no dialog
 * appeared. Between them sits a second model call, asking for guidance text and suggested caps. Its
 * failures were already caught and fall back to a blank suggestion — its SLOWNESS was not, and the
 * SDK's own ceiling is eight minutes across retries, which is a reasonable limit for an unattended
 * pipeline step and not for the last thing standing between the user and a dialog.</p>
 */
public class PreflightGuidanceTimeoutTest
{
	/** A backend that blocks until released, standing in for a call that does not return. */
	private static final class Hanging implements VlmBackend
	{
		final CountDownLatch release = new CountDownLatch( 1 );

		@Override
		public String analyze( final String model, final String prompt, final List< String > images )
		{
			return "";
		}

		@Override
		public String chat( final String model, final List< LlmMessage > messages ) throws IOException
		{
			try
			{
				release.await( 30, TimeUnit.SECONDS );
			}
			catch ( final InterruptedException e )
			{
				Thread.currentThread().interrupt();
			}
			return "too late";
		}
	}

	private static ToolContext logging( final List< String > out )
	{
		final ToolContext ctx = new ToolContext( null );
		ctx.setLogCallback( out::add );
		return ctx;
	}

	@Test
	public void aCallThatDoesNotReturnGivesUpAndSaysSo()
	{
		final List< String > logged = new ArrayList<>();
		final Hanging backend = new Hanging();
		final long started = System.currentTimeMillis();
		final String reply = AutonomousTrackingAction.preflightGuidanceReply(
				logging( logged ), backend, "some-model", "prompt", 1 );
		final long elapsed = System.currentTimeMillis() - started;
		backend.release.countDown();

		assertNull( reply, "a call that did not answer must not be waited for" );
		assertTrue( elapsed < 10_000,
				"gave up after " + elapsed + " ms; the budget was 1 s and the SDK's own ceiling is 8 min" );
		assertTrue( logged.stream().anyMatch( m -> m.contains( "Asking" ) ),
				"the wait must be announced, or a slow call reads as a freeze: " + logged );
		assertTrue( logged.stream().anyMatch( m -> m.contains( "did not answer" ) ),
				"giving up must be announced too: " + logged );
	}

	@Test
	public void aCallThatAnswersIsUsed()
	{
		final List< String > logged = new ArrayList<>();
		final VlmBackend prompt = new VlmBackend()
		{
			@Override
			public String analyze( final String m, final String p, final List< String > i )
			{
				return "";
			}

			@Override
			public String chat( final String m, final List< LlmMessage > messages )
			{
				return "PATCH_SIZE: 368,352,1";
			}
		};
		assertEquals( "PATCH_SIZE: 368,352,1", AutonomousTrackingAction.preflightGuidanceReply(
				logging( logged ), prompt, "m", "p", 30 ) );
	}

	@Test
	public void aBackendThatThrowsFallsBackRatherThanPropagating()
	{
		final List< String > logged = new ArrayList<>();
		final VlmBackend broken = new VlmBackend()
		{
			@Override
			public String analyze( final String m, final String p, final List< String > i )
			{
				return "";
			}

			@Override
			public String chat( final String m, final List< LlmMessage > messages ) throws IOException
			{
				throw new IOException( "no credentials" );
			}
		};
		assertNull( AutonomousTrackingAction.preflightGuidanceReply(
				logging( logged ), broken, "m", "p", 30 ) );
		assertEquals( 1, Collections.frequency(
				logged.stream().map( x -> x.contains( "Asking" ) ).collect(
						java.util.stream.Collectors.toList() ), Boolean.TRUE ) );
		// The reason has to reach the LOG WINDOW, not only the terminal logger. A failure that is
		// invisible there is indistinguishable from the freeze this whole change is about.
		assertTrue( logged.stream().anyMatch(
				m -> m.contains( "IOException" ) && m.contains( "no credentials" ) ),
				"the cause must be surfaced to the user: " + logged );
	}
}
