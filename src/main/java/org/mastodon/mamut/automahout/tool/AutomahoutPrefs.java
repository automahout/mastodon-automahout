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

import java.util.prefs.Preferences;

/**
 * Where Automahout keeps its per-user settings. Normally Java's per-user node for the class's package; with the
 * system property {@value #PROPERTY_ROOT} set, a node under that path instead -- so a benchmark run reads the
 * code's defaults, not whatever the user last tuned in the GUI, and leaves the user's settings alone. Must be
 * set before the first Automahout class loads (on the command line).
 */
public final class AutomahoutPrefs
{
	private AutomahoutPrefs()
	{}

	public static final String PROPERTY_ROOT = "automahout.prefs.root";

	public static Preferences node( final Class< ? > c )
	{
		final String root = System.getProperty( PROPERTY_ROOT );
		if ( root == null || root.trim().isEmpty() )
			return Preferences.userNodeForPackage( c );
		return Preferences.userRoot().node( root.trim() + "/" + c.getPackage().getName().replace( '.', '/' ) );
	}

	/** Whether settings are isolated (a benchmark run). */
	public static boolean isolated()
	{
		final String root = System.getProperty( PROPERTY_ROOT );
		return root != null && !root.trim().isEmpty();
	}

	/**
	 * The environment variable that sets a language-model setting of a benchmark run (BenchmarkRunner.LLM_KEYS)
	 * over the user's saved settings: {@code vlmAwsRegion} -> {@code AUTOMAHOUT_VLM_AWS_REGION}. A command-line
	 * run (scripts/reproduce) has no settings saved from Mastodon's dialog; the environment, unlike a {@code -D}
	 * property, keeps an API key off the process list.
	 */
	public static String llmEnvName( final String key )
	{
		return "AUTOMAHOUT_" + key.replaceAll( "([a-z])([A-Z])", "$1_$2" ).toUpperCase( java.util.Locale.ROOT );
	}
}
