package org.mastodon.mamut.automahout.vision;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;

import org.mastodon.mamut.automahout.autocorrect.AutoCorrectOrchestrator;

/**
 * Measures the VLM's crosshair verdicts against the frozen set under {@code benchmark/vlm-fp-negatives}.
 *
 * <p>This exists because the verdicts driving fp-negative synthesis could not be checked. What they
 * were doing only became visible once the crops were scored against measured ground truth: of 2444
 * crops that are genuinely empty at the crosshair the model called 17.1% background, and of the 17
 * that genuinely hold a cell it called 35.3% background — it was likelier to call a real cell empty
 * than to call empty space empty. Two batches of 74, seconds apart on statistically identical
 * candidates, returned 0 and 57 background verdicts. Any change to prompting, batch size or crop
 * rendering should be run through here rather than argued about.</p>
 *
 * <p>Not a unit test: it makes real, paid API calls and needs backend credentials, so it is a
 * {@code main} nobody runs by accident. {@link VlmBenchmarkSetTest} keeps the set itself honest in CI.</p>
 *
 * <pre>
 * mvn -o test-compile
 * mvn -o exec:java -Dexec.classpathScope=test \
 *     -Dexec.mainClass=org.mastodon.mamut.automahout.vision.VlmFpBenchmark \
 *     -Dexec.args="bedrock jp.anthropic.claude-haiku-4-5-20251001-v1:0 ap-northeast-1 1,5,10,30,60"
 * </pre>
 */
public class VlmFpBenchmark
{
	/** One labelled crop. */
	private static final class Case
	{
		final String image;

		final boolean cell;

		final String base64;

		Case( final String image, final boolean cell, final String base64 )
		{
			this.image = image;
			this.cell = cell;
			this.base64 = base64;
		}
	}

	static Path benchmarkDir()
	{
		return Paths.get( "benchmark", "vlm-fp-negatives" );
	}

	/** Reads {@code labels.tsv} and the crops it names. */
	static List< Case > load() throws IOException
	{
		final List< Case > out = new ArrayList<>();
		for ( final String line : Files.readAllLines( benchmarkDir().resolve( "labels.tsv" ),
				StandardCharsets.UTF_8 ) )
		{
			if ( line.startsWith( "#" ) || line.trim().isEmpty() )
				continue;
			final String[] f = line.split( "\t" );
			final byte[] png = Files.readAllBytes( benchmarkDir().resolve( "images" ).resolve( f[ 0 ] ) );
			out.add( new Case( f[ 0 ], "cell".equals( f[ 1 ] ),
					Base64.getEncoder().encodeToString( png ) ) );
		}
		return out;
	}

	/**
	 * Runs the set in batches of {@code batchSize} and returns
	 * {@code [emptyCalledEmpty, emptyTotal, cellCalledCell, cellTotal, unparsed]}.
	 *
	 * <p>Shuffled with a fixed seed so every batch size sees the same ordering of the same cases, and
	 * a difference between two runs is the batching rather than which crops happened to travel
	 * together.</p>
	 */
	static int[] run( final VlmBackend backend, final String model, final List< Case > cases,
			final int batchSize ) throws IOException
	{
		final List< Case > shuffled = new ArrayList<>( cases );
		Collections.shuffle( shuffled, new Random( 20260925L ) );
		final int[] tally = new int[ 5 ];
		for ( int from = 0; from < shuffled.size(); from += batchSize )
		{
			final List< Case > batch = shuffled.subList( from,
					Math.min( shuffled.size(), from + batchSize ) );
			final List< String > images = new ArrayList<>();
			for ( final Case c : batch )
				images.add( c.base64 );
			// The production prompt, not a copy of it: a benchmark that measures its own paraphrase
			// measures nothing.
			final String prompt =
					AutoCorrectOrchestrator.buildMultiPlaneVerdictPrompt( batch.size(), 1 ).toString();
			final String reply = backend.analyze( model, prompt, images );

			final boolean[] answered = new boolean[ batch.size() ];
			final Matcher m = AutoCorrectOrchestrator.VLM_VERDICT_LINE.matcher( reply );
			while ( m.find() )
			{
				final int idx = Integer.parseInt( m.group( 1 ) ) - 1;
				if ( idx < 0 || idx >= batch.size() )
					continue;
				answered[ idx ] = true;
				final boolean saidEmpty = "fp".equalsIgnoreCase( m.group( 2 ) );
				final Case c = batch.get( idx );
				if ( c.cell )
				{
					tally[ 3 ]++;
					if ( !saidEmpty )
						tally[ 2 ]++;
				}
				else
				{
					tally[ 1 ]++;
					if ( saidEmpty )
						tally[ 0 ]++;
				}
			}
			for ( final boolean a : answered )
				if ( !a )
					tally[ 4 ]++;
		}
		return tally;
	}

	public static void main( final String[] args ) throws Exception
	{
		final String backendName = args.length > 0 ? args[ 0 ] : "bedrock";
		final String model = args.length > 1 ? args[ 1 ] : "jp.anthropic.claude-haiku-4-5-20251001-v1:0";
		final String region = args.length > 2 ? args[ 2 ] : "ap-northeast-1";
		final String sizes = args.length > 3 ? args[ 3 ] : "1,5,10,30,60";

		final VlmBackend backend = "bedrock".equals( backendName )
				? new BedrockBackend( region )
				: new OllamaBackend( region );
		final List< Case > cases = load();
		long cells = 0;
		for ( final Case c : cases )
			if ( c.cell )
				cells++;
		System.out.printf( "%d case(s): %d with a cell at the crosshair, %d empty.%n",
				cases.size(), cells, cases.size() - cells );
		System.out.println( "model: " + model + "  backend: " + backendName );
		System.out.println();
		System.out.println( "batch   empty->empty    cell->cell     both    unparsed" );
		System.out.println( "-----   ------------   ------------   ------   --------" );
		for ( final String s : sizes.split( "," ) )
		{
			final int batch = Integer.parseInt( s.trim() );
			final int[] t = run( backend, model, cases, batch );
			final double emptyRecall = t[ 1 ] > 0 ? 100.0 * t[ 0 ] / t[ 1 ] : Double.NaN;
			final double cellRecall = t[ 3 ] > 0 ? 100.0 * t[ 2 ] / t[ 3 ] : Double.NaN;
			final int right = t[ 0 ] + t[ 2 ], total = t[ 1 ] + t[ 3 ];
			System.out.printf( "%5d   %4.0f%% (%2d/%2d)   %4.0f%% (%2d/%2d)   %4.0f%%   %8d%n",
					batch, emptyRecall, t[ 0 ], t[ 1 ], cellRecall, t[ 2 ], t[ 3 ],
					total > 0 ? 100.0 * right / total : Double.NaN, t[ 4 ] );
		}
		System.out.println();
		System.out.println( "empty->empty is the one that decides the fp-negative supply: it is the "
				+ "fraction of genuinely empty" );
		System.out.println( "positions the model is willing to hand over as training negatives. "
				+ "Production measured 17% on" );
		System.out.println( "2444 crops before any of this was changed. cell->cell is the safety "
				+ "side: a cell called empty is" );
		System.out.println( "taught to the detector as background." );
	}
}
