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
package org.mastodon.mamut.automahout.tool.remote;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.mastodon.mamut.automahout.tool.Applicability;
import org.mastodon.mamut.automahout.tool.AutomahoutTool;
import org.mastodon.mamut.automahout.tool.DataProfile;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.automahout.tool.ToolRegistry;
import org.mastodon.mamut.automahout.tool.ToolRole;

/**
 * Before adaptive tracking, the pretrained detectors are tried: if StarDist or Cellpose, applied as they
 * are, already detect this movie well, it is detected with that model and ELEPHANT's training is not
 * needed. The user's procedure: StarDist first, then Cellpose, each on a few frames, each shown to the
 * user, who accepts it or moves on; when neither is accepted, ELEPHANT's adaptive tracking runs as
 * before.
 *
 * <p>Only {@link FrameDetector}s that apply to the data are tried (a brightfield model is not tried on a
 * fluorescence movie). Nothing is added to the graph during the trial. The choice holds for the run: a
 * second adaptive_track lap does not ask again. With nobody to ask, ELEPHANT is used.</p>
 */
public final class DetectorTrial
{
	private DetectorTrial()
	{}

	/** {@link ToolContext#getSharedData} key, String: the id chosen this run, or {@link #ELEPHANT}. */
	public static final String SHARED_CHOICE = "detector.trial.choice";

	/** The choice that means ELEPHANT's adaptive tracking. */
	public static final String ELEPHANT = "elephant";

	/**
	 * {@link ToolContext#getSharedData} key, {@code int[][] { timepoints, counts }}: what the accepted
	 * detector found on the trial frames. The user looked at these and said they are right, so they are
	 * a better count of the cells than any guess.
	 */
	public static final String SHARED_ACCEPTED_COUNTS = "detector.trial.accepted_counts";

	/**
	 * {@link ToolContext#getSharedData} key, {@link Boolean}: TRUE = skip the trial dialog and choose from
	 * the image (see {@link #chooseFromImage}). Set from the pre-flight dialog.
	 */
	public static final String SHARED_AUTOMATIC = "detector.trial.automatic";

	/**
	 * The image-agreement F1 a pretrained detector needs to be chosen without the user (mean over the trial
	 * frames). Calibrated on HeLa 01 (2D fluorescence), the one movie with truth at hand, at t=0/45/91:
	 * the proxy put StarDist at 0.91/0.99/0.97 (true F1 0.93/0.92/0.91) and Cellpose at 0.76/0.88/0.93 (true
	 * 0.77/0.81/0.84), so 0.90 takes StarDist and would not take Cellpose alone. Re-measure on another
	 * movie before trusting it there.
	 */
	public static final double AUTO_MIN_AGREEMENT = 0.90;

	/**
	 * Where {@link #imageAgreement} does not apply (see {@link #imageAgreementApplies}), a detector is judged by
	 * how well its detections persist from one frame to the next ({@link #persistence}), which assumes nothing
	 * about how a cell looks: on every trial pair it must reach this. Measured on the development sequences
	 * (01), pairs (t, t+1) at the first, middle and last frame, matching within one measured cell radius,
	 * 2026-10-20: detectors with true F1 0.81-0.92 stayed at 0.88 or above on every pair (HeLa StarDist fluo,
	 * Cellpose nuclei and cyto3; PSC Cellpose cyto3), detectors with true F1 0.00-0.35 fell to 0.13 or below on
	 * at least one (PSC StarDist H&E: 0 detections on t=0; CE Cellpose nuclei per plane: 0.13 on t=0/1). It is
	 * blind to cells a detector misses consistently (Cellpose nuclei on HeLa, F1 0.81, scored 0.92), which is why
	 * 2D fluorescence keeps {@link #imageAgreement}.
	 */
	public static final double MIN_PERSISTENCE = 0.80;

	/**
	 * Whether {@link #imageAgreement} measures anything here: it takes cells to be the frame's bright regions,
	 * which holds for 2D fluorescence. Measured 2026-10-20 where it does not: on PSC (phase contrast) it put
	 * Cellpose cyto3 at 0.51/0.21/0.05 against a true F1 of 0.86/0.85/0.75; on CE (a stack, read as its maximum
	 * projection, where nuclei merge) at 0.86/0.60/0.00 against 0.00/0.44/0.60.
	 */
	static boolean imageAgreementApplies( final DataProfile data )
	{
		return Boolean.TRUE.equals( data.fluorescenceLike ) && !data.volumetric;
	}

	/** Bright = this many spreads (MAD) above the smoothed frame's median, the frame's own scale. */
	static final double AGREEMENT_SPREADS = 5.0;

	/** Bright regions smaller than this many pixels are specks, not cells. */
	static final int AGREEMENT_MIN_REGION_PX = 30;

