package org.mastodon.mamut.automahout.elephant;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Test;

/**
 * The training call stops waiting for an HTTP reply once the server's own "done" for THIS request has arrived over
 * RabbitMQ; a "done" with nothing before it may be an earlier request's, delivered late, and does not count.
 */
public class MonitorCompletionTest
{
	private static void receive( final ElephantRabbitMqMonitor m, final String message ) throws Exception
	{
		final Method mark = ElephantRabbitMqMonitor.class.getDeclaredMethod( "markMessage" );
		final Method check = ElephantRabbitMqMonitor.class.getDeclaredMethod( "checkCompletion", String.class );
		mark.setAccessible( true );
		check.setAccessible( true );
		mark.invoke( m );
		check.invoke( m, message );
	}

	@Test
	public void aDoneAfterThisRequestsProgressCounts() throws Exception
	{
		final ElephantRabbitMqMonitor m = new ElephantRabbitMqMonitor( null, "done post /seg/train" );
		assertFalse( m.completedThisRequest() );
		receive( m, "frame:20, [('tp', 9), ('fp', 0)]" );
		receive( m, "Train Epoch: 4 [10/10 (100%)]" );
		assertFalse( m.completedThisRequest() );
		receive( m, "DONE POST /seg/train => [200 OK]" );
		assertTrue( m.completedThisRequest() );
	}

	@Test
	public void aLoneDoneMayBeAnEarlierRequestsAndDoesNotCount() throws Exception
	{
		final ElephantRabbitMqMonitor m = new ElephantRabbitMqMonitor( null, "done post /seg/train" );
		receive( m, "DONE POST /seg/train => [200 OK]" );
		assertFalse( m.completedThisRequest() );
	}
}
