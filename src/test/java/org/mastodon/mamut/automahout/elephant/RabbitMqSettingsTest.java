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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.elephant.setting.server.ElephantServerSettings;
import org.json.JSONObject;
import org.junit.Test;

/** A demo run takes its server's RabbitMQ connection from the person's ELEPHANT settings, and records no credentials. */
public class RabbitMqSettingsTest
{
	private static final String YAML = "--- !!org.elephant.setting.server.ElephantServerSettings {name: other, rabbitMQHost: other.example,\n"
			+ "  serverURL: 'http://other.example:8080/', useSslProtocol: false, verifySSL: false}\n"
			+ "--- !!org.elephant.setting.server.ElephantServerSettings {name: gpu, rabbitMQHost: gpu.example,\n"
			+ "  rabbitMQPassword: not-a-real-one, rabbitMQPort: 5671, rabbitMQUsername: someone, rabbitMQVirtualHost: /vh0,\n"
			+ "  serverURL: 'http://gpu.example:8080/', useSslProtocol: true, verifySSL: false}\n";

	@Test
	public void theEntryWithTheRunsServerLendsItsConnection() throws Exception
	{
		final Path f = Files.createTempFile( "elephant_server_settings", ".yaml" );
		try
		{
			Files.write( f, YAML.getBytes( StandardCharsets.UTF_8 ) );
			final ElephantServerSettings style = ElephantServerSettings.defaultStyle().copy( "run" );
			final JSONObject used = RabbitMqFromSettings.apply( f, "http://gpu.example:8080", style, s -> {} );
			assertTrue( used.getBoolean( "used" ) );
			assertEquals( "gpu.example", style.getRabbitMQHost() );
			assertEquals( 5671, style.getRabbitMQPort() );
			assertEquals( "/vh0", style.getRabbitMQVirtualHost() );
			assertEquals( "someone", style.getRabbitMQUsername() );
			assertEquals( "not-a-real-one", style.getRabbitMQPassword() );
			assertTrue( style.getUseSslProtocol() );
			assertFalse( "no credentials in run-info", used.toString().contains( "not-a-real-one" ) || used.toString().contains( "someone" ) );
		}
		finally
		{
			Files.delete( f );
		}
	}

	@Test
	public void noEntryForTheServerChangesNothing()
	{
		assertNull( RabbitMqFromSettings.entry( YAML, "http://elsewhere.example:8080/" ) );
	}
}