	/** Whether this run detects with a pretrained detector (and so never trains or restores ELEPHANT's). */
	public static boolean pretrainedChosen( final ToolContext ctx )
	{
		final Object c = ctx.getSharedData( SHARED_CHOICE );
		return c instanceof String && !ELEPHANT.equals( c );
	}

	/** Tried in this order (id prefix); others after, by applicability score. */
	static final List< String > ORDER = Arrays.asList( "stardist", "cellpose" );

	/** Where frames come from for a pretrained detector: the run's viewer (a test substitutes its own). */
	public static Frame.Reader reader( final ToolContext ctx )
	{
		return RemoteTool.readers.apply( ctx );
	}

	/** Width of one frame in the trial picture, pixels. */
	private static final int FRAME_PX = 420;

	/**
	 * The detector to use for this run, after asking the user; null for ELEPHANT's adaptive tracking.
	 *
	 * @param frames the timepoints to try (a few, spread over the range)
	 */
	public static FrameDetector choose( final ToolContext ctx, final ToolRegistry registry, final DataProfile data,
			final int[] frames )
	{
		final Object earlier = ctx.getSharedData( SHARED_CHOICE );
		if ( earlier instanceof String )
		{
			final FrameDetector d = find( registry, ( String ) earlier );
			ctx.log( "  Detector chosen earlier this run: " + ( d == null ? "ELEPHANT adaptive tracking" : d.getId() ) + "." );
			return d;
		}
		final String named = System.getProperty( PROPERTY_DETECTOR, "" ).trim();
		if ( !named.isEmpty() )
			return named( ctx, registry, data, frames, named );
		final List< FrameDetector > candidates = candidates( registry, data );
		if ( candidates.isEmpty() )
			return decided( ctx, null, "no pretrained detector is available that suits this data (" + data
					+ "); configure a remote tool server in Run a Tool... to try one" );
		final Frame.Reader reader = RemoteTool.readers.apply( ctx );
		final Map< Integer, Frame > read = new LinkedHashMap<>();
		for ( final int t : frames )
		{
			final Frame f = reader.read( t );
			if ( f != null )
				read.put( t, f );
		}
		if ( read.isEmpty() )
			return decided( ctx, null, "no frame could be read for the trial" );
		if ( Boolean.TRUE.equals( ctx.getSharedData( SHARED_AUTOMATIC ) ) || !ctx.canChooseImages() )
			return imageAgreementApplies( data ) ? chooseFromImage( ctx, candidates, new ArrayList<>( read.values() ) )
					: chooseByPersistence( ctx, candidates, reader, read, seedsNeeded( data ) );
		for ( int i = 0; i < candidates.size(); i++ )
		{
			final FrameDetector d = candidates.get( i );
			final String next = i + 1 < candidates.size() ? candidates.get( i + 1 ).getId() : null;
			final List< List< FrameDetector.Detection > > found = new ArrayList<>();
			final StringBuilder counts = new StringBuilder();
			try
			{
				for ( final Frame f : read.values() )
				{
					final List< FrameDetector.Detection > dets = d.detect( f, FrameDetector.hints( ctx ) );
					found.add( dets );
					counts.append( counts.length() == 0 ? "" : ", " ).append( "t=" ).append( f.t ).append( ": " )
							.append( dets.size() );
				}
			}
			catch ( final IOException | RuntimeException e )
			{
				ctx.log( "  Detector trial: " + d.getId() + " failed (" + e.getMessage() + ") -- trying the next." );
				continue;
			}
			ctx.log( "  Detector trial: " + d.getId() + " found " + counts + " detection(s)." );
			final List< String > buttons = Arrays.asList( "Use " + d.getId() + " for the whole movie",
					next != null ? "Not good enough -- try " + next : "Not good enough -- use ELEPHANT adaptive tracking",
					"Use ELEPHANT adaptive tracking now" );
			final int k = ctx.chooseImage( String.format(
					"Pretrained detector trial %d/%d: %s, applied as it is (no training), on %d frame(s) spread over "
							+ "the movie. Magenta: its detections (%s).%n%n"
							+ "If these are good, the whole movie is detected with this model and then linked and "
							+ "corrected as usual; ELEPHANT's training is skipped. If not, the next model is tried, "
							+ "and after the last one ELEPHANT's adaptive tracking runs as before.",
					i + 1, candidates.size(), d.getId(), read.size(), counts ),
					Collections.singletonList( picture( new ArrayList<>( read.values() ), found ) ),
					Collections.singletonList( d.getId() + ": " + counts ), buttons );
			if ( k == 0 )
			{
				final int[] tps = new int[ read.size() ], n = new int[ read.size() ];
				int j = 0;
				for ( final Frame f : read.values() )
				{
					tps[ j ] = f.t;
					n[ j ] = found.get( j ).size();
					j++;
				}
				ctx.setSharedData( SHARED_ACCEPTED_COUNTS, new int[][] { tps, n } );
				return decided( ctx, d, "you accepted it in the trial" );
			}
			if ( k != 1 )
				return decided( ctx, null, k == 2 ? "you chose it in the trial" : "the trial dialog was closed" );
			ctx.log( "  Detector trial: " + d.getId() + " not accepted." );
		}
		return decided( ctx, null, "no pretrained detector was accepted" );
	}

