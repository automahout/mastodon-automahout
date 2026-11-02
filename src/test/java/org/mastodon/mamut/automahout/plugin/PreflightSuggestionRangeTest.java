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
package org.mastodon.mamut.automahout.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.SpinnerNumberModel;

import org.junit.jupiter.api.Test;

/**
 * The pre-flight dialog must survive whatever number the model writes.
 *
 * <p>Reported as a freeze, after the request had reached the model and been answered. The dialog
 * fed the model's suggested numbers straight into {@code SpinnerNumberModel}, which throws when the
 * value is outside its range — on the EDT, inside {@code SwingWorker.done()}, where the throw is
 * swallowed and the dialog simply never appears. Nothing in the code had changed; the model's
 * answer is different every run, and this run's was out of range.</p>
 */
public class PreflightSuggestionRangeTest
{
	@Test
	public void aSpinnerReallyDoesThrowOnAnOutOfRangeValue()
	{
		// The mechanism, asserted rather than assumed, because the whole fix rests on it.
		assertThrows( IllegalArgumentException.class,
				() -> new SpinnerNumberModel( 65.0, 0.0, 1.0, 0.05 ),
				"COMPLETENESS_TARGET: 65 -- the model writing a percentage" );
		assertThrows( IllegalArgumentException.class,
				() -> new SpinnerNumberModel( 25, 1, 20, 1 ), "REFINE_ITERATIONS: 25" );
		assertThrows( IllegalArgumentException.class,
				() -> new SpinnerNumberModel( 0, 1, 100_000, 1 ), "PATCH_SIZE containing 0" );
	}

	@Test
	public void suggestionsTheDialogCannotShowAreRejected()
	{
		assertFalse( AutonomousTrackingAction.completenessTargetInRange( 65.0 ),
				"a percentage written where a fraction was asked for" );
		assertFalse( AutonomousTrackingAction.completenessTargetInRange( -0.1 ) );
		assertFalse( AutonomousTrackingAction.completenessTargetInRange( Double.NaN ),
				"an unparseable number must not reach the spinner either" );
		assertFalse( AutonomousTrackingAction.refineIterationsInRange( 0 ) );
		assertFalse( AutonomousTrackingAction.refineIterationsInRange( 25 ) );
		assertFalse( AutonomousTrackingAction.patchSizeInRange( new int[] { 0, 0, 0 } ) );
		assertFalse( AutonomousTrackingAction.patchSizeInRange( new int[] { 368, 352 } ) );
		assertFalse( AutonomousTrackingAction.patchSizeInRange( null ) );
	}

	@Test
	public void theSuggestionsAModelActuallyGivesAreKept()
	{
		assertTrue( AutonomousTrackingAction.completenessTargetInRange( 0.65 ) );
		assertTrue( AutonomousTrackingAction.completenessTargetInRange( 0.0 ) );
		assertTrue( AutonomousTrackingAction.completenessTargetInRange( 1.0 ) );
		assertTrue( AutonomousTrackingAction.refineIterationsInRange( 2 ) );
		assertTrue( AutonomousTrackingAction.refineIterationsInRange( 20 ) );
		assertTrue( AutonomousTrackingAction.patchSizeInRange( new int[] { 368, 352, 1 } ) );
	}

	@Test
	public void whatIsAcceptedIsExactlyWhatTheSpinnerAccepts()
	{
		// The two used to be stated in different places, which is the whole defect: the parser kept
		// what the spinner would reject. Walk the boundary and require them to agree.
		for ( final int v : new int[] { 0, 1, 2, 19, 20, 21, 100 } )
		{
			boolean spinnerOk = true;
			try
			{
				new SpinnerNumberModel( v, 1, 20, 1 );
			}
			catch ( final IllegalArgumentException e )
			{
				spinnerOk = false;
			}
			org.junit.jupiter.api.Assertions.assertEquals( spinnerOk,
					AutonomousTrackingAction.refineIterationsInRange( v ),
					"refine_iterations " + v + ": parser and spinner disagree" );
		}
	}
}
