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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JSpinner;

import org.junit.jupiter.api.Test;
import org.mastodon.mamut.automahout.tool.ToolContext;

/**
 * No spinner in the pre-flight dialog may be able to throw.
 *
 * <p>{@code SpinnerNumberModel} throws when its initial value is outside its range, on the EDT
 * inside {@code SwingWorker.done()}, where the throw is swallowed and the dialog never appears.
 * Values reach these spinners from the model's suggestions, from the ELEPHANT settings and from this
 * dialog's own defaults.</p>
 *
 * <p>This test exists because fixing the first of those three was not enough and was shipped as
 * though it were: the dialog still failed, on one of the six spinners that had not been looked at.
 * The rule is now structural — every spinner goes through {@code boundedSpinner}, which clamps —
 * and this reads the source to keep it that way.</p>
 */
public class PreflightSpinnerSafetyTest
{
	private static final Path SRC = Paths.get( "src", "main", "java", "org", "mastodon", "mamut",
			"automahout", "plugin", "AutonomousTrackingAction.java" );

	private static ToolContext quiet( final List< String > log )
	{
		final ToolContext ctx = new ToolContext( null );
		ctx.setLogCallback( log::add );
		return ctx;
	}

	@Test
	public void everySpinnerIsBuiltThroughTheClampingHelper() throws IOException
	{
		final String text = new String( Files.readAllBytes( SRC ), StandardCharsets.UTF_8 );
		final Matcher m = Pattern.compile( "new\\s+SpinnerNumberModel\\s*\\(" ).matcher( text );
		final List< String > bare = new ArrayList<>();
		int inHelper = 0;
		while ( m.find() )
		{
			final String before = text.substring( 0, m.start() );
			final int helper = before.lastIndexOf( "JSpinner boundedSpinner(" );
			final int nextMethod = before.lastIndexOf( "\n\t}" );
			if ( helper > nextMethod )
				inHelper++;
			else
				bare.add( "line " + before.split( "\n", -1 ).length );
		}
		assertEquals( 0, bare.size(),
				"a SpinnerNumberModel built outside boundedSpinner can throw on a value from the model "
						+ "or from the ELEPHANT settings, and the dialog then never appears with nothing "
						+ "logged. Offending site(s): " + bare );
		assertTrue( inHelper >= 2, "the helper itself should build them: " + inHelper );
	}

	@Test
	public void theValuesThatBrokeTheDialogAreClampedAndNamed()
	{
		final List< String > log = new ArrayList<>();
		final JSpinner target = AutonomousTrackingAction.boundedSpinner(
				quiet( log ), "completeness_target", 65.0, 0.0, 1.0, 0.05 );
		assertEquals( 1.0, ( ( Number ) target.getValue() ).doubleValue(), 1e-9 );

		final JSpinner refine = AutonomousTrackingAction.boundedSpinner(
				quiet( log ), "refine_iterations", 25, 1, 20, 1 );
		assertEquals( 20, ( ( Number ) refine.getValue() ).intValue() );

		final JSpinner patch = AutonomousTrackingAction.boundedSpinner(
				quiet( log ), "ELEPHANT patch size Z", 0, 1, 100_000, 1 );
		assertEquals( 1, ( ( Number ) patch.getValue() ).intValue() );

		assertEquals( 3, log.size(), "each clamp must be reported, or a bad setting stays invisible: "
				+ log );
		assertTrue( log.get( 0 ).contains( "completeness_target" ), log.toString() );
	}

	@Test
	public void aValueInRangeIsLeftAloneAndSaysNothing()
	{
		final List< String > log = new ArrayList<>();
		final JSpinner s = AutonomousTrackingAction.boundedSpinner(
				quiet( log ), "refine_iterations", 2, 1, 20, 1 );
		assertEquals( 2, ( ( Number ) s.getValue() ).intValue() );
		assertTrue( log.isEmpty(), "nothing to report when nothing was wrong: " + log );
	}

	@Test
	public void aNaNDoesNotReachTheSpinner()
	{
		final List< String > log = new ArrayList<>();
		final JSpinner s = AutonomousTrackingAction.boundedSpinner(
				quiet( log ), "completeness_target", Double.NaN, 0.0, 1.0, 0.05 );
		assertEquals( 0.0, ( ( Number ) s.getValue() ).doubleValue(), 1e-9 );
	}
}
