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
package org.mastodon.mamut.automahout.vision;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link VlmBackend} implementation that calls the Anthropic Messages API.
 *
 * <p>Requires a valid API key (pass via constructor, or set the
 * {@code ANTHROPIC_API_KEY} environment variable and use
 * {@link #fromEnvironment()}).</p>
 *
 * <p>Default endpoint: {@code https://api.anthropic.com/v1/messages}</p>
 */
public class AnthropicBackend implements VlmBackend
{

	private static final Logger log = LoggerFactory.getLogger( AnthropicBackend.class );

	public static final String DEFAULT_MODEL = "claude-opus-4-8";

	private static final String DEFAULT_ENDPOINT = "https://api.anthropic.com/v1/messages";

	private static final String API_VERSION = "2023-06-01";

	private final String apiKey;

	private final String endpoint;

	public AnthropicBackend( final String apiKey )
	{
		this( apiKey, DEFAULT_ENDPOINT );
	}

	public AnthropicBackend( final String apiKey, final String endpoint )
	{
		if ( apiKey == null || apiKey.trim().isEmpty() )
			throw new IllegalArgumentException( "Anthropic API key must not be blank" );
		this.apiKey = apiKey;
		this.endpoint = endpoint;
	}

	/** Creates a backend using {@code ANTHROPIC_API_KEY} from the environment. */
	public static AnthropicBackend fromEnvironment()
	{
		final String key = System.getenv( "ANTHROPIC_API_KEY" );
		if ( key == null || key.trim().isEmpty() )
			throw new IllegalStateException(
					"ANTHROPIC_API_KEY environment variable is not set" );
		return new AnthropicBackend( key );
	}

	@Override
	public String analyze( final String model, final String prompt,
			final List< String > base64Images )
			throws IOException
	{
		// Each image introduced by its own label — see BedrockBackend.labelledImageContent for why the
		// old anonymous-run form cost this project a working fp-negative supply.
		final JSONArray contentArr = BedrockBackend.labelledImageContent( prompt, base64Images );

		final JSONObject body = new JSONObject();
		body.put( "model", model );
		body.put( "max_tokens", 4096 );
		body.put( "messages", new JSONArray().put(
				new JSONObject()
						.put( "role", "user" )
						.put( "content", contentArr ) ) );

		final byte[] bodyBytes = body.toString().getBytes( "UTF-8" );

		final HttpURLConnection conn =
				( HttpURLConnection ) new URL( endpoint ).openConnection();
		conn.setRequestMethod( "POST" );
		conn.setRequestProperty( "x-api-key", apiKey );
		conn.setRequestProperty( "anthropic-version", API_VERSION );
		conn.setRequestProperty( "content-type", "application/json; charset=UTF-8" );
		conn.setConnectTimeout( 30_000 );
		conn.setReadTimeout( 300_000 );
		conn.setDoOutput( true );

		try ( final OutputStream os = conn.getOutputStream() )
		{
			os.write( bodyBytes );
		}

		final int status = conn.getResponseCode();
		final String responseBody = readStream( status == 200
				? conn.getInputStream()
				: conn.getErrorStream() );

		if ( status != 200 )
		{
			final String msg = "Anthropic API returned HTTP " + status + ": " + responseBody;
			log.error( msg );
			throw new IOException( msg );
		}

		final JSONObject resp = new JSONObject( responseBody );
		return resp.getJSONArray( "content" ).getJSONObject( 0 ).getString( "text" );
	}

	@Override
	public String chat( final String model, final List< LlmMessage > messages )
			throws IOException
	{
		final JSONArray messagesArr = new JSONArray();
		for ( final LlmMessage msg : messages )
		{
			messagesArr.put( new JSONObject()
					.put( "role", msg.role )
					.put( "content", msg.content ) );
		}

		final JSONObject body = new JSONObject();
		body.put( "model", model );
		body.put( "max_tokens", 4096 );
		body.put( "messages", messagesArr );

		final byte[] bodyBytes = body.toString().getBytes( "UTF-8" );

		final HttpURLConnection conn =
				( HttpURLConnection ) new URL( endpoint ).openConnection();
		conn.setRequestMethod( "POST" );
		conn.setRequestProperty( "x-api-key", apiKey );
		conn.setRequestProperty( "anthropic-version", API_VERSION );
		conn.setRequestProperty( "content-type", "application/json; charset=UTF-8" );
		conn.setConnectTimeout( 30_000 );
		conn.setReadTimeout( 300_000 );
		conn.setDoOutput( true );

		try ( final OutputStream os = conn.getOutputStream() )
		{
			os.write( bodyBytes );
		}

		final int status = conn.getResponseCode();
		final String responseBody = readStream( status == 200
				? conn.getInputStream()
				: conn.getErrorStream() );

		if ( status != 200 )
		{
			final String msg = "Anthropic API returned HTTP " + status + ": " + responseBody;
			log.error( msg );
			throw new IOException( msg );
		}

		final JSONObject resp = new JSONObject( responseBody );
		return resp.getJSONArray( "content" ).getJSONObject( 0 ).getString( "text" );
	}

	static String readStream( final java.io.InputStream is ) throws IOException
	{
		final StringBuilder sb = new StringBuilder();
		try ( final BufferedReader br =
				new BufferedReader( new InputStreamReader( is, "UTF-8" ) ) )
		{
			String line;
			while ( ( line = br.readLine() ) != null )
				sb.append( line ).append( '\n' );
		}
		return sb.toString();
	}
}