	/**
	 * Chooses without the user: each candidate detects the trial frames, and is scored against a model-free
	 * reading of the same frames -- the regions brighter than the frame's own background
	 * ({@link #AGREEMENT_SPREADS} above the smoothed median). Recall proxy: the share of those regions with
	 * a detection on them; precision proxy: the share of detections on one. The best mean F1 of the two is
	 * chosen if it reaches {@link #AUTO_MIN_AGREEMENT}; otherwise ELEPHANT. The precision proxy cannot see a
	 * cell split in two (both halves are bright), so this mostly judges recall.
	 */
	static FrameDetector chooseFromImage( final ToolContext ctx, final List< FrameDetector > candidates,
			final List< Frame > frames )
	{
		FrameDetector best = null;
		double bestF = -1;
		int[] bestCounts = null;
		for ( final FrameDetector d : candidates )
		{
			double sumF = 0;
			final int[] counts = new int[ frames.size() ];
			final StringBuilder per = new StringBuilder();
			try
			{
				for ( int i = 0; i < frames.size(); i++ )
				{
					final Frame f = frames.get( i );
					final List< FrameDetector.Detection > dets = d.detect( f, FrameDetector.hints( ctx ) );
					counts[ i ] = dets.size();
					final double[] pr = imageAgreement( f, dets );
					final double f1 = pr[ 0 ] + pr[ 1 ] > 0 ? 2 * pr[ 0 ] * pr[ 1 ] / ( pr[ 0 ] + pr[ 1 ] ) : 0;
					sumF += f1;
					per.append( per.length() == 0 ? "" : ", " ).append( String.format( "t=%d: %d detections, "
							+ "precision~%.2f recall~%.2f", f.t, dets.size(), pr[ 0 ], pr[ 1 ] ) );
				}
			}
			catch ( final IOException | RuntimeException e )
			{
				ctx.log( "  Detector choice: " + d.getId() + " failed (" + e.getMessage() + ") -- left out." );
				continue;
			}
			final double meanF = sumF / frames.size();
			ctx.log( String.format( "  Detector choice: %s agrees with the image at F1~%.2f (%s).", d.getId(), meanF, per ) );
			if ( meanF > bestF )
			{
				bestF = meanF;
				best = d;
				bestCounts = counts;
			}
		}
		if ( best == null || bestF < AUTO_MIN_AGREEMENT )
			return decided( ctx, null, best == null ? "no pretrained detector could be run"
					: String.format( "chosen from the image: the best pretrained detector, %s, agrees with it at "
							+ "F1~%.2f, below the %.2f needed", best.getId(), bestF, AUTO_MIN_AGREEMENT ) );
		final int[] tps = new int[ frames.size() ];
		for ( int i = 0; i < tps.length; i++ )
			tps[ i ] = frames.get( i ).t;
		ctx.setSharedData( SHARED_ACCEPTED_COUNTS, new int[][] { tps, bestCounts } );
		return decided( ctx, best, String.format( "chosen from the image: it agrees with it at F1~%.2f (at least "
				+ "%.2f needed)", bestF, AUTO_MIN_AGREEMENT ) );
	}

	/**
	 * The candidate whose detections persist best between consecutive frames, if it reaches
	 * {@link #MIN_PERSISTENCE} on every trial pair; null (ELEPHANT) otherwise. Each trial frame is paired with the
	 * next one (the last with the one before). Matching is within the cell radius the pre-flight measured; without
	 * one, nothing can be checked and ELEPHANT is used.
	 */
	static FrameDetector chooseByPersistence( final ToolContext ctx, final List< FrameDetector > candidates,
			final Frame.Reader reader, final Map< Integer, Frame > read )
	{
		return chooseByPersistence( ctx, candidates, reader, read, true );
	}

	/**
	 * Whether a rejected detector may still seed ELEPHANT's training: not on fluorescence, where the review's
	 * reference blobs are the training's start and the warm-up that takes seeds does not run.
	 */
	static boolean seedsNeeded( final DataProfile data )
	{
		return !Boolean.TRUE.equals( data.fluorescenceLike );
	}

