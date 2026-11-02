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
package org.mastodon.mamut.automahout.elephant;

import java.util.function.Predicate;

import org.mastodon.mamut.model.Model;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;
import org.mastodon.model.tag.TagSetStructure;
import org.mastodon.model.tag.TagSetStructure.Tag;
import org.mastodon.model.tag.TagSetStructure.TagSet;

/**
 * Marks spots as ELEPHANT detection positives ({@code tp}) or negatives ({@code fp}) using ELEPHANT's
 * own {@code Detection} tag set, so background false detections can be trained on as explicit negatives
 * (which suppress over-detection) rather than merely deleted, AND the tags are the same ones ELEPHANT's
 * GUI and training use (Detection&gt;tp / Detection&gt;fp). {@link ElephantIntegration}'s training request
 * reads these tags per spot; they persist on the spots between the labelling step and the training step
 * (and are visible/editable in the Mastodon TrackScheme). Reuses an existing {@code Detection} tag set +
 * its {@code tp}/{@code fp} tags if present, otherwise creates them.
 */
public final class DetectionTagLabels
{

	/** ELEPHANT's detection tag set. */
	public static final String TAG_SET_NAME = "Detection";

	/** True positive (a real cell) — trained as a positive detection label. */
	public static final String TP = "tp";

	/** False positive (background) — trained as an explicit negative. */
	public static final String FP = "fp";

	/**
	 * A cell the model MISSED — trained as a positive, at ten times the weight of a {@link #TP}.
	 *
	 * <p>The half of ELEPHANT's correction channel this pipeline could not reach. On the server
	 * ({@code app/apis/seg.py}) {@code fn} paints exactly the same ellipsoid as {@code tp} — outer
	 * contour, inner centre — but with {@code label_offset = 3}, and the loss
	 * ({@code elephant-core/elephant/losses.py}) multiplies every voxel above 2 by {@code false_weight},
	 * which this pipeline sends as 10. A correction of the model's own error is worth ten
	 * confirmations, by design.</p>
	 *
	 * <p>Until this existed the wire protocol was binary — {@code FP.equals(label) ? "fp" : "tp"} — so
	 * every training call in a whole run reported {@code ('fn', 0)} on every frame. The pipeline had
	 * never once told the detector about a cell it failed to find, which is the one thing that raises
	 * recall directly. Raising it instead by lowering thresholds and interpolating along tracks is what
	 * the damping chain exists to control.</p>
	 */
	public static final String FN = "fn";

	/**
	 * Kept as a detection but withheld from training: neither taught as a cell nor declared background.
	 *
	 * <p>The third answer the pipeline needs and did not have. A detection the flat-contrast check flags
	 * is one two of our own estimates disagree about, and the two labels available forced a choice
	 * between teaching the model that it is a cell and teaching it that it is background — both of them
	 * assertions nothing here can support. Withholding it asserts nothing: ELEPHANT's loss ignores
	 * unlabelled voxels, so the model simply never sees that position.</p>
	 *
	 * <p>This matters because the annotation set IS the next round's supervision. Measured on a stated
	 * 250 cells/frame: 507 then 564 spots/tp were sent as positives, because the flat-contrast check
	 * only ever ran on timepoints whose raw count crossed the detection ceiling, so the model was taught
	 * roughly twice the cell count that exists and then re-detected against what it had been taught.</p>
	 *
	 * <p>Visible in the Mastodon TrackScheme like the other two, so what was withheld can be inspected
	 * rather than inferred from a count.</p>
	 */
	public static final String UNLABELED = "unlabeled";

	private static final int GREEN = 0xFF00CC00;

	private static final int RED = 0xFFCC0000;

	private static final int GREY = 0xFF888888;

	private static final int BLUE = 0xFF0066CC;

	private DetectionTagLabels()
	{}

