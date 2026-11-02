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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Keeps {@link GraphEdits} the only place that deletes from the graph.
 *
 * <p>This is a source-text test rather than a behavioural one because the rule it protects is about
 * where code may be written, and that is exactly the rule this codebase kept losing: a safety guard
 * was added to some of the ~40 mutation sites and not to the others, silently, and each recurrence
 * cost a real GUI run on real data to notice. A compiler cannot express "no other file may call
 * this", so the test reads the sources.</p>
 *
 * <p>It asserts on deletion only. Creation ({@code addVertex}/{@code addEdge}) is migrating
 * separately, because a refused creation has to be handled at the call site — {@code addSpot}
 * returns {@code null} where the old code created unconditionally — and that is a judgement per site
 * rather than a substitution. The count of unmigrated creation sites is asserted as a ratchet below,
 * so it can fall but not rise.</p>
 */
public class GraphEditsChokepointTest
{
	private static final Path SRC = Paths.get( "src", "main", "java" );

	/**
	 * Files permitted to call {@code graph.remove} directly, each for a reason that cannot be met by
	 * routing through the facade.
	 */
	private static final List< String > DELETION_EXEMPT = Arrays.asList(
			// The facade itself.
			"GraphEdits.java",
			// Implements the protection guard the facade consults; routing it back through the facade
			// would be circular.
			"ProtectedSpots.java",
			// Implements the cell-count floor the facade will consult; same circularity.
			"ExpectedCellCount.java",
			// Withdraws a detection it has just created when it lands on a protected spot — the
			// create-then-check pattern that GraphEdits.addSpot replaces, migrating with creation.
			"ElephantIntegration.java" );

	private static final Pattern DELETE = Pattern.compile( "\\bgraph\\s*\\.\\s*remove\\s*\\(" );

	private static final Pattern CREATE =
			Pattern.compile( "\\bgraph\\s*\\.\\s*(?:addVertex|addEdge)\\s*\\(" );

	/**
	 * Creation sites still written directly: none. Kept as a named zero rather than folded into the
	 * deletion test because the two carry different exemptions — creation has only the benchmark's truth
	 * reader, which builds a model of its own.
	 */
	private static final int UNMIGRATED_CREATION_SITES = 0;

	@Test
	public void deletionGoesThroughTheFacade() throws IOException
	{
		final List< String > offenders = scan( DELETE, DELETION_EXEMPT );
		assertEquals( "graph.remove outside GraphEdits: " + offenders, 0, offenders.size() );
	}

	@Test
	public void creationGoesThroughTheFacade() throws IOException
	{
		final List< String > sites = scan( CREATE, Arrays.asList( "GraphEdits.java",
				// Builds a new, private Model from CTC truth files for the benchmark; no run, no viewer
				// and no user edits ever touch it, so none of the facade's guards has anything to guard.
				"CtcGroundTruth.java" ) );
		assertEquals( "graph.addVertex/addEdge outside GraphEdits: " + sites,
				UNMIGRATED_CREATION_SITES, sites.size() );
	}

	private static List< String > scan( final Pattern pattern, final List< String > exempt )
			throws IOException
	{
		final List< String > hits = new ArrayList<>();
		try ( Stream< Path > files = Files.walk( SRC ) )
		{
			final List< Path > javaFiles = files
					.filter( p -> p.toString().endsWith( ".java" ) )
					.filter( p -> !exempt.contains( p.getFileName().toString() ) )
					.collect( Collectors.toList() );
			for ( final Path p : javaFiles )
			{
				final String[] lines =
						new String( Files.readAllBytes( p ), StandardCharsets.UTF_8 ).split( "\n", -1 );
				for ( int i = 0; i < lines.length; i++ )
				{
					final String line = stripComment( lines[ i ] );
					final Matcher m = pattern.matcher( line );
					if ( m.find() )
						hits.add( p.getFileName() + ":" + ( i + 1 ) );
				}
			}
		}
		return hits;
	}