	/**
	 * As above; a detector's trial stops at the first pair that decides it cannot be used, which on CE 01 is the first
	 * pair for both candidates (0.13, 0.14): below {@link #MIN_PERSISTENCE} it cannot be chosen, and it can only still
	 * seed when {@code seedsNeeded} and something persisted (seeding needs every pair above 0). The decision is the
	 * one the full trial makes; only the pairs after the deciding one are not run.
	 */
	static FrameDetector chooseByPersistence( final ToolContext ctx, final List< FrameDetector > candidates,
			final Frame.Reader reader, final Map< Integer, Frame > read, final boolean seedsNeeded )
	{
		final Object r = ctx.getSharedData( org.mastodon.mamut.automahout.tool.CellScale.SHARED_RADIUS_UM );
		if ( !( r instanceof Number ) || Double.isNaN( ( ( Number ) r ).doubleValue() ) )
			return decided( ctx, null, "chosen by persistence: no cell size was measured for this run, so no "
					+ "pretrained detector can be checked on this data" );
		final double radius = ( ( Number ) r ).doubleValue();
		final List< Frame[] > pairs = new ArrayList<>();
		for ( final Frame f : read.values() )
		{
			Frame g = reader.read( f.t + 1 );
			if ( g == null )
				g = reader.read( f.t - 1 );
			if ( g != null )
				pairs.add( new Frame[] { f, g } );
		}
		if ( pairs.isEmpty() )
			return decided( ctx, null, "chosen by persistence: no pair of consecutive frames could be read" );
		FrameDetector best = null;
		double bestMean = -1, bestOverallMin = -1, bestSeedMean = 0;
		FrameDetector bestSeed = null;
		String bestOverall = null;
		int[] bestCounts = null;
		for ( final FrameDetector d : candidates )
		{
			double sum = 0, min = Double.POSITIVE_INFINITY;
			final int[] counts = new int[ pairs.size() ];
			final StringBuilder per = new StringBuilder();
			int run = 0;
			try
			{
				for ( int i = 0; i < pairs.size(); i++ )
				{
					run++;
					final Frame a = pairs.get( i )[ 0 ], b = pairs.get( i )[ 1 ];
					final List< FrameDetector.Detection > da = d.detect( a, FrameDetector.hints( ctx ) );
					final List< FrameDetector.Detection > db = d.detect( b, FrameDetector.hints( ctx ) );
					counts[ i ] = da.size();
					final double p = persistence( a, da, b, db, radius );
					sum += p;
					min = Math.min( min, p );
					per.append( per.length() == 0 ? "" : ", " ).append( String.format( "t=%d/%d: %d/%d detections, %.2f",
							a.t, b.t, da.size(), db.size(), p ) );
					if ( p < MIN_PERSISTENCE && ( !seedsNeeded || p <= 0 ) && i < pairs.size() - 1 )
					{
						per.append( String.format( "; the other %d pair(s) not run, this one decides it", pairs.size() - 1 - i ) );
						break;
					}
				}
			}
			catch ( final IOException | RuntimeException e )
			{
				ctx.log( "  Detector choice: " + d.getId() + " failed (" + e.getMessage() + ") -- left out." );
				continue;
			}
			final double mean = sum / run;
			ctx.log( String.format( "  Detector choice: %s keeps %.2f of its detections from one frame to the next "
					+ "(worst pair %.2f; %s).", d.getId(), mean, min, per ) );
			if ( min > bestOverallMin )
			{
				bestOverallMin = min;
				bestOverall = d.getId();
			}
			// A seed detector must find persisting detections on every trial pair: PSC's StarDist H&E (0 real cells)
			// had 0.51 on average but nothing on t=0/1; CE's Cellpose nuclei per plane (63% real seeds) 0.13 at worst.
			if ( min > 0 && mean > bestSeedMean )
			{
				bestSeedMean = mean;
				bestSeed = d;
			}
			if ( min >= MIN_PERSISTENCE && mean > bestMean )
			{
				bestMean = mean;
				best = d;
				bestCounts = counts;
			}
		}
		if ( best == null && bestSeed != null )
		{
			ctx.setSharedData( SHARED_SEED_DETECTOR, bestSeed );
			ctx.log( String.format( "  Seeds: none passed, so %s (keeps %.2f of its detections on average) seeds "
					+ "ELEPHANT's training instead of intensity maxima -- only its detections that reappear in the next "
					+ "frame.", bestSeed.getId(), bestSeedMean ) );
		}
		if ( best == null )
			return decided( ctx, null, bestOverall == null ? "no pretrained detector could be run"
					: String.format( "chosen by persistence: no pretrained detector keeps its detections on every "
							+ "pair of frames (the best worst pair, %s, %.2f; %.2f needed)", bestOverall, bestOverallMin,
							MIN_PERSISTENCE ) );
		final int[] tps = new int[ pairs.size() ];
		for ( int i = 0; i < tps.length; i++ )
			tps[ i ] = pairs.get( i )[ 0 ].t;
		ctx.setSharedData( SHARED_ACCEPTED_COUNTS, new int[][] { tps, bestCounts } );
		return decided( ctx, best, String.format( "chosen by persistence: it keeps %.2f of its detections from one "
				+ "frame to the next, and at least %.2f on every pair", bestMean, MIN_PERSISTENCE ) );
	}