	/**
	 * Ensures the tag set and its {@code tp}/{@code fp} tags exist, and returns the live {@link TagSet}
	 * from the model's applied structure (for tag lookup + assignment).
	 */
	public static TagSet ensure( final Model model )
	{
		final TagSetStructure tss = new TagSetStructure();
		tss.set( model.getTagSetModel().getTagSetStructure() );
		TagSet ts = byName( tss, TAG_SET_NAME );
		boolean changed = false;
		if ( ts == null )
		{
			ts = tss.createTagSet( TAG_SET_NAME );
			changed = true;
		}
		if ( tagByLabel( ts, TP ) == null )
		{
			ts.createTag( TP, GREEN );
			changed = true;
		}
		if ( tagByLabel( ts, FP ) == null )
		{
			ts.createTag( FP, RED );
			changed = true;
		}
		if ( tagByLabel( ts, FN ) == null )
		{
			ts.createTag( FN, BLUE );
			changed = true;
		}
		if ( tagByLabel( ts, UNLABELED ) == null )
		{
			ts.createTag( UNLABELED, GREY );
			changed = true;
		}
		if ( changed )
			model.getTagSetModel().setTagSetStructure( tss );
		// Re-fetch from the applied structure so the returned Tag instances are the model's own.
		return byName( model.getTagSetModel().getTagSetStructure(), TAG_SET_NAME );
	}

	/** The {@code tp} tag of the (already-ensured) tag set. */
	public static Tag tpTag( final TagSet ts )
	{
		return tagByLabel( ts, TP );
	}

	/** The {@code fn} tag of the (already-ensured) tag set — see {@link #FN}. */
	public static Tag fnTag( final TagSet ts )
	{
		return tagByLabel( ts, FN );
	}

	/** The {@code fp} tag of the (already-ensured) tag set. */
	public static Tag fpTag( final TagSet ts )
	{
		return tagByLabel( ts, FP );
	}

	/** The {@code unlabeled} tag of the (already-ensured) tag set — see {@link #UNLABELED}. */
	public static Tag unlabeledTag( final TagSet ts )
	{
		return tagByLabel( ts, UNLABELED );
	}

	/**
	 * Returns the spot's detection-label ({@code "tp"}/{@code "fp"}) in this tag set, or {@code null} if
	 * the tag set does not exist or the spot is untagged.
	 */
	public static String labelOf( final Model model, final Spot spot )
	{
		final TagSet ts = byName( model.getTagSetModel().getTagSetStructure(), TAG_SET_NAME );
		if ( ts == null )
			return null;
		final Tag tag = model.getTagSetModel().getVertexTags().tags( ts ).get( spot );
		return tag == null ? null : tag.label();
	}

	/**
	 * The tag with this label if the Detection tag set ALREADY exists, otherwise {@code null}.
	 *
	 * <p>Never creates it. {@link #ensure} modifies the tag-set STRUCTURE, which must not happen while
	 * a vertex write lock is held, and the one caller that needs this — {@code GraphEdits} correcting
	 * what ELEPHANT's graph listener wrote — runs inside one. There is nothing to correct when the set
	 * does not exist: the listener could not have tagged anything either.</p>
	 */
	public static Tag existingTag( final Model model, final String label )
	{
		if ( model == null )
			return null;
		final TagSet ts = byName( model.getTagSetModel().getTagSetStructure(), TAG_SET_NAME );
		return ts == null ? null : tagByLabel( ts, label );
	}

	/**
	 * An {@code fp} test with the tag set resolved once, for loops over the graph.
	 *
	 * <p>{@link #labelOf} re-resolves the tag set by NAME on every call, which is a linear scan of the
	 * tag-set structure per spot. That is invisible on a handful of spots and not on a loop over every
	 * vertex in the graph, which is what the cell counters do.</p>
	 *
	 * <p>Returns a test that is always false when there is no tag set, which is the honest reading:
	 * nothing has been labelled a negative, so nothing is one.</p>
	 */
	public static Predicate< Spot > fpTest( final Model model )
	{
		final TagSet ts = model == null ? null
				: byName( model.getTagSetModel().getTagSetStructure(), TAG_SET_NAME );
		if ( ts == null )
			return s -> false;
		return s -> {
			final Tag tag = model.getTagSetModel().getVertexTags().tags( ts ).get( s );
			return tag != null && FP.equals( tag.label() );
		};
	}

