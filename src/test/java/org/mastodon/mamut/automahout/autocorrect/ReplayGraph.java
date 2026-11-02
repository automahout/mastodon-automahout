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
package org.mastodon.mamut.automahout.autocorrect;

import java.util.ArrayList;
import java.util.List;

import org.mastodon.mamut.automahout.tool.GraphWatch;
import org.mastodon.mamut.automahout.tool.ProtectedSpots;
import org.mastodon.mamut.automahout.tool.ToolContext;
import org.mastodon.mamut.model.Link;
import org.mastodon.mamut.model.Model;
import org.mastodon.mamut.model.ModelGraph;
import org.mastodon.mamut.model.Spot;
import org.mastodon.model.tag.TagSetStructure;
import org.mastodon.model.tag.TagSetStructure.Tag;
import org.mastodon.model.tag.TagSetStructure.TagSet;

/**
 * Builds a real {@link ModelGraph} for a test, and prints one back deterministically.
 *
 * <p>Until this existed, nothing in the test suite constructed a graph at all: every test operated on
 * extracted arithmetic or on pre-built {@code Snapshot} arrays, and the ~40 sites that actually
 * mutate the graph were reachable only through a GUI run on real data. That is why the same class of
 * bug — a guard applied to some deletion sites and not others — kept being found by the user rather
 * than by the build.</p>
 *
 * <p>The point of {@link #canonical()} is comparison rather than inspection. A correction pass is
 * correct here if it leaves the graph it left yesterday, so the fixture's value is that a refactor
 * which changes behaviour produces a diff. Spots are printed sorted by timepoint then position, and
 * edges by their endpoints' printed form, so the output does not depend on pool ordering — which is
 * not stable across runs once spots have been removed and their slots reused.</p>
 */
public final class ReplayGraph
{
	private static final String TAG_SET = "Status";

	private static final String TAG_LABEL = "Completed";

	private final Model model = new Model();

	private final List< Spot > spots = new ArrayList<>();

	/** A spot at {@code t}, at {@code (x,y,z)}, with radius {@code r}. */
	public Spot spot( final int t, final double x, final double y, final double z, final double r )
	{
		final Spot ref = model.getGraph().vertexRef();
		// The fixture is the graph as it stood: added as the run's own, so GraphWatch does not read it
		// as an edit in the viewer. A test that means a viewer edit makes it on the graph directly.
		final Spot s = GraphWatch.asRunsOwn( () -> model.getGraph().addVertex( ref ).init( t, new double[] { x, y, z }, r ) );
		spots.add( s );
		return s;
	}

	/** A spot at {@code t} on the x axis, radius 5 — enough for tests about topology rather than shape. */
	public Spot spot( final int t, final double x )
	{
		return spot( t, x, 0, 0, 5 );
	}

	/** An edge from {@code from} to {@code to}. */
	public Link link( final Spot from, final Spot to )
	{
		final Link ref = model.getGraph().edgeRef();
		return GraphWatch.asRunsOwn( () -> model.getGraph().addEdge( from, to, ref ).init() );
	}

	/**
	 * A straight track: one spot per timepoint from {@code t0}, each linked to the next, moving by
	 * {@code dx} per frame. Returns the spots in time order.
	 */
	public List< Spot > track( final int t0, final int length, final double x0, final double dx )
	{
		final List< Spot > out = new ArrayList<>();
		Spot prev = null;
		for ( int i = 0; i < length; i++ )
		{
			final Spot s = spot( t0 + i, x0 + i * dx );
			if ( prev != null )
				link( prev, s );
			out.add( s );
			prev = s;
		}
		return out;
	}

	/**
	 * Marks {@code s} protected, the way a user confirming a cell does, and points the context at the
	 * tag set that says so. Returns a context already configured to honour it.
	 */
	public ToolContext protect( final Spot... toProtect )
	{
		final TagSetStructure tss = new TagSetStructure();
		tss.set( model.getTagSetModel().getTagSetStructure() );
		final TagSet ts = tss.createTagSet( TAG_SET );
		ts.createTag( TAG_LABEL, 0xff00ff00 );
		model.getTagSetModel().setTagSetStructure( tss );
		final TagSet live = model.getTagSetModel().getTagSetStructure().getTagSets().stream()
				.filter( x -> TAG_SET.equals( x.getName() ) ).findFirst().orElseThrow( IllegalStateException::new );
		final Tag tag = live.getTags().stream()
				.filter( x -> TAG_LABEL.equals( x.label() ) ).findFirst().orElseThrow( IllegalStateException::new );
		for ( final Spot s : toProtect )
			model.getTagSetModel().getVertexTags().set( s, tag );
		final ToolContext ctx = context();
		ctx.setSharedData( ProtectedSpots.SHARED_CONFIG_KEY, ProtectedSpots.Config.of( TAG_SET, TAG_LABEL ) );
		return ctx;
	}

	public Model model()
	{
		return model;
	}

	public ModelGraph graph()
	{
		return model.getGraph();
	}

	/** A context with no project, no viewer and no image data. */
	public ToolContext context()
	{
		return ToolContext.forReplay( model );
	}

	public int spotCount()
	{
		return model.getGraph().vertices().size();
	}

	public int linkCount()
	{
		return model.getGraph().edges().size();
	}

	/**
	 * The graph as a stable, sorted string: one {@code t@x,y,z} line per spot, then one
	 * {@code t@x,y,z -> t@x,y,z} line per edge, each block sorted.
	 */
	public String canonical()
	{
		final List< String > verts = new ArrayList<>();
		for ( final Spot s : model.getGraph().vertices() )
			verts.add( print( s ) );
		final List< String > edges = new ArrayList<>();
		final Spot src = model.getGraph().vertexRef();
		final Spot tgt = model.getGraph().vertexRef();
		for ( final Link e : model.getGraph().edges() )
			edges.add( print( e.getSource( src ) ) + " -> " + print( e.getTarget( tgt ) ) );
		verts.sort( null );
		edges.sort( null );
		final StringBuilder sb = new StringBuilder();
		for ( final String v : verts )
			sb.append( v ).append( '\n' );
		for ( final String e : edges )
			sb.append( e ).append( '\n' );
		return sb.toString();
	}

	private static String print( final Spot s )
	{
		return String.format( "%d@%.1f,%.1f,%.1f", s.getTimepoint(), s.getDoublePosition( 0 ),
				s.getDoublePosition( 1 ), s.getDoublePosition( 2 ) );
	}
}
