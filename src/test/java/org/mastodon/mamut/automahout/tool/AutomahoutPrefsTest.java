package org.mastodon.mamut.automahout.tool;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The environment names scripts/reproduce documents for the language-model settings. */
public class AutomahoutPrefsTest
{
	@Test
	public void llmEnvironmentNames()
	{
		assertEquals( "AUTOMAHOUT_VLM_BACKEND", AutomahoutPrefs.llmEnvName( "vlmBackend" ) );
		assertEquals( "AUTOMAHOUT_VLM_BASE_URL", AutomahoutPrefs.llmEnvName( "vlmBaseUrl" ) );
		assertEquals( "AUTOMAHOUT_VLM_AWS_REGION", AutomahoutPrefs.llmEnvName( "vlmAwsRegion" ) );
		assertEquals( "AUTOMAHOUT_VLM_MODEL", AutomahoutPrefs.llmEnvName( "vlmModel" ) );
		assertEquals( "AUTOMAHOUT_VLM_API_KEY", AutomahoutPrefs.llmEnvName( "vlmApiKey" ) );
	}
}