	/**
	 * The share of detections in two frames that pair up one to one within {@code radius} (world units), nearest
	 * pairs first: matched / mean count. 0 when either frame has none.
	 */
	static double persistence( final Frame a, final List< FrameDetector.Detection > da, final Frame b,
			final List< FrameDetector.Detection > db, final double radius )
	{
		if ( da.isEmpty() || db.isEmpty() )
			return 0;
		return pairs( a, da, b, db, radius ).size() / ( ( da.size() + db.size() ) / 2.0 );
	}

	/**
	 * One-to-one pairs of detections in two frames within {@code radius} (world units), nearest first (greedy):
	 * {@code {i, j}} indices into {@code da} and {@code db}, in increasing distance.
	 */
	static List< int[] > pairs( final Frame a, final List< FrameDetector.Detection > da, final Frame b,
			final List< FrameDetector.Detection > db, final double radius )
	{
		final double[][] wa = new double[ da.size() ][], wb = new double[ db.size() ][];
		for ( int i = 0; i < wa.length; i++ )
			wa[ i ] = a.toWorld( da.get( i ).centroid );
		for ( int j = 0; j < wb.length; j++ )
			wb[ j ] = b.toWorld( db.get( j ).centroid );
		final List< double[] > close = new ArrayList<>(); // {distance², i, j}
		final double r2 = radius * radius;
		for ( int i = 0; i < wa.length; i++ )
			for ( int j = 0; j < wb.length; j++ )
			{
				double d2 = 0;
				for ( int k = 0; k < 3; k++ )
					d2 += ( wa[ i ][ k ] - wb[ j ][ k ] ) * ( wa[ i ][ k ] - wb[ j ][ k ] );
				if ( d2 <= r2 )
					close.add( new double[] { d2, i, j } );
			}
		close.sort( ( x, y ) -> Double.compare( x[ 0 ], y[ 0 ] ) );
		final boolean[] usedA = new boolean[ wa.length ], usedB = new boolean[ wb.length ];
		final List< int[] > out = new ArrayList<>();
		for ( final double[] c : close )
		{
			final int i = ( int ) c[ 1 ], j = ( int ) c[ 2 ];
			if ( !usedA[ i ] && !usedB[ j ] )
			{
				usedA[ i ] = usedB[ j ] = true;
				out.add( new int[] { i, j } );
			}
		}
		return out;
	}

	/**
	 * {@link ToolContext#getSharedData} key, {@link FrameDetector}: the pretrained detector whose detections seed
	 * ELEPHANT's training in this run, set when none passed {@link #chooseByPersistence} but one applies (decided
	 * with the user, 2026-10-21). Seeds that reappear in the next frame were right far more often than the
	 * intensity maxima the training otherwise starts from, measured on the development sequences (01, three
	 * frames each, against the TRA markers): CE 63% (Cellpose nuclei per plane) against 23%; PSC 78% (Cellpose
	 * cyto3) against 32%; HeLa 89% (Cellpose nuclei) against 87%.
	 */
	public static final String SHARED_SEED_DETECTOR = "detector.trial.seed_detector";

	/**
	 * Seeds from this run's seed detector ({@link #SHARED_SEED_DETECTOR}) at frame {@code t}: its detections that
	 * reappear in the next frame (the previous one at the end) within one measured cell radius, most stable first,
	 * at most {@code max}, as {@code {x, y, z, radius}} in world units (radius: the in-plane mean semi-axis). Null
	 * when there is no seed detector or nothing could be read or detected: the caller seeds as before.
	 */
	public static List< double[] > persistentSeeds( final ToolContext ctx, final int t, final int max )
	{
		final Object det = ctx.getSharedData( SHARED_SEED_DETECTOR );
		final Object r = ctx.getSharedData( org.mastodon.mamut.automahout.tool.CellScale.SHARED_RADIUS_UM );
		if ( !( det instanceof FrameDetector ) || !( r instanceof Number ) )
			return null;
		final FrameDetector d = ( FrameDetector ) det;
		final Frame.Reader reader = reader( ctx );
		final Frame a = reader.read( t );
		Frame b = reader.read( t + 1 );
		if ( b == null )
			b = reader.read( t - 1 );
		if ( a == null || b == null )
			return null;
		try
		{
			final List< FrameDetector.Detection > da = d.detect( a, FrameDetector.hints( ctx ) );
			final List< FrameDetector.Detection > db = d.detect( b, FrameDetector.hints( ctx ) );
			final List< double[] > out = new ArrayList<>();
			for ( final int[] p : pairs( a, da, b, db, ( ( Number ) r ).doubleValue() ) )
			{
				if ( out.size() >= max )
					break;
				final FrameDetector.Detection x = da.get( p[ 0 ] );
				final double[] w = a.toWorld( x.centroid );
				final double[][] cw = a.covarianceToWorld( x.covariance );
				out.add( new double[] { w[ 0 ], w[ 1 ], w[ 2 ], Math.sqrt( Math.max( 0, ( cw[ 0 ][ 0 ] + cw[ 1 ][ 1 ] ) / 2 ) ) } );
			}
			return out.isEmpty() ? null : out;
		}
		catch ( final java.io.IOException | RuntimeException e )
		{
			ctx.log( "  Seeds from " + d.getId() + " at t=" + t + " failed (" + e.getMessage() + ") -- seeding as before." );
			return null;
		}
	}

