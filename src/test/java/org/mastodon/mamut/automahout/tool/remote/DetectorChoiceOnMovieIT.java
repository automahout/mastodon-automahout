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

import static org.junit.Assume.assumeTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.mastodon.mamut.automahout.autocorrect.ReplayGraph;
import org.mastodon.mamut.automahout.tool.ToolContext;

import bdv.BigDataViewer;
import bdv.spimdata.SpimDataMinimal;
import bdv.spimdata.XmlIoSpimDataMinimal;
import bdv.viewer.SourceAndConverter;

/**
 * The automatic detector choice ({@link DetectorTrial#chooseFromImage}) on frames of any BigDataViewer movie, against
 * real tool servers: prints each detector's precision and recall proxies and the choice. A movie without ground truth
 * is judged by looking at what the detectors found; this shows what the choice made of it. Opt-in:
 *
 * <pre>mvn test -Dtest=DetectorChoiceOnMovieIT -Dautomahout.it.xml=movie.xml
 *     -Dautomahout.it.servers=http://127.0.0.1:8765,http://127.0.0.1:8767 [-Dautomahout.it.frames=0,4,9]</pre>
 */
public class DetectorChoiceOnMovieIT
{
	@Test
	public void theImageAgreementOnAMovie() throws Exception
	{
		final String xml = System.getProperty( "automahout.it.xml" );
		final String servers = System.getProperty( "automahout.it.servers" );
		assumeTrue( "set -Dautomahout.it.xml and -Dautomahout.it.servers", xml != null && servers != null );
		final SpimDataMinimal data = new XmlIoSpimDataMinimal().load( xml );
		final List< SourceAndConverter< ? > > sources = new ArrayList<>();
		BigDataViewer.initSetups( data, new ArrayList<>(), sources );
		final List< Frame > frames = new ArrayList<>();
		for ( final String t : System.getProperty( "automahout.it.frames", "0" ).split( "," ) )
			frames.add( BdvFrameReader.read( sources.get( 0 ).getSpimSource(), Integer.parseInt( t.trim() ) ) );
		final List< FrameDetector > candidates = new ArrayList<>();
		for ( final String url : servers.split( "," ) )
			for ( final RemoteTool r : new RemoteToolServer( url.trim() ).tools( s -> {} ) )
				if ( !r.getId().endsWith( "3d" ) )
					candidates.add( r );
		final ToolContext ctx = new ReplayGraph().context();
		final List< String > log = new ArrayList<>();
		ctx.setLogCallback( log::add );
		DetectorTrial.chooseFromImage( ctx, candidates, frames );
		log.forEach( System.out::println );
	}
}
