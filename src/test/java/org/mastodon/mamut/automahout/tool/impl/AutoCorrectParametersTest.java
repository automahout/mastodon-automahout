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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;
import org.mastodon.mamut.automahout.tool.ToolParameter;

/** auto.correct declares its parameters, so the Run a Tool dialog can ask for every timepoint (t = -1). */
public class AutoCorrectParametersTest
{
	@Test
	public void everyParameterIsDeclaredOnceAndBlankKeepsTheDefaults()
	{
		final List< ToolParameter > ps = new AutoCorrectTool().getParameters();
		final Set< String > names = new HashSet<>();
		for ( final ToolParameter p : ps )
			assertTrue( "declared twice: " + p.name, names.add( p.name ) );
		for ( final String n : new String[] { AutoCorrectTool.PARAM_T, AutoCorrectTool.PARAM_LONG_LINK_FACTOR,
				AutoCorrectTool.PARAM_MERGE_OVERLAP_RATIO, AutoCorrectTool.PARAM_MIN_INTENSITY_RATIO, AutoCorrectTool.PARAM_MIN_SIZE_RATIO,
				AutoCorrectTool.PARAM_FILL_GAPS, AutoCorrectTool.PARAM_GAP_CROP_FACTOR, AutoCorrectTool.PARAM_MIN_TRACKLET_LENGTH,
				AutoCorrectTool.PARAM_DIVISION_LINK_FACTOR, AutoCorrectTool.PARAM_MIN_BRANCH_LENGTH,
				AutoCorrectTool.PARAM_MIN_DIVISION_INTERVAL, AutoCorrectTool.PARAM_REFIT, AutoCorrectTool.PARAM_ANISOTROPY_THRESHOLD } )
			assertTrue( "not declared: " + n, names.contains( n ) );
		assertEquals( 13, ps.size() );
		for ( final ToolParameter p : ps )
			if ( p.name.equals( AutoCorrectTool.PARAM_T ) )
				assertNull( "blank t = the timepoint shown", p.defaultValue );
			else if ( p.name.equals( AutoCorrectTool.PARAM_FILL_GAPS ) )
				assertEquals( Boolean.TRUE, p.defaultValue );
	}
}
