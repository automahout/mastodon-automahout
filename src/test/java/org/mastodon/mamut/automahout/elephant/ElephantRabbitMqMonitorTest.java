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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests for the terminal-notification status parsing.
 *
 * <p>The first case is the one that shipped: ELEPHANT emits the same {@code DONE POST /seg/train}
 * notification whether the request succeeded or crashed, appending the status. Matching the marker
 * alone reported a 500 as a completed training run, and the caller then burned one of its two
 * training iterations re-detecting with an unchanged model.</p>
 */
public class ElephantRabbitMqMonitorTest
{
	private static final String TRAIN = "done post /seg/train";

	@Test
	public void failureStatusIsExtracted()
	{
		assertEquals( 500, ElephantRabbitMqMonitor.statusAfterMarker(
				"DONE POST /seg/train => [500 INTERNAL SERVER ERROR]", TRAIN ) );
	}

	@Test
	public void successStatusIsExtracted()
	{
		assertEquals( 200, ElephantRabbitMqMonitor.statusAfterMarker(
				"DONE POST /seg/train => [200 OK]", TRAIN ) );
	}

	@Test
	public void aBracketedPrefixIsNotMistakenForTheStatus()
	{
		// Only the text after the marker is searched, so a relay-added prefix cannot be parsed as a code.
		assertEquals( 500, ElephantRabbitMqMonitor.statusAfterMarker(
				"[ELEPHANT INFO] DONE POST /seg/train => [500 INTERNAL SERVER ERROR]", TRAIN ) );
	}

	@Test
	public void aMessageWithoutAStatusReportsNone()
	{
		// -1 means "no status stated", which callers must treat as success — servers and endpoints that
		// report no status at all have always been treated that way.
		assertEquals( -1, ElephantRabbitMqMonitor.statusAfterMarker( "DONE POST /seg/train", TRAIN ) );
	}

	@Test
	public void matchingIsCaseInsensitiveOnTheMarker()
	{
		assertEquals( 503, ElephantRabbitMqMonitor.statusAfterMarker(
				"Done Post /Seg/Train => [503 SERVICE UNAVAILABLE]", TRAIN ) );
	}

	@Test
	public void anAbsentMarkerReportsNone()
	{
		assertEquals( -1, ElephantRabbitMqMonitor.statusAfterMarker(
				"DONE POST /seg/predict => [500 INTERNAL SERVER ERROR]", TRAIN ) );
	}

	@Test
	public void nullsAreSafe()
	{
		assertEquals( -1, ElephantRabbitMqMonitor.statusAfterMarker( null, TRAIN ) );
		assertEquals( -1, ElephantRabbitMqMonitor.statusAfterMarker( "DONE POST /seg/train", null ) );
	}
}
