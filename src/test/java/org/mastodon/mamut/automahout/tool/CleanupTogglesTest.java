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
package org.mastodon.mamut.automahout.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for the diagnostic on/off switches over the two count-reducing cleanup passes. */
public class CleanupTogglesTest
{
	@Test
	public void aDiagnosticThatDeletesNothingIsOffUntilAskedFor()
	{
		// The VLM audit of the flat-contrast verdict removes nothing; it only prints a line. On this
		// project's crops it already returned no separation (77% of flagged called background against
		// 71% of kept), and it costs a multi-image VLM round trip up to three times per window — so it
		// is opt-in, like the fluorescence filter and unlike the two cleanup passes.
		assertFalse( CleanupToggles.resolveOptIn( null ) );
		assertFalse( CleanupToggles.resolveOptIn( Boolean.FALSE ) );
		assertTrue( CleanupToggles.resolveOptIn( Boolean.TRUE ) );
	}

	@Test
	public void aPassNobodyHasAnOpinionAboutStaysOn()
	{
		// The whole point of the default: a build carrying these switches must behave exactly like the
		// build before them for anyone who never touches the dialog.
		assertTrue( CleanupToggles.resolve( null, null ) );
	}

	@Test
	public void aPerCallParameterDecidesWhenNoPreflightChoiceWasMade()
	{
		assertFalse( CleanupToggles.resolve( null, Boolean.FALSE ) );
		assertTrue( CleanupToggles.resolve( null, Boolean.TRUE ) );
	}

	@Test
	public void thePreflightChoiceWinsOverThePerCallParameter()
	{
		// The user unticked the box for a diagnostic run; the agent then asked for the pass anyway (it
		// is not told the switch exists, so its parameter map simply carries the default).
		assertFalse( CleanupToggles.resolve( Boolean.FALSE, Boolean.TRUE ) );
		// And the reverse: the user left the pass on, so an agent cannot switch it off.
		assertTrue( CleanupToggles.resolve( Boolean.TRUE, Boolean.FALSE ) );
	}

	@Test
	public void aNonBooleanValueIsIgnoredRatherThanTakenAsFalse()
	{
		// Shared data is untyped; a stale/foreign value under one of these keys must not silently
		// disable a precision guard.
		assertTrue( CleanupToggles.resolve( "false", null ) );
		assertTrue( CleanupToggles.resolve( null, "false" ) );
	}

	@Test
	public void aNormalRunSaysNothingAndADiagnosticRunNamesEveryPassItTurnedOff()
	{
		// The defaults: flat-contrast deletion ON, duplicate MERGING off. Nothing to say about either.
		assertNull( CleanupToggles.disabledSummary( true, false ) );
		final String flatOff = CleanupToggles.disabledSummary( false, false );
		assertNotNull( flatOff );
		assertTrue( flatOff.contains( "flat-contrast" ) );
		assertFalse( flatOff.contains( "MERGING" ) );
		// Merging is the one that is notable when it is ON: it lets geometry overrule the detector.
		final String mergeOn = CleanupToggles.disabledSummary( true, true );
		assertNotNull( mergeOn, "a run that merges detections must say so" );
		assertTrue( mergeOn.contains( "MERGING" ) );
		assertFalse( mergeOn.contains( "flat-contrast" ) );
		final String both = CleanupToggles.disabledSummary( false, true );
		assertTrue( both.contains( "flat-contrast" ) && both.contains( "MERGING" ) );
	}

	@Test
	public void nothingMergesUnlessTheUserAsksForIt()
	{
		// The detector has already decided where one cell ends. Every merge pass overrules that from
		// geometry alone, which in a dense field is equally true of two real neighbours — observed as
		// one ellipse drawn around two clearly separated nuclei.
		assertFalse( CleanupToggles.resolveOptIn( null, null ), "unset must not merge" );
		assertFalse( CleanupToggles.resolveOptIn( null, Boolean.FALSE ) );
		assertTrue( CleanupToggles.resolveOptIn( null, Boolean.TRUE ), "an explicit parameter turns it on" );
		assertTrue( CleanupToggles.resolveOptIn( Boolean.TRUE, null ), "a pre-flight tick turns it on" );
		assertFalse( CleanupToggles.resolveOptIn( Boolean.FALSE, Boolean.TRUE ),
				"the pre-flight choice wins over the parameter, same precedence as every other switch" );
	}