	/** Drops a trailing line comment so prose mentioning the call does not count as a call. */
	private static String stripComment( final String line )
	{
		final int i = line.indexOf( "//" );
		final String code = i >= 0 ? line.substring( 0, i ) : line;
		return code.trim().startsWith( "*" ) ? "" : code;
	}

	/**
	 * Detector output and track surgery must not share one reason.
	 *
	 * <p>They did, and the end-of-window edit tally — the one line whose job is to make a window's
	 * spot accounting attributable — printed {@code TOPOLOGY spots -68/+6095} for a window that
	 * committed about 78 spots/tp: five predicts' worth of detections summed with the few spots
	 * correction actually added. A reason that counts both cannot answer what correction did.</p>
	 */
	@Test
	public void theDetectorDoesNotFileItsOutputAsTrackSurgery() throws IOException
	{
		final String predict = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org",
				"mastodon", "mamut", "automahout", "elephant", "ElephantIntegration.java" ) ),
				StandardCharsets.UTF_8 );
		assertTrue( "the predict path must add its detections as Reason.DETECTION",
				predict.contains( "GraphEdits.Reason.DETECTION" ) );
	}

	@Test
	public void detectionIsAnAdditionOnlyReason() throws IOException
	{
		// It says "the model produced this", which is never a reason to delete anything. A removal
		// filed under it would also be exempt from the cell-count floor without arguing for it.
		final List< String > offenders = new ArrayList<>();
		try ( Stream< Path > files = Files.walk( Paths.get( "src", "main", "java" ) ) )
		{
			for ( final Path f : ( Iterable< Path > ) files
					.filter( p -> p.toString().endsWith( ".java" ) )::iterator )
			{
				final String text = new String( Files.readAllBytes( f ), StandardCharsets.UTF_8 );
				final Matcher m = Pattern.compile(
						"GraphEdits\\.remove(?:Spot|Link)\\s*\\([^;]*?Reason\\.DETECTION" ).matcher( text );
				while ( m.find() )
					offenders.add( f.getFileName().toString() );
			}
		}
		assertEquals( "Reason.DETECTION may only add: " + offenders, 0, offenders.size() );
	}

	/**
	 * A refused detection is evidence for a training negative, and the chain's refusals are the volume.
	 *
	 * <p>{@link CorrectionEvidence} existed, was drained by the fp synthesis, and was fed by exactly one
	 * reason: {@code TOPOLOGY}, which is track surgery in [5c/6]. The cleanup chain in [3/6] makes the
	 * same judgement dozens of times per frame and every one of them was thrown away, so the only
	 * negatives the detector ever trained on were synthesized at offsets from real cells -- places it
	 * had never fired. The detector meanwhile produced 5.4x more detections than there are cells.</p>
	 */
	@Test
	public void aRefusalThatSaysThereIsNoCellHereIsEvidence()
	{
		assertTrue( GraphEdits.saysThereIsNoCellHere( GraphEdits.Reason.NOT_A_CELL ) );
		assertTrue( GraphEdits.saysThereIsNoCellHere( GraphEdits.Reason.PROBABLY_NOT_A_CELL ) );
		assertTrue( GraphEdits.saysThereIsNoCellHere( GraphEdits.Reason.TOPOLOGY ) );
	}

	/**
	 * A duplicate sits ON a real cell, so it must never become a negative.
	 *
	 * <p>Teaching that position as background teaches the detector that a cell is not one, and
	 * ELEPHANT weights a negative at ten times a positive. This is the one entry in the enum where
	 * getting the membership wrong is worse than having no negatives at all.</p>
	 */
	@Test
	public void aDuplicateIsNeverEvidenceThatThereIsNoCell()
	{
		assertFalse( GraphEdits.saysThereIsNoCellHere( GraphEdits.Reason.DUPLICATE ) );
		assertFalse( GraphEdits.saysThereIsNoCellHere( GraphEdits.Reason.SPLIT ) );
		assertFalse( GraphEdits.saysThereIsNoCellHere( GraphEdits.Reason.BULK ) );
		assertFalse( GraphEdits.saysThereIsNoCellHere( GraphEdits.Reason.TRAINING ) );
		assertFalse( GraphEdits.saysThereIsNoCellHere( GraphEdits.Reason.USER ) );
		assertFalse( GraphEdits.saysThereIsNoCellHere( GraphEdits.Reason.DETECTION ) );
	}

	/** Ratchet: the rule is applied at the chokepoint, not merely declared. */
	@Test
	public void theEvidenceIsRecordedAtTheChokepoint() throws IOException
	{
		final String src = new String( Files.readAllBytes( Paths.get( "src", "main", "java", "org",
				"mastodon", "mamut", "automahout", "tool", "GraphEdits.java" ) ),
				StandardCharsets.UTF_8 );
		assertTrue( "every removal passes through removeSpot; recording anywhere else misses the "
				+ "passes added later",
				src.contains( "if ( saysThereIsNoCellHere( why ) && ctx != null )" ) );
		assertTrue( "and the position must be read BEFORE the spot is removed",
				src.contains( "CorrectionEvidence.spurious( ctx, t, pos," ) );
	}

	/**
	 * The split pass's output is a guess, not a claim that the model missed a cell.
	 *
	 * <p>It separates its two classes at an AUC of 0.663. That is not confidence worth ten times a
	 * plain positive, and teaching its mistakes at that weight amplifies the pass with the least
	 * evidence behind it. Same for the covering spot a duplicate merge creates: the model FOUND that
	 * cell, this is a reshaping.</p>
	 */
	@Test
	public void aGuessIsNotACorrection()
	{
		assertTrue( GraphEdits.claimsTheDetectorWasRight( GraphEdits.Reason.DETECTION ) );
		assertTrue( GraphEdits.claimsTheDetectorWasRight( GraphEdits.Reason.SPLIT ) );
		assertTrue( GraphEdits.claimsTheDetectorWasRight( GraphEdits.Reason.DUPLICATE ) );
	}

	/**
	 * And the three reasons that really are corrections keep their own label.
	 *
	 * <p>{@code TOPOLOGY} speaks through {@code tagAsMissedCell} and its claim is corroborated by TIME —
	 * a cell exists before and after the gap — which is far stronger evidence than any per-frame test.
	 * {@code TRAINING} tags {@code fp} itself. {@code USER} is the listener's own case: a person adding
	 * a spot IS saying the detector missed one, and that is exactly right.</p>
	 */
	@Test
	public void aRealCorrectionKeepsItsOwnLabel()
	{
		assertFalse( "tagAsMissedCell says fn, and means it",
				GraphEdits.claimsTheDetectorWasRight( GraphEdits.Reason.TOPOLOGY ) );
		assertFalse( "the fp synthesis tags its own",
				GraphEdits.claimsTheDetectorWasRight( GraphEdits.Reason.TRAINING ) );
		assertFalse( "a person adding a spot is the case the listener was written for",
				GraphEdits.claimsTheDetectorWasRight( GraphEdits.Reason.USER ) );
	}

	/**
	 * A training annotation that asserts a cell is a POSITIVE, not a correction.
	 *
	 * <p>One reason once covered both a synthesized {@code fp} negative and a restored GT position,
	 * so {@link GraphEdits#claimForDetector} had nothing to tell them apart with and left the graph
	 * listener's {@code fn} on both. A GT-trained window's payload read {@code fn=440, tp=0}, and
	 * ELEPHANT's own per-frame tally confirmed it on the wire: {@code ('tp', 0), ('fn', 56)}. Every
	 * cell that had been confirmed went to the model as one it had MISSED, at ten times the weight of
	 * a plain positive.</p>
	 */
	@Test
	public void aTrainingAnnotationThatAssertsACellIsAPositive()
	{
		assertTrue( "a seed and a restored GT position say a cell IS here",
				GraphEdits.claimsTheDetectorWasRight( GraphEdits.Reason.TRAINING_POSITIVE ) );
		assertFalse( "and a synthesized negative still says the opposite",
				GraphEdits.claimsTheDetectorWasRight( GraphEdits.Reason.TRAINING ) );
	}

}