	/**
	 * {precision proxy, recall proxy} of {@code dets} against the frame's bright regions (see
	 * {@link #chooseFromImage}). A stack is read as its maximum projection.
	 */
	static double[] imageAgreement( final Frame f, final List< FrameDetector.Detection > dets )
	{
		final int w = ( int ) f.dims[ 0 ], h = ( int ) f.dims[ 1 ];
		final int[] labels = brightRegions( f );
		int regions = 0;
		for ( final int l : labels )
			regions = Math.max( regions, l );
		final boolean[] hit = new boolean[ regions + 1 ];
		int on = 0;
		for ( final FrameDetector.Detection d : dets )
		{
			final int x = ( int ) Math.round( d.centroid[ 0 ] ), y = ( int ) Math.round( d.centroid[ 1 ] );
			if ( x < 0 || y < 0 || x >= w || y >= h )
				continue;
			final int l = labels[ y * w + x ];
			if ( l > 0 )
			{
				on++;
				hit[ l ] = true;
			}
		}
		int covered = 0;
		for ( int l = 1; l <= regions; l++ )
			if ( hit[ l ] )
				covered++;
		return new double[] { dets.isEmpty() ? 0 : on / ( double ) dets.size(),
				regions == 0 ? 0 : covered / ( double ) regions };
	}

	/** Labels (1..n, row-major) of the frame's bright regions; 0 = background or a speck. */
	static int[] brightRegions( final Frame f )
	{
		final int w = ( int ) f.dims[ 0 ], h = ( int ) f.dims[ 1 ], d = ( int ) f.dims[ 2 ];
		final float[] plane = new float[ w * h ];
		Arrays.fill( plane, Float.NEGATIVE_INFINITY );
		for ( int z = 0; z < d; z++ )
			for ( int i = 0; i < plane.length; i++ )
				plane[ i ] = Math.max( plane[ i ], f.data[ z * plane.length + i ] );
		final float[] sm = gaussian( plane, w, h, 2.0 );
		final float[] sorted = sm.clone();
		Arrays.sort( sorted );
		final double med = sorted[ sorted.length / 2 ];
		final float[] dev = new float[ sm.length ];
		for ( int i = 0; i < sm.length; i++ )
			dev[ i ] = ( float ) Math.abs( sm[ i ] - med );
		Arrays.sort( dev );
		final double mad = 1.4826 * dev[ dev.length / 2 ];
		final double cut = med + AGREEMENT_SPREADS * mad;
		final int[] labels = new int[ w * h ];
		final int[] queue = new int[ w * h ];
		int next = 0;
		for ( int start = 0; start < labels.length; start++ )
		{
			if ( labels[ start ] != 0 || sm[ start ] <= cut )
				continue;
			// one region, 4-connected; numbered for now, cleared below if it is a speck
			final int id = ++next;
			int head = 0, tail = 0;
			queue[ tail++ ] = start;
			labels[ start ] = id;
			while ( head < tail )
			{
				final int p = queue[ head++ ], x = p % w, y = p / w;
				final int[] nb = { x > 0 ? p - 1 : -1, x < w - 1 ? p + 1 : -1, y > 0 ? p - w : -1, y < h - 1 ? p + w : -1 };
				for ( final int q : nb )
					if ( q >= 0 && labels[ q ] == 0 && sm[ q ] > cut )
					{
						labels[ q ] = id;
						queue[ tail++ ] = q;
					}
			}
			if ( tail < AGREEMENT_MIN_REGION_PX )
			{
				for ( int i = 0; i < tail; i++ )
					labels[ queue[ i ] ] = -1; // a speck: visited, not a region
				next--;
			}
		}
		for ( int i = 0; i < labels.length; i++ )
			if ( labels[ i ] < 0 )
				labels[ i ] = 0;
		return labels;
	}

	/** A separable Gaussian blur of a {@code w x h} plane, edges clamped. */
	private static float[] gaussian( final float[] in, final int w, final int h, final double sigma )
	{
		final int r = ( int ) Math.ceil( 3 * sigma );
		final double[] k = new double[ 2 * r + 1 ];
		double sum = 0;
		for ( int i = -r; i <= r; i++ )
			sum += k[ i + r ] = Math.exp( -i * i / ( 2 * sigma * sigma ) );
		for ( int i = 0; i < k.length; i++ )
			k[ i ] /= sum;
		final float[] tmp = new float[ in.length ], out = new float[ in.length ];
		for ( int y = 0; y < h; y++ )
			for ( int x = 0; x < w; x++ )
			{
				double v = 0;
				for ( int i = -r; i <= r; i++ )
					v += k[ i + r ] * in[ y * w + Math.max( 0, Math.min( w - 1, x + i ) ) ];
				tmp[ y * w + x ] = ( float ) v;
			}
		for ( int y = 0; y < h; y++ )
			for ( int x = 0; x < w; x++ )
			{
				double v = 0;
				for ( int i = -r; i <= r; i++ )
					v += k[ i + r ] * tmp[ Math.max( 0, Math.min( h - 1, y + i ) ) * w + x ];
				out[ y * w + x ] = ( float ) v;
			}
		return out;
	}

