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
package org.mastodon.mamut.automahout.plugin;

import java.awt.Desktop;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import javax.swing.JOptionPane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks before Cmd-Q quits the application while an autonomous run is in progress.
 *
 * <p>A run can take an hour and lives in the open project; Cmd-Q pressed with the focus in one of
 * its dialogs quit the whole application and lost it. While a run is active the quit is held until
 * the user confirms. Otherwise the request goes where it went before: ImageJ's own Quit command,
 * which ImageJ 1 registers as the macOS quit handler (MacAdapter9 runs {@code IJ.doCommand("Quit")}
 * and its save prompts), or the platform's default exit when ImageJ is not loaded. The previous
 * handler cannot be read back from {@link Desktop}, so it is reproduced rather than chained.</p>
 */
public final class QuitGuard
{
	private static final Logger log = LoggerFactory.getLogger( QuitGuard.class );

	private QuitGuard()
	{}

	private static final List< BooleanSupplier > RUNS = new CopyOnWriteArrayList<>();

	private static volatile boolean installed;

	/**
	 * Registers something that can say whether a run is in progress, and installs the handler. Asked
	 * at the moment Cmd-Q is pressed rather than told when runs start and end: a run begins with a
	 * pre-flight analysis and dialog and can end on any of several paths, and a start/end count kept
	 * at the tracking step alone let Cmd-Q through during the pre-flight.
	 */
	public static void watch( final BooleanSupplier runInProgress )
	{
		RUNS.add( runInProgress );
		install();
	}

	/** Whether any watched run is in progress. */
	public static boolean runActive()
	{
		for ( final BooleanSupplier r : RUNS )
			if ( r.getAsBoolean() )
				return true;
		return false;
	}

	/**
	 * Whether a quit request should go ahead: always when no run is active, otherwise only when
	 * {@code confirm} says so.
	 */
	static boolean shouldQuit( final boolean active, final BooleanSupplier confirm )
	{
		return !active || confirm.getAsBoolean();
	}

	/**
	 * The quit-handler API arrived in Java 9 and this project compiles against Java 8, so it is reached
	 * by reflection: on Java 8 the guard is simply not installed.
	 */
	private static synchronized void install()
	{
		if ( installed )
			return;
		installed = true;
		try
		{
			if ( !Desktop.isDesktopSupported() )
				return;
			final Desktop desktop = Desktop.getDesktop();
			final Object action = Enum.valueOf( Desktop.Action.class, "APP_QUIT_HANDLER" );
			if ( !( Boolean ) Desktop.class.getMethod( "isSupported", Desktop.Action.class ).invoke( desktop, action ) )
				return;
			final Class< ? > handlerType = Class.forName( "java.awt.desktop.QuitHandler" );
			final Class< ? > responseType = Class.forName( "java.awt.desktop.QuitResponse" );
			final Method cancel = responseType.getMethod( "cancelQuit" );
			final Method perform = responseType.getMethod( "performQuit" );
			final Object handler = Proxy.newProxyInstance( QuitGuard.class.getClassLoader(), new Class< ? >[] { handlerType },
					( proxy, method, args ) -> {
						if ( !"handleQuitRequestWith".equals( method.getName() ) )
							return method.getName().equals( "hashCode" ) ? System.identityHashCode( proxy )
									: method.getName().equals( "equals" ) ? proxy == args[ 0 ]
											: method.getName().equals( "toString" ) ? "QuitGuard" : null;
						final Object response = args[ 1 ];
						if ( !shouldQuit( runActive(), QuitGuard::confirm ) )
							cancel.invoke( response );
						else if ( imageJQuit() )
							cancel.invoke( response ); // ImageJ's Quit exits the VM itself, after its own prompts
						else
							perform.invoke( response );
						return null;
					} );
			Desktop.class.getMethod( "setQuitHandler", handlerType ).invoke( desktop, handler );
		}
		catch ( final ReflectiveOperationException | RuntimeException | LinkageError e )
		{
			log.warn( "Could not install the quit guard; Cmd-Q will quit without asking.", e );
		}
	}

	private static boolean confirm()
	{
		final Object[] options = { "Keep running", "Quit anyway" };
		final int k = JOptionPane.showOptionDialog( null,
				"An Automahout run is in progress.\n\nQuitting now stops it, and everything not saved in the "
						+ "project is lost.\n\nQuit anyway?",
				"Automahout — quit while a run is in progress?", JOptionPane.DEFAULT_OPTION,
				JOptionPane.WARNING_MESSAGE, null, options, options[ 0 ] );
		return k == 1;
	}

	/** Runs ImageJ 1's Quit command, as its own quit handler does; false when ImageJ is not running. */
	private static boolean imageJQuit()
	{
		try
		{
			final Class< ? > ij = Class.forName( "ij.IJ" );
			if ( ij.getMethod( "getInstance" ).invoke( null ) == null )
				return false;
			ij.getMethod( "doCommand", String.class ).invoke( null, "Quit" );
			return true;
		}
		catch ( final ReflectiveOperationException | LinkageError e )
		{
			return false;
		}
	}
}
