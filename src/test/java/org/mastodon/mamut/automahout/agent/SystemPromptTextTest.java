package org.mastodon.mamut.automahout.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The system prompt is built with {@code StringBuilder.append}, not {@code String.format}, so a
 * {@code %%} written there reached the agent as "70%%" instead of "70%" (13 places until 2026-11-11).
 */
class SystemPromptTextTest
{
	@Test
	void percentSignsReachTheAgentAsWritten()
	{
		final String prompt = new LlmOrchestrator( null, null, null, null, 1 )
				.buildSystemPrompt( "description", "schemas", "state" );
		assertFalse( prompt.contains( "%%" ), "a format escape reached the agent" );
		assertTrue( prompt.contains( "continuity>=70%," ) );
		assertTrue( prompt.contains( "Track continuity is GOOD (>=70%)" ) );
	}
}