	/** The applicable frame detectors, StarDist first, then Cellpose, then the rest by score. */
	static List< FrameDetector > candidates( final ToolRegistry registry, final DataProfile data )
	{
		final List< FrameDetector > out = new ArrayList<>();
		for ( final AutomahoutTool t : registry.getAll() )
			if ( t instanceof FrameDetector && t.getRole() == ToolRole.DETECT && t.applicability( data ).applies )
				out.add( ( FrameDetector ) t );
		out.sort( Comparator.< FrameDetector >comparingInt( t -> rank( t.getId() ) )
				.thenComparing( t -> -t.applicability( data ).score ) );
		return out;
	}

	private static int rank( final String id )
	{
		for ( int i = 0; i < ORDER.size(); i++ )
			if ( id.startsWith( ORDER.get( i ) ) )
				return i;
		return ORDER.size();
	}

	private static FrameDetector find( final ToolRegistry registry, final String id )
	{
		final AutomahoutTool t = registry.get( id ).orElse( null );
		return t instanceof FrameDetector ? ( FrameDetector ) t : null;
	}

	/**
	 * System property: the detector this run uses, named by the person running it -- a tool id such as
	 * {@code cellpose.cpsam}, or {@code elephant} -- in place of the trial. The automatic choice judges a 2D
	 * fluorescence movie by its bright regions, a measure calibrated on one sparse culture (HeLa); on dense tissue,
	 * where touching cells read as one region and dim cells as none, it can refuse a detector a person would take.
	 */
	public static final String PROPERTY_DETECTOR = "automahout.detector";

	/**
	 * The detector named by {@link #PROPERTY_DETECTOR}. It detects the trial frames as an accepted one does, so its
	 * counts replace the agent's guess at the cell count as when a person accepts it in the dialog. A name no tool
	 * server offers stops the run: going on with another detector would not be the run that was asked for.
	 */
	static FrameDetector named( final ToolContext ctx, final ToolRegistry registry, final DataProfile data,
			final int[] frames, final String id )
	{
		final String why = "named for this run (" + PROPERTY_DETECTOR + "=" + id + ")";
		if ( ELEPHANT.equals( id ) )
			return decided( ctx, null, why );
		final FrameDetector d = find( registry, id );
		if ( d == null )
			throw new IllegalStateException( "the detector " + id + " named for this run (" + PROPERTY_DETECTOR
					+ ") is not offered by any tool server: is its server running and listed in the remote tool servers?" );
		final Applicability a = d.applicability( data );
		if ( !a.applies )
			ctx.log( "  Detector: " + id + " would not be tried on this data (" + a.reason + "); used because it was named." );
		final Frame.Reader reader = RemoteTool.readers.apply( ctx );
		final List< Integer > tps = new ArrayList<>();
		final List< Integer > counts = new ArrayList<>();
		for ( final int t : frames )
		{
			final Frame f = reader.read( t );
			if ( f == null )
				continue;
			try
			{
				counts.add( d.detect( f, FrameDetector.hints( ctx ) ).size() );
				tps.add( t );
			}
			catch ( final IOException e )
			{
				throw new IllegalStateException( id + " failed on t=" + t + ": " + e.getMessage(), e );
			}
		}
		if ( !tps.isEmpty() )
		{
			ctx.setSharedData( SHARED_ACCEPTED_COUNTS, new int[][] { tps.stream().mapToInt( Integer::intValue ).toArray(),
					counts.stream().mapToInt( Integer::intValue ).toArray() } );
			ctx.log( "  Detector: " + id + " found " + counts + " detection(s) at t=" + tps + "." );
		}
		return decided( ctx, d, why );
	}

	/**
	 * System property: {@code true} = a pretrained detector never detects; the one the choice would use only seeds
	 * ELEPHANT's training ({@link #SHARED_SEED_DETECTOR}). The benchmark's condition E (decided with the user,
	 * 2026-10-21: E is ELEPHANT's adaptive tracking started from the pretrained detector's persisting detections,
	 * not from intensity maxima).
	 */
	public static final String PROPERTY_SEEDS_ONLY = "automahout.detector.seedsOnly";

