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

import java.util.ArrayList;
import java.util.List;

import org.mastodon.mamut.model.Model;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;
import org.mastodon.model.tag.TagSetStructure;
import org.mastodon.model.tag.TagSetStructure.Tag;
import org.mastodon.model.tag.TagSetStructure.TagSet;

/**
 * Checks whether a spot carries a user-designated "do not touch" tag — e.g. a manually applied
 * {@code Tracking > Approved} or {@code Status > Completed} tag from a prior curation pass, configured
 * via {@code adaptive_track}'s {@code protected_tag_set}/{@code protected_tag_label} parameters.
 *
 * <p>A spot matching this tag is a human-confirmed result: the per-window pipeline must never delete
 * it, never let a fresh ELEPHANT detection duplicate/overlap it, and must use it as trusted {@code tp}
 * training signal WITHOUT discarding it afterward — unlike the existing GT/reliable-track path in
 * {@code ElephantTool.runAdaptiveTrack}, which trains on trusted annotations and then deletes and
 * re-detects them anyway (correct for "teach the model," wrong for "this specific result is already
 * finished and must survive verbatim").</p>
 *
 * <p>Tag set/tag names are entirely user-defined in Mastodon's own tagging UI, so this deliberately
 * takes them as explicit configuration rather than guessing a naming convention.</p>
 */
public final class ProtectedSpots
{
	private ProtectedSpots()
	{}

	/**
	 * {@link ToolContext#getSharedData}/{@code setSharedData} key for the active {@link Config} (or
	 * {@code null} if protection is off) for the CURRENT {@code adaptive_track} run. Stored once, at the
	 * top of {@code ElephantTool.runAdaptiveTrack}, so every deletion/mutation helper across
	 * {@code ElephantTool} and {@code AutoCorrectOrchestrator} can consult it without threading a new
	 * parameter through every one of their signatures — the number of call sites that can delete or
	 * modify a spot is too large for per-call-site plumbing to stay maintainable/complete.
	 */
	public static final String SHARED_CONFIG_KEY = "adaptive_track.protected_spots_config";

	/** Convenience: fetches the active {@link Config} from {@code ctx}, or {@code null} if none is set. */
	public static Config fromContext( final ToolContext ctx )
	{
		final Object v = ctx.getSharedData( SHARED_CONFIG_KEY );
		return v instanceof Config ? ( Config ) v : null;
	}

	/** Convenience: {@code isProtected(model, spot, fromContext(ctx))}. */
	public static boolean isProtected( final ToolContext ctx, final Model model, final Spot spot )
	{
		return isProtected( model, spot, fromContext( ctx ) );
	}

	/**
	 * True if {@code candidate} (already added at {@code timepoint}, e.g. right after
	 * {@code graph.addVertex(...).init(...)}) geometrically overlaps a PROTECTED spot at the same
	 * timepoint — bounding-sphere distance test, the same "duplicate" definition already used elsewhere
	 * in this codebase (e.g. {@code AutoCorrectOrchestrator.mergeDuplicateSpots}/{@code collidesWithAny}).
	 * Used to reject a fresh ELEPHANT detection that would otherwise duplicate an already-tracked,
	 * human-confirmed cell. Callers must hold the graph's write lock (or otherwise guarantee no
	 * concurrent mutation) — this does no locking of its own.
	 */
	public static boolean overlapsProtected( final ToolContext ctx, final Model model, final ModelGraph graph,
			final Spot candidate, final int timepoint )
	{
		final Config config = fromContext( ctx );
		if ( config == null )
			return false;
		final double[] cPos = new double[ 3 ];
		candidate.localize( cPos );
		final double cRadius = Math.sqrt( candidate.getBoundingSphereRadiusSquared() );
		for ( final Spot s : graph.vertices() )
		{
			if ( s.getTimepoint() != timepoint || s.getInternalPoolIndex() == candidate.getInternalPoolIndex() )
				continue;
			if ( !isProtected( model, s, config ) )
				continue;
			final double[] pPos = new double[ 3 ];
			s.localize( pPos );
			final double sumR = cRadius + Math.sqrt( s.getBoundingSphereRadiusSquared() );
			double distSq = 0;
			for ( int d = 0; d < 3; d++ )
			{
				final double diff = cPos[ d ] - pPos[ d ];
				distSq += diff * diff;
			}
			if ( distSq < sumR * sumR )
				return true;
		}
		return false;
	}