	/**
	 * How many spots at some timepoints carry each detection tag, and how many carry none.
	 *
	 * <p>Exists because a run reported {@code ('tp', 0), ('fn', 59)} on every training frame and the
	 * only way to tell WHERE that happened was to guess. {@code fn} is set in exactly one place
	 * ({@code AutoCorrectOrchestrator.tagAsMissedCell}, two call sites, about two spots per
	 * timepoint), so either the graph really carries 59 of them or something between the graph and the
	 * wire turns the rest into corrections. Counted on the graph, this says which.</p>
	 *
	 * <p>{@link #untagged} is the load-bearing number. The wire protocol sends anything that is
	 * neither {@code fp} nor {@code fn} as {@code tp}, so an untagged spot is a positive by default —
	 * {@code ('tp', 0)} means "nothing was untagged", not "nothing was tagged tp".</p>
	 */
	public static final class Tally
	{
		public int tp, fp, fn, unlabeled, untagged;

		public int total()
		{
			return tp + fp + fn + unlabeled + untagged;
		}

		@Override
		public String toString()
		{
			return String.format( "%d spot(s): tp=%d fp=%d fn=%d unlabeled=%d untagged=%d "
					+ "(untagged go on the wire as tp)", total(), tp, fp, fn, unlabeled, untagged );
		}
	}

	/**
	 * The tag set itself, as text: every tag's id and label, and whether the set was found at all.
	 *
	 * <p>Four hypotheses about the {@code ('tp', 0), ('fn', N)} anomaly have now been tested and are
	 * all wrong — the writers are correct, the reader is correct on every tag-set shape the project
	 * can have, tags do not survive a spot's deletion into the pool slot that replaces it, and the
	 * counter itself is right. A spot that {@code addSpotFromJson} has just created, in a project
	 * whose graph was empty, nevertheless reads {@code fn}. So the next thing to look at is not a
	 * classification of the tag but the tag, which is what this prints.</p>
	 */
	public static String describeTagSet( final Model model )
	{
		final TagSet ts = byName( model.getTagSetModel().getTagSetStructure(), TAG_SET_NAME );
		if ( ts == null )
			return "no '" + TAG_SET_NAME + "' tag set";
		final StringBuilder sb = new StringBuilder( TAG_SET_NAME + "[" );
		for ( final Tag t : ts.getTags() )
			sb.append( t.id() ).append( '=' ).append( t.label() ).append( ' ' );
		return sb.append( ']' ).toString();
	}

	/** {@link Tally} of the spots at {@code timepoints}; a null/empty timepoint list counts nothing. */
	public static Tally tally( final Model model, final ModelGraph graph, final int[] timepoints )
	{
		final Tally out = new Tally();
		if ( timepoints == null || timepoints.length == 0 || graph == null )
			return out;
		final TagSet ts = byName( model.getTagSetModel().getTagSetStructure(), TAG_SET_NAME );
		graph.getLock().readLock().lock();
		try
		{
			for ( final Spot spot : graph.vertices() )
			{
				boolean inRange = false;
				for ( final int at : timepoints )
					if ( at == spot.getTimepoint() ) { inRange = true; break; }
				if ( !inRange )
					continue;
				final Tag tag = ts == null ? null
						: model.getTagSetModel().getVertexTags().tags( ts ).get( spot );
				final String label = tag == null ? null : tag.label();
				if ( TP.equals( label ) ) out.tp++;
				else if ( FP.equals( label ) ) out.fp++;
				else if ( FN.equals( label ) ) out.fn++;
				else if ( UNLABELED.equals( label ) ) out.unlabeled++;
				else out.untagged++;
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		return out;
	}

	private static TagSet byName( final TagSetStructure tss, final String name )
	{
		for ( final TagSet ts : tss.getTagSets() )
			if ( name.equals( ts.getName() ) )
				return ts;
		return null;
	}

	private static Tag tagByLabel( final TagSet ts, final String label )
	{
		for ( final Tag t : ts.getTags() )
			if ( label.equals( t.label() ) )
				return t;
		return null;
	}
}
