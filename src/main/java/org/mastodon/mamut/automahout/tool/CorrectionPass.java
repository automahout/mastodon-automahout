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

import org.scijava.plugin.SciJavaPlugin;

/**
 * One correction pass: a single rule that removes, links or reshapes, run at its place in a correction
 * plan ({@link PassPlan}). Three steps of a window are plans of these -- {@link Stage#DETECTED} runs on each
 * frame as ELEPHANT detects it (the cleanup in {@code predictWindow}), {@link Stage#FRAME} once per timepoint
 * of correction (the old {@code correctTimepoint}), {@link Stage#LINEAGE} once over a span of finished tracks
 * (the old {@code runTopologyConsistency}).
 *
 * <p>A pass from another jar is a SciJava plugin, {@code @Plugin( type = CorrectionPass.class )}. With a
 * new id it is inserted {@link #after()} the pass it names; with a built-in pass's id and a priority above
 * {@link org.scijava.Priority#NORMAL} it replaces that pass. See {@code docs/writing-a-tool.md}.</p>
 *
 * <p>Two kinds of condition, kept apart: {@link #applicability} is about the DATA (2D or 3D, modality,
 * which detector this run uses) and is logged once with its reason when it leaves a pass out;
 * {@link #runsAt} is about the moment (a setting, the frame's position, what an earlier pass did) and is
 * silent. Every pass follows the graph rules of {@code docs/writing-a-tool.md}: edits through
 * {@link GraphEdits}, no image read under a graph lock, dirty spots ({@link GraphWatch}) left alone.</p>
 */
public interface CorrectionPass extends SciJavaPlugin
{
	/** Which correction plan a pass belongs to. */
	enum Stage
	{
		/**
		 * Once per timepoint right after the detector added its spots, before anything links them: shape fits
		 * and the checks that delete what is not a cell. ELEPHANT's detection only, so far.
		 */
		DETECTED,
		/** Once per timepoint, on that frame and its links to the frames beside it. */
		FRAME,
		/** Once per window, over the span's finished tracks: divisions, breaks, flickers. */
		LINEAGE
	}

	/**
	 * What a pass's count means, for the per-frame summary ({@code CorrectionResult}). {@link #NONE} is
	 * logged by the pass itself and counted nowhere.
	 */
	enum Effect
	{
		SPOTS_MERGED, GAPS_FILLED, LINKS_DELETED, RELINKED, SPOTS_DELETED, SPOTS_REFIT, NONE
	}

	/** Unique and stable, lower case with dots; a pass that needs ELEPHANT's model starts {@code elephant.}. */
	String getId();

	/** One sentence: what the pass changes and when. */
	String getDescription();

	Stage getStage();

	/** Usually {@link ToolRole#CORRECT_DETECTIONS}, {@link ToolRole#CORRECT_LINEAGE} or {@link ToolRole#SHAPE}, as for a tool. */
	ToolRole getRole();

	/** What {@link #run}'s count is. */
	default Effect getEffect()
	{
		return Effect.NONE;
	}

	/**
	 * For a pass with a new id: the id of the pass it follows in the plan. {@code null} puts it at the end.
	 * Ignored for a pass that replaces a built-in one (it takes that one's place).
	 */
	default String after()
	{
		return null;
	}

	/** Whether the pass suits data like this, and why not. Asked once per plan run. */
	default Applicability applicability( final DataProfile data )
	{
		return Applicability.always();
	}

	/** Whether the pass runs here and now: settings, the frame's position, earlier passes' counts. */
	default boolean runsAt( final PassScope scope )
	{
		return true;
	}

	/** Runs the pass; returns how many things it changed (see {@link #getEffect()}). */
	int run( PassScope scope );

	/**
	 * When not {@code null}, the graph's link lists are checked after this pass's place in the plan (whether
	 * or not it ran), and the run stops "after {@code <this>}" if they are damaged. For passes after which a
	 * damaged graph has hung the application before.
	 */
	default String integrityCheckAfter()
	{
		return null;
	}
}
