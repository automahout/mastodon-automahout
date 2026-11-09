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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.json.JSONObject;

/**
 * A run's ELEPHANT RabbitMQ connection, taken from the person's own ELEPHANT server settings (the entry for the run's
 * server URL), so the server's progress messages reach the log as the GUI shows them. In memory only; the record it
 * returns names no user and no password. Its own class, so reading it loads nothing else (BenchmarkRunner patches
 * ImageJ when it loads).
 */
public final class RabbitMqFromSettings
{
	private RabbitMqFromSettings()
	{}

	/**
	 * Copies the RabbitMQ connection of the entry in {@code settings} whose server URL is {@code server} (trailing slash
	 * ignored) into {@code style}. Returns what was used, without credentials, for run-info.json.
	 */
	public static JSONObject apply( final java.nio.file.Path settings, final String server,
			final org.elephant.setting.server.ElephantServerSettings style, final java.util.function.Consumer< String > note )
	{
		final JSONObject out = new JSONObject().put( "settings", settings.toString() );
		final java.util.Map< String, Object > e;
		try
		{
			e = entry( new String( Files.readAllBytes( settings ), StandardCharsets.UTF_8 ), server );
		}
		catch ( final java.io.IOException | RuntimeException x )
		{
			note.accept( "ELEPHANT server settings not read (" + x.getMessage() + "): no RabbitMQ progress." );
			return out.put( "used", false ).put( "reason", String.valueOf( x.getMessage() ) );
		}
		if ( e == null )
		{
			note.accept( "No entry for " + server + " in " + settings + ": no RabbitMQ progress." );
			return out.put( "used", false ).put( "reason", "no entry for the server URL" );
		}
		if ( e.get( "rabbitMQHost" ) != null )
			style.setRabbitMQHost( String.valueOf( e.get( "rabbitMQHost" ) ) );
		if ( e.get( "rabbitMQPort" ) instanceof Number )
			style.setRabbitMQPort( ( ( Number ) e.get( "rabbitMQPort" ) ).intValue() );
		if ( e.get( "rabbitMQVirtualHost" ) != null )
			style.setRabbitMQVirtualHost( String.valueOf( e.get( "rabbitMQVirtualHost" ) ) );
		if ( e.get( "rabbitMQUsername" ) != null )
			style.setRabbitMQUsername( String.valueOf( e.get( "rabbitMQUsername" ) ) );
		if ( e.get( "rabbitMQPassword" ) != null )
			style.setRabbitMQPassword( String.valueOf( e.get( "rabbitMQPassword" ) ) );
		if ( e.get( "useSslProtocol" ) instanceof Boolean )
			style.setUseSslProtocol( ( Boolean ) e.get( "useSslProtocol" ) );
		if ( e.get( "verifySSL" ) instanceof Boolean )
			style.setVerifySSL( ( Boolean ) e.get( "verifySSL" ) );
		return out.put( "used", true ).put( "entry", String.valueOf( e.get( "name" ) ) ).put( "host", style.getRabbitMQHost() )
				.put( "port", style.getRabbitMQPort() ).put( "virtual_host", style.getRabbitMQVirtualHost() )
				.put( "ssl", style.getUseSslProtocol() ).put( "credentials", "from the settings, not recorded" );
	}

	/** The entry of ELEPHANT's server settings YAML whose serverURL is {@code server}, as a map; null if none. */
	@SuppressWarnings( "unchecked" )
	static java.util.Map< String, Object > entry( final String yaml, final String server )
	{
		// The documents carry ELEPHANT's class tag; read as plain maps, nothing is instantiated.
		final String plain = yaml.replace( "!!org.elephant.setting.server.ElephantServerSettings", "" );
		final String want = server.trim().replaceAll( "/+$", "" );
		for ( final Object doc : new org.yaml.snakeyaml.Yaml( new org.yaml.snakeyaml.constructor.SafeConstructor(
				new org.yaml.snakeyaml.LoaderOptions() ) ).loadAll( plain ) )
			if ( doc instanceof java.util.Map
					&& want.equals( String.valueOf( ( ( java.util.Map< String, Object > ) doc ).get( "serverURL" ) ).trim().replaceAll( "/+$", "" ) ) )
				return ( java.util.Map< String, Object > ) doc;
		return null;
	}
}
