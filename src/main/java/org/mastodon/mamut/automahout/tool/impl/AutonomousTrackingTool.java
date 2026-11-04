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
package org.mastodon.mamut.automahout.tool.impl;

import org.mastodon.mamut.automahout.tool.ToolRole;
import org.scijava.plugin.Plugin;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import org.mastodon.mamut.automahout.agent.LlmOrchestrator;
import org.mastodon.mamut.automahout.plugin.AutomahoutSettings;
import org.mastodon.mamut.automahout.tool.AutomahoutTool;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.automahout.tool.ToolRegistry;
import org.mastodon.mamut.automahout.vision.AnthropicBackend;
import org.mastodon.mamut.automahout.vision.OllamaBackend;
import org.mastodon.mamut.automahout.vision.VlmBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Autonomous cell tracking tool driven by an LLM agent.
 *
 * <p>Execution flow:</p>
 * <ol>
 *   <li>If a video analysis report is not yet in {@link ToolContext#getSharedData
 *       shared data} under key {@value AnalyzeVideoTool#SHARED_DATA_KEY}, runs
 *       {@link AnalyzeVideoTool} first to produce one.</li>
 *   <li>Launches an {@link LlmOrchestrator} that reads the report and drives
 *       ELEPHANT detection/linking + autocorrection in a conversation loop.</li>
 * </ol>
 *
 * <h3>Parameters</h3>
 * <table border="1">
 *   <tr><th>Key</th><th>Type</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>{@value #PARAM_MAX_ITERATIONS}</td><td>int</td>
 *       <td>{@link LlmOrchestrator#DEFAULT_MAX_ITERATIONS}</td>
 *       <td>Maximum LLM conversation turns</td></tr>
 * </table>
 */
@Plugin( type = AutomahoutTool.class )
public class AutonomousTrackingTool implements AutomahoutTool
{
	@Override
	public ToolRole getRole()
	{
		return ToolRole.PIPELINE;
	}


	private static final Logger log = LoggerFactory.getLogger( AutonomousTrackingTool.class );

	public static final String ID = "tracking.auto";

	public static final String PARAM_MAX_ITERATIONS = "max_iterations";

	/**
	 * Optional free-text guidance from the user (e.g. dataset-specific cautions such as "this dataset's
	 * detection is unstable under self-training — avoid refine_pass"), gathered via a pre-flight review
	 * dialog before the agent starts. Prepended to the video description so the agent sees it from
	 * turn 1, rather than only being discoverable reactively through {@code ask_user}.
	 */
	public static final String PARAM_USER_GUIDANCE = "user_guidance";

	/** Thrown by {@link #buildVlmBackend} when the configured backend cannot be constructed. */
	public static class BackendConfigException extends Exception
	{
		private static final long serialVersionUID = 1L;

		public BackendConfigException( final String message )
		{
			super( message );
		}
	}

	/**
	 * Builds the VLM/LLM backend configured in Automahout settings. Shared with
	 * {@link org.mastodon.mamut.automahout.plugin.AutonomousTrackingAction}'s pre-flight guidance-
	 * suggestion step so both use the identical backend the tracking agent itself will use.
	 */
	public static VlmBackend buildVlmBackend( final AutomahoutSettings settings ) throws BackendConfigException
	{
		final String backendName = settings.getVlmBackend();
		switch ( backendName )
		{
		case "anthropic":
		{
			String apiKey = settings.getVlmApiKey();
			if ( apiKey == null || apiKey.trim().isEmpty() )
				apiKey = System.getenv( "ANTHROPIC_API_KEY" );
			if ( apiKey == null || apiKey.trim().isEmpty() )
				throw new BackendConfigException( "Anthropic API key not configured. "
						+ "Set it in 'Automahout Settings...' (VLM / LLM tab) "
						+ "or set the ANTHROPIC_API_KEY environment variable." );
			return new AnthropicBackend( apiKey );
		}
		case "bedrock":
			return new org.mastodon.mamut.automahout.vision.BedrockBackend( settings.getVlmAwsRegion() );
		case "ollama":
		default:
			return new OllamaBackend( settings.getVlmBaseUrl() );
		}
	}

	@Override
	public String getId()
	{
		return ID;
	}

	@Override
	public String getDescription()
	{
		return "Autonomous cell tracking orchestrator. "
				+ "First runs video.analyze (if not already done) to understand the dataset, "
				+ "then uses an LLM agent to drive ELEPHANT detection/linking and "
				+ "autocorrection with dataset-appropriate parameters. "
				+ "Requires ELEPHANT server and a configured VLM/LLM backend. "
				+ "Optional: max_iterations (int, default "
				+ LlmOrchestrator.DEFAULT_MAX_ITERATIONS + ").";
	}

	@Override
	public String execute( final ToolContext context, final Map< String, Object > params )
	{
		// ---- settings ----
		final AutomahoutSettings settings = new AutomahoutSettings();

		final int maxIterations = params.containsKey( PARAM_MAX_ITERATIONS )
				? ( ( Number ) params.get( PARAM_MAX_ITERATIONS ) ).intValue()
				: LlmOrchestrator.DEFAULT_MAX_ITERATIONS;

		// ---- build VLM backend ----
		final VlmBackend backend;
		try
		{
			backend = buildVlmBackend( settings );
		}
		catch ( final BackendConfigException e )
		{
			return "Error: " + e.getMessage();
		}
		final String model = settings.getVlmModel();

		// Share this run's single VLM client with ElephantTool's adaptive_track window loop (per-window
		// false-positive confirmation, see ElephantTool.SHARED_VLM_BACKEND) so it isn't rebuilt every window.
		context.setSharedData( ElephantTool.SHARED_VLM_BACKEND, backend );
		context.setSharedData( ElephantTool.SHARED_VLM_MODEL, model );

		// ---- ensure video description is available ----
		String videoDescription = ( String ) context.getSharedData( AnalyzeVideoTool.SHARED_DATA_KEY );
		if ( videoDescription == null || videoDescription.trim().isEmpty() )
		{
			log.info( "tracking.auto — no video description found; running video.analyze first" );
			videoDescription = new AnalyzeVideoTool().execute( context, Collections.emptyMap() );
			if ( videoDescription != null && videoDescription.startsWith( "Error:" ) )
				return "tracking.auto aborted: video.analyze failed — " + videoDescription;
		}
		else
		{
			log.info( "tracking.auto — using cached video description" );
		}

		// User-provided pre-flight guidance (e.g. dataset-specific cautions gathered via a review dialog
		// before the agent started) takes precedence and is shown first, so the agent weighs it from
		// turn 1 rather than only discovering constraints reactively through ask_user.
		final Object guidanceObj = params.get( PARAM_USER_GUIDANCE );
		final String userGuidance = guidanceObj != null ? String.valueOf( guidanceObj ).trim() : "";
		final String descriptionForAgent = userGuidance.isEmpty()
				? videoDescription
				: "## User-Provided Guidance (follow these instructions carefully; they come from a human "
						+ "who has already looked at this dataset)\n\n" + userGuidance + "\n\n" + videoDescription;

		// ---- run agentic loop ----
		final ToolRegistry registry = ToolRegistry.forContext( context );

		final LlmOrchestrator orchestrator = new LlmOrchestrator(
				context, registry, backend, model, maxIterations );

		try
		{
			return orchestrator.run( descriptionForAgent );
		}
		catch ( final IOException e )
		{
			log.error( "Autonomous tracking failed", e );
			return "Error: autonomous tracking failed — " + e.getMessage();
		}
	}
}
