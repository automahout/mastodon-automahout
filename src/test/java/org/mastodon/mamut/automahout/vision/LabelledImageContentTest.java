package org.mastodon.mamut.automahout.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * Anthropic's vision guidance for multi-image requests: "When sending several images, introduce each
 * one with a short text label (Image 1:, Image 2:, and so on)". This used to send one anonymous run
 * of images followed by a single text block, on the strength of the separate single-image tip that
 * images work best before text.
 *
 * <p>The cost was measured: two batches of 74 crops, seconds apart in the same window and
 * statistically identical, came back with 0 and 57 background verdicts; across a run, batches of
 * 62-99 returned almost uniformly one answer while batches of 32 were mixed. The verdicts tracked
 * the batch, not the images.</p>
 */
public class LabelledImageContentTest
{
	private static List< String > images( final int n )
	{
		final List< String > out = new ArrayList<>();
		for ( int i = 0; i < n; i++ )
			out.add( "b64-" + i );
		return out;
	}

	private static List< String > types( final JSONArray a )
	{
		final List< String > out = new ArrayList<>();
		for ( int i = 0; i < a.length(); i++ )
			out.add( a.getJSONObject( i ).getString( "type" ) );
		return out;
	}

	@Test
	public void everyImageInAMultiImageRequestIsIntroducedByItsOwnLabel()
	{
		final JSONArray c = BedrockBackend.labelledImageContent( "Q?", images( 3 ) );
		assertEquals( Arrays.asList( "text", "image", "text", "image", "text", "image", "text" ),
				types( c ) );
		assertEquals( "Image 1:", c.getJSONObject( 0 ).getString( "text" ) );
		assertEquals( "Image 2:", c.getJSONObject( 2 ).getString( "text" ) );
		assertEquals( "Image 3:", c.getJSONObject( 4 ).getString( "text" ) );
	}

	@Test
	public void theLabelNumberMatchesTheCandidateNumberTheVerdictUses()
	{
		// The whole point: VERDICT_17 has to be answerable about a specific crop. With one image per
		// candidate, "Image 17:" IS candidate 17 — the model no longer has to count to find it.
		final JSONArray c = BedrockBackend.labelledImageContent( "Q?", images( 30 ) );
		assertEquals( "Image 17:", c.getJSONObject( 2 * 16 ).getString( "text" ) );
		assertEquals( "b64-16",
				c.getJSONObject( 2 * 16 + 1 ).getJSONObject( "source" ).getString( "data" ) );
	}

	@Test
	public void theImagesStayInOrderAndNoneIsDropped()
	{
		final JSONArray c = BedrockBackend.labelledImageContent( "Q?", images( 5 ) );
		final List< String > data = new ArrayList<>();
		for ( int i = 0; i < c.length(); i++ )
		{
			final JSONObject o = c.getJSONObject( i );
			if ( "image".equals( o.getString( "type" ) ) )
				data.add( o.getJSONObject( "source" ).getString( "data" ) );
		}
		assertEquals( Arrays.asList( "b64-0", "b64-1", "b64-2", "b64-3", "b64-4" ), data );
	}

	@Test
	public void thePromptIsAlwaysLast()
	{
		final JSONArray c = BedrockBackend.labelledImageContent( "Answer please", images( 4 ) );
		final JSONObject last = c.getJSONObject( c.length() - 1 );
		assertEquals( "text", last.getString( "type" ) );
		assertEquals( "Answer please", last.getString( "text" ) );
	}

	@Test
	public void aSingleImageKeepsThePlainImageThenTextForm()
	{
		// That is the form the single-image tip actually describes; a lone "Image 1:" adds nothing.
		assertEquals( Arrays.asList( "image", "text" ),
				types( BedrockBackend.labelledImageContent( "Q?", images( 1 ) ) ) );
	}

	@Test
	public void noImagesIsJustThePrompt()
	{
		assertEquals( Arrays.asList( "text" ),
				types( BedrockBackend.labelledImageContent( "Q?", images( 0 ) ) ) );
	}
}
