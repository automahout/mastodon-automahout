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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mastodon.app.ui.ViewMenuBuilder.MenuItem;
import org.mastodon.mamut.KeyConfigScopes;
import org.mastodon.mamut.MamutMenuBuilder;
import org.mastodon.mamut.ProjectModel;
import org.mastodon.mamut.plugin.MamutPlugin;
import org.mastodon.ui.keymap.KeyConfigContexts;
import org.scijava.plugin.Plugin;
import org.scijava.ui.behaviour.io.gui.CommandDescriptionProvider;
import org.scijava.ui.behaviour.io.gui.CommandDescriptions;
import org.scijava.ui.behaviour.util.Actions;

/**
 * SciJava plugin entry point for Automahout: the menu items under <em>Plugins &gt; Automahout</em>.
 *
 * <ul>
 *   <li><b>Run Autonomous Tracking...</b> ({@value #ACTION_AUTONOMOUS_TRACKING_NAME}) -- the tracking pipeline.</li>
 *   <li><b>Stop Automahout</b> ({@value #ACTION_STOP_NAME}) -- cancels a running tracking.</li>
 *   <li><b>Automahout Settings...</b> and <b>Run a Tool...</b>.</li>
 *   <li><em>Advanced</em>: the lineage-constraint and GT-store tools the pipeline's log messages point to.</li>
 * </ul>
 */
@Plugin( type = MastodonAutomahoutPlugin.class )
public class MastodonAutomahoutPlugin implements MamutPlugin
{

	static final String ACTION_NAME = "automahout run";

	static final String ACTION_STOP_NAME = "automahout stop";

	static final String ACTION_SETTINGS_NAME = "automahout settings";

	static final String ACTION_CORRECT_NAME = "automahout correct";


	static final String ACTION_ANALYZE_VIDEO_NAME = "automahout analyze video";

	static final String ACTION_AUTONOMOUS_TRACKING_NAME = "automahout autonomous tracking";

	static final String ACTION_LEARN_CONSTRAINTS_NAME = "automahout learn constraints";

	static final String ACTION_LEARN_CONSTRAINTS_MODEL_NAME = "automahout learn constraints model";

	static final String ACTION_BUILD_GT_STORE_NAME = "automahout build gt store";

	static final String ACTION_FOCUS_WINDOW_NAME = "automahout focus window";

	static final String ACTION_FILTER_BY_EXAMPLE_NAME = "automahout filter by example";

	static final String ACTION_RUN_TOOL_NAME = "automahout run a tool";


	private static final String[] SHORTCUTS_STOP = new String[] { "not mapped" };

	private static final String[] SHORTCUTS_SETTINGS = new String[] { "not mapped" };




	private static final String[] SHORTCUTS_AUTONOMOUS_TRACKING = new String[] { "not mapped" };

	private static final String[] SHORTCUTS_LEARN_CONSTRAINTS = new String[] { "not mapped" };

	private static final String[] SHORTCUTS_LEARN_CONSTRAINTS_MODEL = new String[] { "not mapped" };

	private static final String[] SHORTCUTS_BUILD_GT_STORE = new String[] { "not mapped" };



	private static final String[] SHORTCUTS_RUN_TOOL = new String[] { "not mapped" };

	/**
	 * Persistent settings shared by the loop and the settings dialog.
	 * Loaded from user preferences at construction time.
	 */
	private final AutomahoutSettings settings = new AutomahoutSettings();

	/**
	 * One loop instance per plugin (= per Mastodon project), shared by the run
	 * and stop actions so that stop can interrupt what run started.
	 */
	private final AutomahoutLoop loop = new AutomahoutLoop( settings );


	private AutomahoutStopAction stopAction;

	private AutomahoutSettingsAction settingsAction;




	private AutonomousTrackingAction autonomousTrackingAction;

	private LearnConstraintsAction learnConstraintsAction;

	private LearnConstraintsFromModelAction learnConstraintsFromModelAction;

	private BuildGtStoreAction buildGtStoreAction;



	private RunToolAction runToolAction;

	@Override
	public void setAppPluginModel( final ProjectModel projectModel )
	{
		final AutomahoutLogWindow logWindow = new AutomahoutLogWindow( loop );
		loop.setProgressListener( logWindow );

		stopAction = new AutomahoutStopAction( loop );
		settingsAction = new AutomahoutSettingsAction( settings );
		autonomousTrackingAction = new AutonomousTrackingAction( projectModel, logWindow );
		learnConstraintsAction = new LearnConstraintsAction( logWindow, settings );
		learnConstraintsFromModelAction = new LearnConstraintsFromModelAction( projectModel, logWindow, settings );
		buildGtStoreAction = new BuildGtStoreAction( projectModel, logWindow, settings );
		runToolAction = new RunToolAction( projectModel );
		stopAction.setAutonomousTrackingAction( autonomousTrackingAction );

		// Combine stop sources: the button must halt whichever operation is active.
		logWindow.setStopCallback( () -> {
			loop.stop();
			autonomousTrackingAction.cancel();
		} );
	}