	/**
	 * Deletes every NON-protected spot at {@code timepoint} that geometrically overlaps a protected
	 * spot (same bounding-sphere test as {@link #overlapsProtected}) — cleans up a duplicate detection
	 * sitting on an already-confirmed cell. The protected spot itself is never touched or considered
	 * for deletion.
	 *
	 * <p>Needed as its own pass because every OTHER duplicate-cleanup mechanism in this codebase
	 * ({@code AutoCorrectOrchestrator.resolveOverlappingDuplicates}/{@code deduplicateSpotsAtTimepoint}/
	 * {@code mergeDuplicateSpots}) deliberately excludes protected spots from its comparisons entirely
	 * (so the protected spot itself is never selected for demotion/removal) — but that also makes a
	 * duplicate sitting right next to it invisible to those passes, since there is no longer anything
	 * for it to be flagged as "overlapping". This pass exists specifically to catch that case: it is
	 * safe to run unconditionally (a no-op when no protection is configured, or when a timepoint has no
	 * protected spots), so callers can invoke it after every predict without checking either
	 * precondition themselves.</p>
	 *
	 * @return number of spots deleted
	 */
	public static int deleteOverlappingNonProtected( final ToolContext ctx, final int timepoint )
	{
		final Config config = fromContext( ctx );
		if ( config == null )
			return 0;
		final Model model = ctx.getModel();
		final ModelGraph graph = ctx.getGraph();

		final List< double[] > protectedSpheres = new ArrayList<>(); // {x,y,z,radius}
		final List< Spot > toDelete = new ArrayList<>();
		graph.getLock().readLock().lock();
		try
		{
			final double[] pos = new double[ 3 ];
			for ( final Spot s : graph.vertices() )
			{
				if ( s.getTimepoint() != timepoint || !isProtected( model, s, config ) )
					continue;
				s.localize( pos );
				protectedSpheres.add( new double[] {
						pos[ 0 ], pos[ 1 ], pos[ 2 ], Math.sqrt( s.getBoundingSphereRadiusSquared() ) } );
			}
			if ( protectedSpheres.isEmpty() )
				return 0;
			for ( final Spot s : graph.vertices() )
			{
				if ( s.getTimepoint() != timepoint || isProtected( model, s, config ) )
					continue;
				s.localize( pos );
				final double r = Math.sqrt( s.getBoundingSphereRadiusSquared() );
				for ( final double[] p : protectedSpheres )
				{
					final double sumR = r + p[ 3 ];
					final double dx = pos[ 0 ] - p[ 0 ], dy = pos[ 1 ] - p[ 1 ], dz = pos[ 2 ] - p[ 2 ];
					if ( dx * dx + dy * dy + dz * dz < sumR * sumR )
					{
						final Spot ref = graph.vertexRef();
						ref.refTo( s );
						toDelete.add( ref );
						break;
					}
				}
			}
		}
		finally
		{
			graph.getLock().readLock().unlock();
		}
		if ( toDelete.isEmpty() )
			return 0;

		graph.getLock().writeLock().lock();
		try
		{
			for ( final Spot s : toDelete )
				if ( !GraphWatch.dirty( graph, s ) ) // collected under the read lock above; see GraphWatch
					graph.remove( s );
		}
		finally
		{
			graph.getLock().writeLock().unlock();
		}
		for ( final Spot s : toDelete )
			graph.releaseRef( s );
		model.setUndoPoint();
		ctx.notifyGraphChanged();
		return toDelete.size();
	}

	/** Bundles the two config strings so call sites pass one nullable object instead of two strings. */
	public static final class Config
	{
		public final String tagSetName;

		public final String tagLabel;

		private Config( final String tagSetName, final String tagLabel )
		{
			this.tagSetName = tagSetName;
			this.tagLabel = tagLabel;
		}

		/** Returns a usable {@link Config}, or {@code null} if either string is missing (protection disabled). */
		public static Config of( final String tagSetName, final String tagLabel )
		{
			if ( tagSetName == null || tagSetName.trim().isEmpty()
					|| tagLabel == null || tagLabel.trim().isEmpty() )
				return null;
			return new Config( tagSetName.trim(), tagLabel.trim() );
		}
	}

	/** True if {@code spot} carries {@code config}'s tag ({@code config == null} means never protected). */
	public static boolean isProtected( final Model model, final Spot spot, final Config config )
	{
		if ( config == null )
			return false;
		final TagSet ts = byName( model.getTagSetModel().getTagSetStructure(), config.tagSetName );
		if ( ts == null )
			return false;
		final Tag tag = model.getTagSetModel().getVertexTags().tags( ts ).get( spot );
		return tag != null && config.tagLabel.equals( tag.label() );
	}

	private static TagSet byName( final TagSetStructure tss, final String name )
	{
		for ( final TagSet ts : tss.getTagSets() )
			if ( name.equals( ts.getName() ) )
				return ts;
		return null;
	}
}
