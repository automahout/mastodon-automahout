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
import java.time.Duration;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

/**
 * {@link VlmBackend} that talks to Claude models hosted on <b>Amazon Bedrock</b>.
 *
 * <p>Unlike {@link AnthropicBackend} (direct Anthropic API, {@code x-api-key} auth), Bedrock uses a
 * region-specific endpoint and a body that carries {@code "anthropic_version": "bedrock-2023-05-31"}
 * instead of a {@code "model"} field (the model is given as the request's {@code modelId}).</p>
 *
 * <p>Authentication is resolved entirely by the AWS SDK's defaults, so this plugin stores no keys and
 * supports both schemes automatically:</p>
 * <ul>
 *   <li><b>Bedrock API key (bearer token)</b> — set {@code AWS_BEARER_TOKEN_BEDROCK}; the SDK uses it
 *       as a bearer token (simplest, similar to a direct API key).</li>
 *   <li><b>AWS IAM credentials (SigV4)</b> — otherwise the default credentials chain applies
 *       (environment variables, {@code ~/.aws/credentials} profile, container / instance role, …).</li>
 * </ul>
 *
 * <p>The {@code model} argument to {@link #chat}/{@link #analyze} is the Bedrock model id or
 * inference-profile id, e.g. {@code anthropic.claude-3-5-sonnet-20241022-v2:0} or a region-prefixed
 * profile such as {@code us.anthropic.claude-opus-4-...-v1:0}.</p>
 */
public class BedrockBackend implements VlmBackend
{

	private static final Logger log = LoggerFactory.getLogger( BedrockBackend.class );

	/** Bedrock's fixed anthropic_version token for the Messages payload. */
	private static final String BEDROCK_ANTHROPIC_VERSION = "bedrock-2023-05-31";

	private static final int MAX_TOKENS = 4096;

	/** Per-attempt socket read timeout — large multimodal requests (many images) can be slow. */
	private static final Duration READ_TIMEOUT = Duration.ofMinutes( 5 );

	/** Overall cap across retries so a genuine hang cannot block the pipeline indefinitely. */
	private static final Duration API_CALL_TIMEOUT = Duration.ofMinutes( 8 );

	private final BedrockRuntimeClient client;

	/**
	 * @param region AWS region hosting the model (e.g. {@code ap-northeast-1}). Auth is resolved by
	 *               the SDK: {@code AWS_BEARER_TOKEN_BEDROCK} (bearer) if set, else the default
	 *               credentials chain (SigV4). No credentials provider is forced, so both work.
	 */
	public BedrockBackend( final String region )
	{
		if ( region == null || region.trim().isEmpty() )
			throw new IllegalArgumentException( "AWS region must not be empty" );
		this.client = BedrockRuntimeClient.builder()
				.region( Region.of( region.trim() ) )
				.httpClientBuilder( UrlConnectionHttpClient.builder()
						.connectionTimeout( Duration.ofSeconds( 20 ) )
						.socketTimeout( READ_TIMEOUT ) )
				.overrideConfiguration( ClientOverrideConfiguration.builder()
						.apiCallTimeout( API_CALL_TIMEOUT )
						.build() )
				.build();
	}

	/**
	 * Builds the user content: each image introduced by a short text label, then the prompt.
	 *
	 * <p>Anthropic's vision guidance for multi-image requests is explicit: "When sending several
	 * images, introduce each one with a short text label ({@code Image 1:}, {@code Image 2:}, and so
	 * on)". This used to send the images as one anonymous run followed by a single text block, on the
	 * strength of the separate single-image tip that images work best before text — which is not the
	 * same advice, and which leaves a model asked for N per-image answers counting an unlabelled
	 * sequence to work out which image it is on.</p>
	 *
	 * <p>Measured cost of the old form, on this project's fp-negative confirmation: two batches of 74
	 * crops, seconds apart in the same window and statistically identical, came back with 0 and 57
	 * background verdicts respectively; across a run, batches of 62-99 returned almost uniformly one
	 * answer while batches of 32 were mixed. The verdicts tracked the batch, not the images.</p>
	 *
	 * <p>A single image keeps the plain image-then-text form the tip actually describes.</p>
	 */
	static JSONArray labelledImageContent( final String prompt, final List< String > base64Images )
	{
		final JSONArray contentArr = new JSONArray();
		final boolean label = base64Images.size() > 1;
		for ( int i = 0; i < base64Images.size(); i++ )
		{
			if ( label )
				contentArr.put( new JSONObject().put( "type", "text" )
						.put( "text", "Image " + ( i + 1 ) + ":" ) );
			final JSONObject source = new JSONObject()
					.put( "type", "base64" )
					.put( "media_type", "image/png" )
					.put( "data", base64Images.get( i ) );
			contentArr.put( new JSONObject().put( "type", "image" ).put( "source", source ) );
		}
		contentArr.put( new JSONObject().put( "type", "text" ).put( "text", prompt ) );
		return contentArr;
	}

	@Override
	public String analyze( final String model, final String prompt,
			final List< String > base64Images )
			throws IOException
	{
		final JSONArray contentArr = labelledImageContent( prompt, base64Images );

		final JSONObject body = baseBody().put( "messages", new JSONArray().put(
				new JSONObject().put( "role", "user" ).put( "content", contentArr ) ) );
		return invoke( model, body );
	}

	@Override
	public String chat( final String model, final List< LlmMessage > messages )
			throws IOException
	{
		final JSONArray messagesArr = new JSONArray();
		for ( final LlmMessage msg : messages )
			messagesArr.put( new JSONObject().put( "role", msg.role ).put( "content", msg.content ) );

		final JSONObject body = baseBody().put( "messages", messagesArr );
		return invoke( model, body );
	}

	private static JSONObject baseBody()
	{
		return new JSONObject()
				.put( "anthropic_version", BEDROCK_ANTHROPIC_VERSION )
				.put( "max_tokens", MAX_TOKENS );
	}

	/** Invokes the Bedrock model and returns the assistant's text (content[0].text). */
	private String invoke( final String model, final JSONObject body ) throws IOException
	{
		if ( model == null || model.trim().isEmpty() )
			throw new IOException( "Bedrock model id is empty — set it in VLM/LLM settings "
					+ "(e.g. anthropic.claude-3-5-sonnet-20241022-v2:0)." );
		try
		{
			final InvokeModelResponse response = client.invokeModel( InvokeModelRequest.builder()
					.modelId( model.trim() )
					.contentType( "application/json" )
					.accept( "application/json" )
					.body( SdkBytes.fromUtf8String( body.toString() ) )
					.build() );
			final JSONObject resp = new JSONObject( response.body().asUtf8String() );
			return resp.getJSONArray( "content" ).getJSONObject( 0 ).getString( "text" );
		}
		catch ( final Exception e )
		{
			final String msg = "Bedrock invokeModel failed for '" + model + "': " + e.getMessage();
			log.error( msg );
			throw new IOException( msg, e );
		}
	}
}
