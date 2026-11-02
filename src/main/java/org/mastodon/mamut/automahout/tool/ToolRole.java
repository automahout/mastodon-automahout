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

/**
 * Which stage of tracking a tool serves. The planner fills each stage of a run with the tools of that
 * role that suit the data (see {@link AutomahoutTool#applicability}); a tool from another jar declares
 * its role and is a candidate for that stage like a built-in one.
 */
public enum ToolRole
{
	/** Characterises the data: modality, density, motion -- what the other choices are made from. */
	PROFILE,
	/** Finds cells in frames. */
	DETECT,
	/** Fits or corrects the shape of detections (ellipse, extent). */
	SHAPE,
	/** Links detections across frames. */
	LINK,
	/** Removes, merges or splits detections that are not one cell each. */
	CORRECT_DETECTIONS,
	/** Repairs tracks and divisions. */
	CORRECT_LINEAGE,
	/** Trains or tunes a model on the run's own annotations. */
	TRAIN,
	/** Measures a result (against truth, or by its own consistency). */
	ASSESS,
	/** A single, targeted edit of a spot or link. */
	EDIT,
	/** Moves the viewer; changes no data. */
	VIEW,
	/** Runs several stages itself (an integrated pipeline). */
	PIPELINE,
	/** Not yet classified. */
	OTHER
}
