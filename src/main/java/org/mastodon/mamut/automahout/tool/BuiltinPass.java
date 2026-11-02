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

import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;


/**
 * A built-in {@link CorrectionPass}: one of the rules the pipeline has always run, as a value -- its body a
 * lambda over the rule's old call, its conditions given by {@link #when} and {@link #onlyFor}.
 */
public final class BuiltinPass implements CorrectionPass
{
	private final String id;

	private final Stage stage;

	private final ToolRole role;

	private final Effect effect;

	private final String description;

	private final ToIntFunction< PassScope > body;

	private Predicate< PassScope > runsAt = s -> true;

	private Function< DataProfile, Applicability > applicability = d -> Applicability.always();

	private String integrityCheckAfter;

	public BuiltinPass( final String id, final Stage stage, final ToolRole role, final Effect effect,
			final String description, final ToIntFunction< PassScope > body )
	{
		this.id = id;
		this.stage = stage;
		this.role = role;
		this.effect = effect;
		this.description = description;
		this.body = body;
	}

	public BuiltinPass when( final Predicate< PassScope > runsAt )
	{
		this.runsAt = runsAt;
		return this;
	}

	public BuiltinPass onlyFor( final Function< DataProfile, Applicability > applicability )
	{
		this.applicability = applicability;
		return this;
	}

	public BuiltinPass thenCheck( final String integrityCheckAfter )
	{
		this.integrityCheckAfter = integrityCheckAfter;
		return this;
	}

	@Override
	public String getId()
	{
		return id;
	}

	@Override
	public String getDescription()
	{
		return description;
	}

	@Override
	public Stage getStage()
	{
		return stage;
	}

	@Override
	public ToolRole getRole()
	{
		return role;
	}

	@Override
	public Effect getEffect()
	{
		return effect;
	}

	@Override
	public Applicability applicability( final DataProfile data )
	{
		return applicability.apply( data );
	}

	@Override
	public boolean runsAt( final PassScope scope )
	{
		return runsAt.test( scope );
	}

	@Override
	public int run( final PassScope scope )
	{
		return body.applyAsInt( scope );
	}

	@Override
	public String integrityCheckAfter()
	{
		return integrityCheckAfter;
	}

	/**
	 * For a pass that re-detects with ELEPHANT's model: in a run a pretrained detector detects, that model was
	 * never trained on this movie, and the consistency step's gap closing bridges the gaps instead.
	 */
	public static Applicability elephantDetects( final DataProfile data )
	{
		return data.pretrainedDetector == null
				? Applicability.always()
				: Applicability.notFor( "it re-detects with ELEPHANT's model, which was not trained on this movie (the "
						+ "detector is " + data.pretrainedDetector + "); gaps are bridged by the consistency step instead" );
	}

	/**
	 * For a pass written for stacks: a phantom detected between two nuclei adjacent along z. In 2D it judges ordinary
	 * neighbours instead; measured 2026-10-20 on Cellpose cyto3's masks of PSC 01 (every 10th frame): it deleted
	 * 6.4% of the detections, 82% of them on a real cell (76% of all detections are), and 5150 spots in a run;
	 * on HeLa 01 it deleted 1 of 963.
	 */
	public static Applicability stacksOnly( final DataProfile data )
	{
		return data.volumetric ? Applicability.always()
				: Applicability.notFor( "it removes a detection wedged between two others along z, a stack's artefact; "
						+ "in 2D it takes ordinary neighbouring cells (measured on PSC 01: 82% of what it deleted were real "
						+ "cells)" );
	}

	/** For a pass whose test is a brightness threshold: a cell is brighter than its surroundings only in fluorescence. */
	public static Applicability fluorescenceOnly( final DataProfile data )
	{
		return Boolean.FALSE.equals( data.fluorescenceLike )
				? Applicability.notFor( "it asks whether a detection is brighter than the background, which holds for "
						+ "fluorescence only; this movie does not read as fluorescence" )
				: Applicability.suits( 0.5, "fluorescence (or not measured yet: the pass measures it)" );
	}

	@Override
	public String toString()
	{
		return id;
	}
}
