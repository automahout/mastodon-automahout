package org.mastodon.mamut.automahout.elephant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.mastodon.mamut.model.Model;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;
import org.mastodon.model.tag.TagSetStructure.TagSet;

public class TagGhostTest
{
	private static Spot add( final ModelGraph g, final int t, final double x )
	{
		final Spot ref = g.vertexRef();
		return g.addVertex( ref ).init( t, new double[] { x, 0, 0 }, 5.0 );
	}

	@Test
	public void doesAFreshSpotInheritADeletedSpotsTag()
	{
		final Model model = new Model();
		final ModelGraph g = model.getGraph();
		final TagSet ts = DetectionTagLabels.ensure( model );

		final Spot old = add( g, 0, 0 );
		final int idx = old.getInternalPoolIndex();
		model.getTagSetModel().getVertexTags().set( old, DetectionTagLabels.fnTag( ts ) );
		assertEquals( "fn", DetectionTagLabels.labelOf( model, old ) );

		g.remove( old );

		final Spot fresh = add( g, 0, 100 );
		System.out.println( "  old pool index=" + idx + "  fresh pool index="
				+ fresh.getInternalPoolIndex() );
		System.out.println( "  fresh spot reads as: " + DetectionTagLabels.labelOf( model, fresh ) );
		System.out.println( "  tally: " + DetectionTagLabels.tally( model, g, new int[] { 0 } ) );
		assertNull( "a spot nobody tagged must read as untagged",
				DetectionTagLabels.labelOf( model, fresh ) );
	}

	/** ELEPHANT's own Detection tag set, in its own order, as the run log prints it. */
	private static TagSet elephantTagSet( final Model model )
	{
		final org.mastodon.model.tag.TagSetStructure tss =
				new org.mastodon.model.tag.TagSetStructure();
		tss.set( model.getTagSetModel().getTagSetStructure() );
		final TagSet ts = tss.createTagSet( "Detection" );
		for ( final String label : new String[] { "tp", "fp", "tn", "fn", "tb", "fb", "unlabeled" } )
			ts.createTag( label, 0xFF000000 );
		model.getTagSetModel().setTagSetStructure( tss );
		return org.mastodon.mamut.automahout.elephant.DetectionTagLabels.ensure( model );
	}

	/**
	 * What an UNTAGGED spot reads as against ELEPHANT's own seven-tag Detection set.
	 *
	 * <p>The run log says every fresh detection arrives as {@code fn} with {@code untagged=0}, before
	 * any correction pass has run and although nothing in this project tags a detection. The first
	 * suspicion — a tag surviving its spot and being inherited through pool-index reuse — is ruled out
	 * above. This asks the other half: whether reading an untagged spot against a set whose tags are in
	 * ELEPHANT's order returns a tag at all.</p>
	 */
	@Test
	public void whatAnUntaggedSpotReadsAsAgainstElephantsOwnTagSet()
	{
		final Model model = new Model();
		final TagSet ts = elephantTagSet( model );
		System.out.println( "  tag set: " + DetectionTagLabels.describeTagSet( model ) );
		final Spot fresh = add( model.getGraph(), 0, 0 );
		System.out.println( "  an untagged spot reads as: " + DetectionTagLabels.labelOf( model, fresh ) );
		System.out.println( "  tally: " + DetectionTagLabels.tally( model, model.getGraph(),
				new int[] { 0 } ) );
		assertNull( "a spot nobody tagged must read as untagged, whatever the tag set's order",
				DetectionTagLabels.labelOf( model, fresh ) );
		assertEquals( "and ensure() must not have added a second Detection set", 1,
				model.getTagSetModel().getTagSetStructure().getTagSets().stream()
						.filter( t -> "Detection".equals( t.getName() ) ).count() );
	}

	/**
	 * Ratchet: the claim about the detector is made at the chokepoint, from the Reason.
	 *
	 * <p>ELEPHANT's {@code GraphListenerService} tags EVERY vertex added to the graph as {@code fn} —
	 * right for the case it was written for, a PERSON adding a spot saying the detector missed a cell,
	 * and {@code fn} is a correction weighted ten times a plain positive. A listener cannot tell that
	 * apart from this project adding spots through the same graph API, and this project adds them for
	 * six different reasons.</p>
	 *
	 * <p>Fixing only the predict path was not enough, and a run said so: the per-predict tallies all
	 * read {@code fn=0} while the window's read {@code fn=31}, which is the split pass replacing 15
	 * detections with about 30. {@link org.mastodon.mamut.automahout.tool.GraphEdits.Reason} is exactly
	 * the information the decision needs, so the decision belongs where every other rule in that class
	 * is.</p>
	 */
	@Test
	public void theClaimAboutTheDetectorIsMadeAtTheChokepoint() throws java.io.IOException
	{
		final String src = new String( java.nio.file.Files.readAllBytes( java.nio.file.Paths.get(
				"src", "main", "java", "org", "mastodon", "mamut", "automahout", "tool",
				"GraphEdits.java" ) ), java.nio.charset.StandardCharsets.UTF_8 )
						.replaceAll( "(?s)/\\*.*?\\*/", "" ).replaceAll( "//[^\\n]*", "" );
		assertTrue( "every spot this project creates passes through acceptOrWithdraw",
				src.contains( "claimForDetector( ctx, ref, why )" ) );
		assertTrue( "and the claim is tp, never a correction",
				src.contains( "DetectionTagLabels.existingTag( model, DetectionTagLabels.TP )" ) );
		assertFalse( "creating the tag set here would modify the structure under a vertex write lock",
				src.contains( "DetectionTagLabels.ensure(" ) );
	}

	/**
	 * And the one claim that IS a correction keeps its label.
	 *
	 * <p>{@code tagAsMissedCell} is where this project says the model really did miss a cell — gap
	 * filling, predecessor recovery, bridging. That is a true false negative and is worth its ten
	 * times. Collapsing the two would lose the distinction the fix exists to make.</p>
	 */
	@Test
	public void aCellTheModelReallyMissedIsStillACorrection() throws java.io.IOException
	{
		final String src = new String( java.nio.file.Files.readAllBytes( java.nio.file.Paths.get(
				"src", "main", "java", "org", "mastodon", "mamut", "automahout", "autocorrect",
				"AutoCorrectOrchestrator.java" ) ), java.nio.charset.StandardCharsets.UTF_8 );
		assertTrue( "tagAsMissedCell must still apply fn",
				src.contains( "final Tag fn = DetectionTagLabels.fnTag( ts );" ) );
	}
}