	@Test
	public void theFluorescenceFilterIsOffUnlessAskedFor()
	{
		// The opposite default from the other two: they turn a pass off, this turns an extra one on, and
		// total brightness only stands for labelled material under fluorescence. A dataset nobody has
		// said anything about must not have real cells deleted for being faint.
		assertFalse( CleanupToggles.resolveOptIn( null ), "unset leaves it off" );
		assertTrue( CleanupToggles.resolveOptIn( Boolean.TRUE ), "an explicit tick turns it on" );
		assertFalse( CleanupToggles.resolveOptIn( Boolean.FALSE ), "an explicit untick leaves it off" );
		assertFalse( CleanupToggles.resolveOptIn( "true" ), "a non-Boolean is not an opinion" );
		// ...and the other two still default ON, which is the whole distinction.
		assertTrue( CleanupToggles.resolve( null, null ), "the diagnostic switches stay on by default" );
	}

	@Test
	public void cropTuningIsOnUnlessTheUserTurnsItOff()
	{
		// Same default as the other diagnostic switches: unset means the pipeline behaves as it did
		// before the switch existed. The crop exists because it makes tuning cheap, so it stays on
		// until someone has a reason.
		assertTrue( CleanupToggles.resolve( null, null ), "unset leaves crop-tuning on" );
		assertTrue( CleanupToggles.resolve( Boolean.TRUE, null ) );
		assertFalse( CleanupToggles.resolve( Boolean.FALSE, null ), "an explicit untick turns it off" );
	}

	@Test
	public void theSplitPassIsOffUntilAskedFor()
	{
		// Measured: in one window it split 11 single nuclei against at most 2 right splits, and 12 of
		// the 13 same-cell double spots came from it. See CleanupToggles.SHARED_SPLIT_MERGED_OVERRIDE.
		final ToolContext ctx = new ToolContext( null );
		assertFalse( CleanupToggles.splitMergedEnabled( ctx ), "nobody asked: off" );
		CleanupToggles.setRequested( ctx, true, false, true );
		assertTrue( CleanupToggles.splitMergedEnabled( ctx ), "the parameter turns it on" );
		ctx.setSharedData( CleanupToggles.SHARED_SPLIT_MERGED_OVERRIDE, Boolean.FALSE );
		assertFalse( CleanupToggles.splitMergedEnabled( ctx ), "a pre-flight choice wins over it" );
	}

	@Test
	public void aRunWithSplittingOnSaysSo()
	{
		assertNull( CleanupToggles.disabledSummary( true, false, false ), "the default run is unremarkable" );
		final String on = CleanupToggles.disabledSummary( true, false, true );
		assertNotNull( on );
		assertTrue( on.contains( "SPLITTING is switched on" ) );
		final String both = CleanupToggles.disabledSummary( true, true, true );
		assertTrue( both.contains( "MERGING" ) && both.contains( "SPLITTING" ) );
	}

	/**
	 * Ratchet: the gate is the pass's first statement, not a check at its call site. The merge switch
	 * was gated at one call site and reached one of four passes.
	 */
	@Test
	public void theSplitPassGatesItself() throws java.io.IOException
	{
		final String src = new String( java.nio.file.Files.readAllBytes( java.nio.file.Paths.get( "src", "main",
				"java", "org", "mastodon", "mamut", "automahout", "autocorrect", "AutoCorrectOrchestrator.java" ) ),
				java.nio.charset.StandardCharsets.UTF_8 ).replaceAll( "(?s)/\\*.*?\\*/", "" )
						.replaceAll( "//[^\\n]*", "" );
		final int at = src.indexOf( "public static int splitMergedDetections( final ToolContext ctx, final int timepoint,\n" );
		assertTrue( at >= 0 );
		final int body = src.indexOf( '{', at );
		final String first = src.substring( body + 1, src.indexOf( ';', body ) ).trim();
		assertTrue( first.startsWith( "if ( !CleanupToggles.splitMergedEnabled( ctx ) )" ), first );
	}

	/** Shape fitting is ON unless the pre-flight dialog unticked it, so the benchmark (no dialog) still fits. */
	@Test
	public void shapeFittingIsOnUnlessSwitchedOff()
	{
		final ToolContext ctx = new ToolContext( null );
		assertTrue( CleanupToggles.shapeFitEnabled( ctx ) );
		ctx.setSharedData( CleanupToggles.SHARED_SHAPE_FIT_OVERRIDE, Boolean.FALSE );
		assertFalse( CleanupToggles.shapeFitEnabled( ctx ) );
		ctx.setSharedData( CleanupToggles.SHARED_SHAPE_FIT_OVERRIDE, Boolean.TRUE );
		assertTrue( CleanupToggles.shapeFitEnabled( ctx ) );
	}
}
