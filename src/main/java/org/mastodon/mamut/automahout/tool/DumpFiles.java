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

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Gives every benchmark dump a file of its own per launch.
 *
 * <p>The dumps append, so a second run into the same configured path mixed its rows with the
 * first's: a snapshot from a run on older classes sat next to the new one, and keeping the two apart
 * meant moving files by hand before every launch. Now the configured path is a NAME PATTERN —
 * {@code /tmp/origin-dump.tsv} is written as {@code /tmp/origin-dump-20261001-141530.tsv}.</p>
 *
 * <p>One stamp per JVM, shared by every dump, so the split dump and the origin dump of one run carry
 * the same suffix and can be paired by name. Two runs inside one launch still share a file; the
 * origin dump's snapshot numbers keep them apart.</p>
 */
public final class DumpFiles
{
	/** When this JVM loaded the class — in practice, the first dump write of the launch. */
	public static final String STAMP = new SimpleDateFormat( "yyyyMMdd-HHmmss" ).format( new Date() );

	private DumpFiles()
	{}

	/** {@code dir/name.ext} → {@code dir/name-STAMP.ext}; a name without an extension gets the suffix. */
	public static String perRun( final String configured )
	{
		return perRun( configured, STAMP );
	}

	static String perRun( final String configured, final String stamp )
	{
		if ( configured == null )
			return null;
		final int slash = Math.max( configured.lastIndexOf( '/' ), configured.lastIndexOf( '\\' ) );
		final int dot = configured.lastIndexOf( '.' );
		if ( dot <= slash + 1 )
			return configured + "-" + stamp;
		return configured.substring( 0, dot ) + "-" + stamp + configured.substring( dot );
	}
}