	@Override
	public void installGlobalActions( final Actions actions )
	{
		actions.namedAction( stopAction, SHORTCUTS_STOP );
		actions.namedAction( settingsAction, SHORTCUTS_SETTINGS );
		actions.namedAction( autonomousTrackingAction, SHORTCUTS_AUTONOMOUS_TRACKING );
		actions.namedAction( learnConstraintsAction, SHORTCUTS_LEARN_CONSTRAINTS );
		actions.namedAction( learnConstraintsFromModelAction, SHORTCUTS_LEARN_CONSTRAINTS_MODEL );
		actions.namedAction( buildGtStoreAction, SHORTCUTS_BUILD_GT_STORE );
		actions.namedAction( runToolAction, SHORTCUTS_RUN_TOOL );
	}

	@Override
	public List< MenuItem > getMenuItems()
	{
		return Arrays.asList(
				MamutMenuBuilder.makeFullMenuItem( ACTION_AUTONOMOUS_TRACKING_NAME, "Plugins", "Automahout" ),
				MamutMenuBuilder.makeFullMenuItem( ACTION_STOP_NAME, "Plugins", "Automahout" ),
				MamutMenuBuilder.makeFullMenuItem( ACTION_SETTINGS_NAME, "Plugins", "Automahout" ),
				MamutMenuBuilder.makeFullMenuItem( ACTION_RUN_TOOL_NAME, "Plugins", "Automahout" ),
				MamutMenuBuilder.makeFullMenuItem( ACTION_LEARN_CONSTRAINTS_NAME, "Plugins", "Automahout", "Advanced" ),
				MamutMenuBuilder.makeFullMenuItem( ACTION_LEARN_CONSTRAINTS_MODEL_NAME, "Plugins", "Automahout", "Advanced" ),
				MamutMenuBuilder.makeFullMenuItem( ACTION_BUILD_GT_STORE_NAME, "Plugins", "Automahout", "Advanced" ) );
	}

	@Override
	public Map< String, String > getMenuTexts()
	{
		final Map< String, String > texts = new HashMap<>();
		texts.put( ACTION_STOP_NAME, "Stop Automahout" );
		texts.put( ACTION_SETTINGS_NAME, "Automahout Settings..." );
		texts.put( ACTION_AUTONOMOUS_TRACKING_NAME, "Run Autonomous Tracking..." );
		texts.put( ACTION_LEARN_CONSTRAINTS_NAME, "Learn Lineage Constraints from GT..." );
		texts.put( ACTION_LEARN_CONSTRAINTS_MODEL_NAME, "Learn Lineage Constraints from Current Spots and Links..." );
		texts.put( ACTION_BUILD_GT_STORE_NAME, "Build GT Annotation Store from Confirmed Spots..." );
		texts.put( ACTION_RUN_TOOL_NAME, "Run a Tool..." );
		return texts;
	}

	// -----------------------------------------------------------------------

	@Plugin( type = Descriptions.class )
	public static class Descriptions extends CommandDescriptionProvider
	{
		public Descriptions()
		{
			super( KeyConfigScopes.MAMUT, KeyConfigContexts.MASTODON );
		}

		@Override
		public void getCommandDescriptions( final CommandDescriptions descriptions )
		{
			descriptions.add( ACTION_STOP_NAME, SHORTCUTS_STOP,
					"Stop the currently running Automahout loop." );
			descriptions.add( ACTION_SETTINGS_NAME, SHORTCUTS_SETTINGS,
					"Open the Automahout settings: the VLM/LLM backend, model and connection, and the tracking range and correction parameters." );
			descriptions.add( ACTION_AUTONOMOUS_TRACKING_NAME, SHORTCUTS_AUTONOMOUS_TRACKING,
					"Run the full autonomous tracking pipeline: video analysis, ELEPHANT detection/linking, and LLM-driven autocorrection." );
			descriptions.add( ACTION_LEARN_CONSTRAINTS_NAME, SHORTCUTS_LEARN_CONSTRAINTS,
					"Learn cell-lineage-graph constraints from a ground-truth .geff store and save them as a reusable profile." );
			descriptions.add( ACTION_LEARN_CONSTRAINTS_MODEL_NAME, SHORTCUTS_LEARN_CONSTRAINTS_MODEL,
					"Learn cell-lineage-graph constraints from the spots and links currently in the project and save them as a reusable profile." );
			descriptions.add( ACTION_BUILD_GT_STORE_NAME, SHORTCUTS_BUILD_GT_STORE,
					"Write the spots carrying a chosen tag (the ones you have confirmed are cells) to a GT "
							+ "annotation store and activate it, so adaptive tracking trains on those "
							+ "annotations instead of on its own detections." );
			descriptions.add( ACTION_RUN_TOOL_NAME, SHORTCUTS_RUN_TOOL,
					"List every tool Automahout can use -- built-in, from plugin jars, from remote tool servers -- with "
							+ "whether it suits this data and why, and run one with its parameters." );
		}
	}
}