	private static FrameDetector decided( final ToolContext ctx, final FrameDetector d, final String why )
	{
		if ( d != null && Boolean.getBoolean( PROPERTY_SEEDS_ONLY ) )
		{
			ctx.setSharedData( SHARED_SEED_DETECTOR, d );
			ctx.setSharedData( SHARED_CHOICE, ELEPHANT );
			ctx.log( "  Detector: ELEPHANT adaptive tracking -- " + d.getId() + " was " + why + ", but this run uses "
					+ "pretrained detectors for seeds only (" + PROPERTY_SEEDS_ONLY + "): its persisting detections seed "
					+ "ELEPHANT's training." );
			return null;
		}
		ctx.setSharedData( SHARED_CHOICE, d == null ? ELEPHANT : d.getId() );
		ctx.log( "  Detector: " + ( d == null ? "ELEPHANT adaptive tracking" : d.getId() + " (pretrained, as it is)" )
				+ " -- " + why + "." );
		return d;
	}

	/** The frames side by side, each with its detections as magenta ellipses, drawn as Mastodon draws the spots. */
	static BufferedImage picture( final List< Frame > frames, final List< List< FrameDetector.Detection > > found )
	{
		final int gap = 6;
		final List< BufferedImage > panels = new ArrayList<>();
		int h = 0;
		for ( int i = 0; i < frames.size(); i++ )
		{
			final BufferedImage p = panel( frames.get( i ), found.get( i ) );
			panels.add( p );
			h = Math.max( h, p.getHeight() );
		}
		final BufferedImage out = new BufferedImage( panels.size() * ( FRAME_PX + gap ) - gap, h, BufferedImage.TYPE_INT_RGB );
		final Graphics2D g = out.createGraphics();
		for ( int i = 0; i < panels.size(); i++ )
			g.drawImage( panels.get( i ), i * ( FRAME_PX + gap ), 0, null );
		g.dispose();
		return out;
	}

	private static BufferedImage panel( final Frame f, final List< FrameDetector.Detection > dets )
	{
		final int w = ( int ) f.dims[ 0 ], hgt = ( int ) f.dims[ 1 ], d = ( int ) f.dims[ 2 ];
		final float[] plane = new float[ w * hgt ];
		Arrays.fill( plane, Float.NEGATIVE_INFINITY );
		for ( int z = 0; z < d; z++ ) // a stack is shown as its maximum projection
			for ( int i = 0; i < plane.length; i++ )
				plane[ i ] = Math.max( plane[ i ], f.data[ z * plane.length + i ] );
		final float[] sorted = plane.clone();
		Arrays.sort( sorted );
		final double lo = sorted[ ( int ) ( 0.01 * ( sorted.length - 1 ) ) ];
		final double span = Math.max( 1e-9, sorted[ ( int ) ( 0.995 * ( sorted.length - 1 ) ) ] - lo );
		final BufferedImage img = new BufferedImage( w, hgt, BufferedImage.TYPE_INT_RGB );
		for ( int y = 0; y < hgt; y++ )
			for ( int x = 0; x < w; x++ )
			{
				final int v = ( int ) Math.round( 255 * Math.max( 0, Math.min( 1, ( plane[ y * w + x ] - lo ) / span ) ) );
				img.setRGB( x, y, ( v << 16 ) | ( v << 8 ) | v );
			}
		final double s = ( double ) FRAME_PX / w;
		final BufferedImage out = new BufferedImage( FRAME_PX, Math.max( 1, ( int ) Math.round( hgt * s ) ),
				BufferedImage.TYPE_INT_RGB );
		final Graphics2D g = out.createGraphics();
		g.setRenderingHint( RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR );
		g.setRenderingHint( RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON );
		g.drawImage( img, 0, 0, out.getWidth(), out.getHeight(), null );
		g.setColor( Color.MAGENTA );
		g.setStroke( new BasicStroke( 1.2f ) );
		for ( final FrameDetector.Detection det : dets )
		{
			final double a = det.covariance[ 0 ][ 0 ], b = det.covariance[ 0 ][ 1 ], c = det.covariance[ 1 ][ 1 ];
			final double m = ( a + c ) / 2, r = Math.sqrt( ( a - c ) * ( a - c ) / 4 + b * b );
			// The shape matrix's eigenvalues are the squared semi-axes (see RemoteTool.parse).
			final double major = Math.sqrt( Math.max( 0, m + r ) ) * s, minor = Math.sqrt( Math.max( 0, m - r ) ) * s;
			final double cx = det.centroid[ 0 ] * s, cy = det.centroid[ 1 ] * s;
			final AffineTransform saved = g.getTransform();
			g.rotate( 0.5 * Math.atan2( 2 * b, a - c ), cx, cy );
			g.draw( new Ellipse2D.Double( cx - major, cy - minor, 2 * major, 2 * minor ) );
			g.setTransform( saved );
		}
		g.setColor( Color.WHITE );
		g.drawString( "t=" + f.t + "  (" + dets.size() + ")", 6, 16 );
		g.dispose();
		return out;
	}
}
