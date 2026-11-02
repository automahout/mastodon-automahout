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

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link VlmBackend} implementation that calls a locally running
 * <a href="https://ollama.com">Ollama</a> instance via its native
 * {@code /api/chat} endpoint.
 *
 * <p>Requires a vision-capable model to be pulled beforehand, e.g.:
 * <pre>ollama pull llava</pre>
 *
 * <p>Default base URL: {@value #DEFAULT_BASE_URL}</p>
 *
 * <p>No API key is required — Ollama runs entirely on localhost.</p>
 */
public class OllamaBackend implements VlmBackend
{

	private static final Logger log = LoggerFactory.getLogger( OllamaBackend.class );

	public static final String DEFAULT_BASE_URL = "http://localhost:11434";

	public static final String DEFAULT_MODEL = "llava";

	private final String chatEndpoint;

	/**
	 * Creates a backend pointing at the default Ollama URL
	 * ({@value #DEFAULT_BASE_URL}).
	 */
	public OllamaBackend()
	{
		this( DEFAULT_BASE_URL );
	}

	/**
	 * Creates a backend pointing at the specified Ollama base URL.
	 *
	 * @param baseUrl base URL of the Ollama server, e.g.
	 *                {@code http://localhost:11434}
	 */
	public OllamaBackend( final String baseUrl )
	{
		final String base = baseUrl.endsWith( "/" )
				? baseUrl.substring( 0, baseUrl.length() - 1 )
				: baseUrl;
		this.chatEndpoint = base + "/api/chat";
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Sends all images and the text prompt as a single user message to
	 * {@code /api/chat} with {@code "stream": false}.  The {@code images}
	 * field accepts multiple base-64-encoded strings.</p>
	 *
	 * <p>Use a vision-capable model tag (e.g. {@code llava}, {@code llava:13b},
	 * {@code bakllava}, {@code moondream}).</p>
	 */
	@Override
	public String analyze( final String model, final String prompt,
			final List< String > base64Images )
			throws IOException
	{
		// Build images JSON array
		final JSONArray imagesArr = new JSONArray();
		for ( final String b64 : base64Images )
			imagesArr.put( b64 );

		// Single user message with all images
		final JSONObject message = new JSONObject();
		message.put( "role", "user" );
		message.put( "content", prompt );
		message.put( "images", imagesArr );

		final JSONObject body = new JSONObject();
		body.put( "model", model );
		body.put( "stream", false );
		body.put( "messages", new JSONArray().put( message ) );

		final byte[] bodyBytes = body.toString().getBytes( "UTF-8" );

		final HttpURLConnection conn =
				( HttpURLConnection ) new URL( chatEndpoint ).openConnection();
		conn.setRequestMethod( "POST" );
		conn.setRequestProperty( "content-type", "application/json; charset=UTF-8" );
		conn.setConnectTimeout( 10_000 );
		conn.setReadTimeout( 600_000 ); // local models can be slow on first inference
		conn.setDoOutput( true );

		try ( final OutputStream os = conn.getOutputStream() )
		{
			os.write( bodyBytes );
		}

		final int status = conn.getResponseCode();
		final String responseBody = AnthropicBackend.readStream( status == 200
				? conn.getInputStream()
				: conn.getErrorStream() );

		if ( status != 200 )
		{
			final String msg = "Ollama returned HTTP " + status + ": " + responseBody;
			log.error( msg );
			throw new IOException( msg );
		}

		// Response: {"model":..., "message":{"role":"assistant","content":"..."}, ...}
		final JSONObject resp = new JSONObject( responseBody );
		return resp.getJSONObject( "message" ).getString( "content" );
	}

	@Override
	public String chat( final String model, final List< LlmMessage > messages )
			throws IOException
	{
		final JSONArray messagesArr = new JSONArray();
		for ( final LlmMessage msg : messages )
		{
			final JSONObject m = new JSONObject();
			m.put( "role", msg.role );
			m.put( "content", msg.content );
			m.put( "images", new JSONArray() ); // empty — text only
			messagesArr.put( m );
		}

		final JSONObject body = new JSONObject();
		body.put( "model", model );
		body.put( "stream", false );
		body.put( "messages", messagesArr );

		final byte[] bodyBytes = body.toString().getBytes( "UTF-8" );

		final HttpURLConnection conn =
				( HttpURLConnection ) new URL( chatEndpoint ).openConnection();
		conn.setRequestMethod( "POST" );
		conn.setRequestProperty( "content-type", "application/json; charset=UTF-8" );
		conn.setConnectTimeout( 10_000 );
		conn.setReadTimeout( 600_000 );
		conn.setDoOutput( true );

		try ( final OutputStream os = conn.getOutputStream() )
		{
			os.write( bodyBytes );
		}

		final int status = conn.getResponseCode();
		final String responseBody = AnthropicBackend.readStream( status == 200
				? conn.getInputStream()
				: conn.getErrorStream() );

		if ( status != 200 )
		{
			final String msg = "Ollama returned HTTP " + status + ": " + responseBody;
			log.error( msg );
			throw new IOException( msg );
		}

		final JSONObject resp = new JSONObject( responseBody );
		return resp.getJSONObject( "message" ).getString( "content" );
	}
}
