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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;

/**
 * The run-wide monitor holds the run's only connection: a per-call monitor attaches to it, sees every message for its
 * completion check, and relays nothing itself, so each server message reaches the log once.
 */
public class SharedRabbitMqMonitorTest
{
	@After
	public void unshare()
	{
		ElephantRabbitMqMonitor.closeShared();
	}

	@Test
	public void aPerCallMonitorAttachesAndEachMessageIsLoggedOnce()
	{
		final List< String > logged = new ArrayList<>();
		final ElephantRabbitMqMonitor run = new ElephantRabbitMqMonitor( logged::add, null );
		ElephantRabbitMqMonitor.shareForTest( run );
		final ElephantRabbitMqMonitor call = new ElephantRabbitMqMonitor( logged::add, "DONE POST /seg/train" );
		assertTrue( "attached, no connection needed", call.start( null ) );
		run.deliver( "Train Epoch: 1 [5/5 (100%)]  Loss: 0.25" );
		run.deliver( "DONE POST /seg/train => [200 OK]" );
		assertEquals( 2, logged.size() );
		assertTrue( call.completedThisRequest() );
		call.close();
		run.deliver( "processing 1 / 6" ); // inference, after the call: still relayed by the run-wide monitor
		assertEquals( 3, logged.size() );
	}

	@Test
	public void aDetachedMonitorNoLongerSeesMessages()
	{
		final ElephantRabbitMqMonitor run = new ElephantRabbitMqMonitor( s -> {}, null );
		ElephantRabbitMqMonitor.shareForTest( run );
		final ElephantRabbitMqMonitor call = new ElephantRabbitMqMonitor( s -> {}, "DONE POST /seg/train" );
		call.start( null );
		call.close();
		run.deliver( "Train Epoch: 1" );
		run.deliver( "DONE POST /seg/train => [200 OK]" );
		assertFalse( call.completedThisRequest() );
	}
}
