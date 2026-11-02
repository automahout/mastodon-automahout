package org.mastodon.mamut.automahout.tool;

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
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * A ratchet on the bug this project keeps rewriting: judging ONE timepoint or ONE window against a
 * bound built for the WHOLE MOVIE.
 *
 * <p>There are three "expected cell counts" and they are not interchangeable — the count stated for
 * the first timepoint, {@code expectedAt(t)} interpolated between the user's two ends, and the
 * run-wide ceiling built on the population's largest end. Fluo-N2DL-HeLA_01 was stated to grow 26 to
 * 300 cells; the run-wide ceiling is therefore 600, and window 1 detected 885 spots at t=0 with four
 * separate guards comparing it against that 600 and finding it acceptable. Each was found and fixed
 * on a different day.</p>
 *
 * <p>{@link ExpectedCellCount#ceilingAt} answers the per-timepoint question once. This test counts
 * the arithmetic that bypasses it, so a new one cannot be added quietly. Lowering the number below
 * is always welcome; raising it needs a reason in the commit message.</p>
 */
public class ExpectedCountChokepointTest
{
	/**
	 * Uses of {@code coldStartCeiling} in arithmetic or a comparison, rather than as something passed
	 * along. Each remaining one is a judgement made against the whole movie's bound.
	 *
	 * <p>The four survivors at the time of writing all genuinely ask a run-wide question: the value's
	 * own declaration, the GT-reliability sanity bound (an UPPER bound where the loose run-wide number
	 * is the conservative direction), and the two lines inside {@code windowExplosionCeiling} that
	 * define the run-wide ceiling in the first place. Anything judging a single timepoint or window
	 * against it has been routed through {@code ceilingAt}.</p>
	 */
	private static final int MAX_RUN_WIDE_COMPARISONS = 4;

	private static final Pattern USE = Pattern.compile(
			"coldStartCeiling\\s*[*/<>]|[<>=]\\s*coldStartCeiling" );

	private static List< String > offenders() throws IOException
	{
		final Path root = Paths.get( "src", "main", "java" );
		final List< String > out = new ArrayList<>();
		try ( Stream< Path > files = Files.walk( root ) )
		{
			for ( final Path f : ( Iterable< Path > ) files.filter( p -> p.toString().endsWith( ".java" ) )::iterator )
			{
				final List< String > lines = Files.readAllLines( f, StandardCharsets.UTF_8 );
				for ( int i = 0; i < lines.size(); i++ )
				{
					final String line = lines.get( i );
					if ( line.trim().startsWith( "*" ) || line.trim().startsWith( "//" ) )
						continue; // a comment naming the trap is not the trap
					final Matcher m = USE.matcher( line );
					if ( m.find() )
						out.add( f.getFileName() + ":" + ( i + 1 ) + "  " + line.trim() );
				}
			}
		}
		return out;
	}

	@Test
	public void noNewJudgementIsMadeAgainstTheWholeMoviesBound() throws IOException
	{
		final List< String > found = offenders();
		assertTrue( found.size() <= MAX_RUN_WIDE_COMPARISONS,
				"a run-wide ceiling is being compared against in " + found.size() + " place(s), up from "
						+ MAX_RUN_WIDE_COMPARISONS + ". Judging one timepoint or one window against the "
						+ "whole movie's bound is the bug this ratchet exists for — use "
						+ "ExpectedCellCount.ceilingAt / ceilingOver, or lower the limit if you removed "
						+ "one:\n  " + String.join( "\n  ", found ) );
	}

	@Test
	public void thePerTimepointAnswerIsTighterAtTheStartOfAGrowingMovie()
	{
		// The property the whole class exists for, stated as arithmetic so it cannot rot: whatever the
		// run-wide bound is, the answer for an early timepoint of a growing population is smaller.
		assertTrue( ExpectedCellCount.statedCeiling( 26 ) < ExpectedCellCount.statedCeiling( 300 ) );
	}

	@Test
	public void aMeasuredPriorIsNeverTightenedByAnEstimate()
	{
		// A prior measured on this data outranks a number the user typed. ceilingAt takes the larger.
		assertTrue( ExpectedCellCount.ceilingAt( null, 0, 900.0, 600.0 ) >= 900.0 );
	}
}
