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
package org.mastodon.mamut.automahout.elephant;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.function.Consumer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.elephant.setting.server.ElephantServerSettings;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

/**
 * Listens to the ELEPHANT server's RabbitMQ notifications the same way the ELEPHANT plugin does, so
 * that long operations (notably detection-model training) can be detected as complete even when the
 * blocking HTTP {@code /seg/train} response is severed by an nginx 60 s gateway timeout — the backend
 * finishes and publishes a notification regardless.
 *
 * <p>ELEPHANT declares three non-durable queues — {@code update} (plain-text status notifications, the
 * ones shown as BDV overlays), {@code log} (JSON {@code {level, message}}), and {@code dataset} — and
 * consumes them with auto-ack. This monitor mirrors that for {@code update} and {@code log}: it relays
 * both to a caller-supplied sink (so the user sees live training progress) and flags completion when an
 * {@code update} notification matches a completion pattern.</p>
 */
public final class ElephantRabbitMqMonitor implements AutoCloseable
{

	private static final Logger log = LoggerFactory.getLogger( ElephantRabbitMqMonitor.class );

	private static final String QUEUE_UPDATE = "update";

	private static final String QUEUE_LOG = "log";

	private final Consumer< String > sink;

	/**
	 * Lower-cased substring whose appearance in ANY relayed message (update or log queue) means the
	 * awaited operation finished. For training this is {@code "done post /seg/train"} — the definitive
	 * server-side request-completion line ELEPHANT publishes to the {@code log} queue. (Intermediate
	 * "Model updated" notifications are NOT terminal, so a substring marker is used instead of a fuzzy
	 * word match.)
	 */
	private final String completionMarker;

	private final Object lock = new Object();

	private volatile boolean completed = false;

	private volatile String completionMessage;

	/** True when {@link #completionMessage} reports a non-2xx status — see {@link #statusAfterMarker}. */
	private volatile boolean completionFailed;

	/** Wall-clock time (ms) of the last server notification relayed, or 0 if none received yet. */
	private volatile long lastMessageAtMs = 0L;

	/** True once at least one server notification has been received (guards the log-silence fallback). */
	private volatile boolean anyMessage = false;

	/** Notifications received so far, the terminal one included. */
	private volatile int messages = 0;

	/** Whether the completion arrived after at least one other notification of this monitor's request. */
	private volatile boolean completedAfterActivity = false;

	private Connection connection;

	private Channel channel;

	/**
	 * The run-wide monitor, when one is open ({@link #startShared}): it holds the run's only connection and relays every
	 * message, during inference as during training. ELEPHANT's {@code update} and {@code log} are named queues, and two
	 * consumers on one share its messages round-robin, so a second connection would take half of them -- a training
	 * call's "DONE" among them. Per-call monitors therefore attach to it instead of connecting.
	 */
	private static volatile ElephantRabbitMqMonitor shared;

	/** The per-call monitors attached to this (shared) one: each sees every message for its own completion check. */
	private final java.util.Set< ElephantRabbitMqMonitor > listeners = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** Whether this monitor is attached to the shared one rather than connected itself. */
	private volatile boolean attached;

	/**
	 * Opens the run-wide monitor: one connection for the run, relaying every server message to {@code sink}. Per-call
	 * monitors started afterwards attach to it. Returns whether it connected.
	 */
	public static synchronized boolean startShared( final ElephantServerSettings settings, final Consumer< String > sink )
	{
		closeShared();
		final ElephantRabbitMqMonitor m = new ElephantRabbitMqMonitor( sink, null );
		if ( !m.connect( settings ) )
			return false;
		shared = m;
		return true;
	}

	/** Makes {@code m} the run-wide monitor without connecting it (tests deliver its messages with {@link #deliver}). */
	static void shareForTest( final ElephantRabbitMqMonitor m )
	{
		shared = m;
	}

	/** One message from the {@code update} queue, handled as a delivery is (relay, completion, attached monitors). */
	void deliver( final String msg )
	{
		markMessage();
		relay( "[ELEPHANT] " + msg );
		checkCompletion( msg );
		forward( msg );
	}

	/** Closes the run-wide monitor, if one is open. */
	public static synchronized void closeShared()
	{
		final ElephantRabbitMqMonitor m = shared;
		shared = null;
		if ( m != null )
			m.close();
	}

	/** Outcome of {@link #awaitCompletion(long, long)}. */
	public enum Result
	{
		/** The completion marker was observed on a server queue. */
		COMPLETED,
		/**
		 * No completion marker, but the server had been streaming notifications and then went silent for
		 * the caller's quiet window — the operation almost certainly finished and the terminal marker was
		 * missed (e.g. the broker connection dropped during the long tail).
		 */
		QUIET,
		/** Neither completion nor sufficient silence within the overall timeout. */
		TIMEOUT,
		/**
		 * The terminal notification arrived but reported a FAILED request (non-2xx HTTP status in the
		 * message). The operation is over — waiting longer will not help — but it did not succeed.
		 */
		FAILED
	}

	/**
	 * @param sink             receives every relayed server notification/log line (may be {@code null});
	 *                         typically wired to the Automahout log window.
	 * @param completionMarker substring (matched case-insensitively) whose appearance signals the awaited
	 *                         operation finished, e.g. {@code "DONE POST /seg/train"}.
	 */
	public ElephantRabbitMqMonitor( final Consumer< String > sink, final String completionMarker )
	{
		this.sink = sink;
		this.completionMarker = completionMarker == null ? null : completionMarker.toLowerCase();
	}

	/**
	 * Opens the RabbitMQ connection and starts consuming the {@code update} and {@code log} queues.
	 * Best-effort: returns {@code false} (and logs) if the broker cannot be reached, so the caller can
	 * fall back to HTTP-only completion detection.
	 */
	public boolean start( final ElephantServerSettings settings )
	{
		final ElephantRabbitMqMonitor s = shared;
		if ( s != null && s != this )
		{
			attached = true;
			s.listeners.add( this );
			return true;
		}
		return connect( settings );
	}

	private boolean connect( final ElephantServerSettings settings )
	{
		try
		{
			final ConnectionFactory factory = new ConnectionFactory();
			factory.setHost( settings.getRabbitMQHost() );
			factory.setPort( settings.getRabbitMQPort() );
			factory.setVirtualHost( settings.getRabbitMQVirtualHost() );
			factory.setUsername( settings.getRabbitMQUsername() );
			factory.setPassword( settings.getRabbitMQPassword() );
			if ( settings.getUseSslProtocol() )
			{
				if ( settings.getVerifySSL() )
					factory.useSslProtocol();
				else
					factory.useSslProtocol( trustAllContext() );
			}
			// Survive a broker/network hiccup during the long training tail: auto-recover the connection AND
			// the topology (queue declarations + consumers) so a dropped connection re-consumes and the
			// terminal "DONE" notification is not silently missed. (The log-silence fallback in
			// awaitCompletion covers the case where recovery does not complete in time.)
			factory.setAutomaticRecoveryEnabled( true );
			factory.setTopologyRecoveryEnabled( true );
			factory.setNetworkRecoveryInterval( 5000 );
			connection = factory.newConnection();
			connection.addShutdownListener( cause -> {
				if ( !cause.isInitiatedByApplication() )
					relay( "[monitor] RabbitMQ connection lost (" + cause.getMessage()
							+ ") — auto-recovery retrying; log-silence fallback active." );
			} );
			channel = connection.createChannel();

			// Match ELEPHANT's declaration: non-durable, non-exclusive, non-auto-delete; auto-ack consume.
			channel.queueDeclare( QUEUE_UPDATE, false, false, false, null );
			channel.basicConsume( QUEUE_UPDATE, true, updateCallback(), tag -> {} );
			channel.queueDeclare( QUEUE_LOG, false, false, false, null );
			channel.basicConsume( QUEUE_LOG, true, logCallback(), tag -> {} );
			return true;
		}
		catch ( final Throwable t )
		{
			log.warn( "RabbitMQ monitor unavailable ({}): falling back to HTTP-only completion.",
					t.getMessage() );
			close();
			return false;
		}
	}

	private DeliverCallback updateCallback()
	{
		return ( tag, delivery ) -> {
			markMessage();
			final String msg = new String( delivery.getBody(), StandardCharsets.UTF_8 );
			relay( "[ELEPHANT] " + msg );
			checkCompletion( msg );
			forward( msg );
		};
	}

	private DeliverCallback logCallback()
	{
		return ( tag, delivery ) -> {
			markMessage();
			final String body = new String( delivery.getBody(), StandardCharsets.UTF_8 );
			String message = body;
			try
			{
				final JSONObject o = new JSONObject( body );
				message = o.optString( "message", body );
				relay( "[ELEPHANT " + o.optString( "level", "INFO" ) + "] " + message );
			}
			catch ( final Exception e )
			{
				relay( "[ELEPHANT] " + body );
			}
			checkCompletion( message );
			forward( message );
		};
	}

	/** Passes a message to the attached per-call monitors, which check their own completion but do not relay it. */
	private void forward( final String message )
	{
		for ( final ElephantRabbitMqMonitor l : listeners )
		{
			l.markMessage();
			l.checkCompletion( message );
		}
	}

	/** Records that a server notification arrived (feeds the log-silence fallback) and wakes any awaiter. */
	private void markMessage()
	{
		lastMessageAtMs = System.currentTimeMillis();
		anyMessage = true;
		messages++;
		synchronized ( lock )
		{
			lock.notifyAll();
		}
	}

	/** Flags completion if the message contains the completion marker. */
	private void checkCompletion( final String message )
	{
		if ( completionMarker == null || message == null )
			return;
		if ( message.toLowerCase().contains( completionMarker ) )
		{
			synchronized ( lock )
			{
				completed = true;
				completionMessage = message;
				completedAfterActivity = messages > 1;
				// The marker only says the request finished, NOT that it succeeded: the server emits the
				// same "DONE POST /seg/train" notification for a failure, with the status appended
				// ("=> [500 INTERNAL SERVER ERROR]"). Matching the marker alone reported a crashed training
				// run as completed, and the caller then spent one of its training iterations re-detecting
				// with an unchanged model — visible only as a re-detection returning byte-identical counts.
				final int status = statusAfterMarker( message, completionMarker );
				completionFailed = status >= 0 && ( status < 200 || status >= 300 );
				lock.notifyAll();
			}
		}
	}

	/**
	 * Extracts the HTTP status from a terminal notification, e.g. {@code 500} from
	 * {@code "DONE POST /seg/train => [500 INTERNAL SERVER ERROR]"}.
	 *
	 * <p>Only the part AFTER the marker is searched, so a bracketed prefix the relay may have added
	 * (e.g. {@code "[ELEPHANT INFO]"}) cannot be mistaken for a status.</p>
	 *
	 * @return the status code, or {@code -1} when the message carries none — in which case the caller
	 *         must treat the notification as a success, the long-standing behaviour for the servers and
	 *         endpoints that report no status at all
	 */
	static int statusAfterMarker( final String message, final String markerLowercase )
	{
		if ( message == null || markerLowercase == null )
			return -1;
		final int at = message.toLowerCase().indexOf( markerLowercase );
		if ( at < 0 )
			return -1;
		final java.util.regex.Matcher m =
				TERMINAL_STATUS.matcher( message.substring( at + markerLowercase.length() ) );
		return m.find() ? Integer.parseInt( m.group( 1 ) ) : -1;
	}

	/** Matches the bracketed HTTP status a terminal notification appends, e.g. {@code [500 ...]}. */
	private static final java.util.regex.Pattern TERMINAL_STATUS =
			java.util.regex.Pattern.compile( "\\[\\s*(\\d{3})\\b" );

	/**
	 * Blocks until a completion notification has been received (possibly already, before this call) or
	 * {@code timeoutMs} elapses.
	 *
	 * @return {@code true} if completion was observed
	 */
	public boolean awaitCompletion( final long timeoutMs )
	{
		return awaitCompletion( timeoutMs, 0L ) == Result.COMPLETED;
	}

	/**
	 * Blocks until either the completion marker is observed, the server notification stream goes silent
	 * for {@code quietMs} after having streamed at least one message, or {@code overallTimeoutMs} elapses.
	 *
	 * <p>The silence branch is a server-semantics-independent fallback: ELEPHANT streams training
	 * progress continuously (per-epoch / per-log-interval), so a sustained gap after activity means the
	 * job finished even if the terminal marker was missed (e.g. the broker connection dropped during the
	 * long tail). {@code quietMs <= 0} disables it, restoring pure marker-only waiting.</p>
	 *
	 * @param overallTimeoutMs hard upper bound on the wait
	 * @param quietMs          post-activity silence that counts as completion, or {@code <= 0} to disable
	 * @return {@link Result#COMPLETED}, {@link Result#QUIET}, or {@link Result#TIMEOUT}
	 */
	public Result awaitCompletion( final long overallTimeoutMs, final long quietMs )
	{
		final long deadline = System.currentTimeMillis() + Math.max( 0, overallTimeoutMs );
		final long poll = 5_000L;
		synchronized ( lock )
		{
			while ( true )
			{
				if ( completed )
					return completionFailed ? Result.FAILED : Result.COMPLETED;
				final long now = System.currentTimeMillis();
				if ( quietMs > 0 && anyMessage && ( now - lastMessageAtMs ) >= quietMs )
					return Result.QUIET;
				final long toDeadline = deadline - now;
				if ( toDeadline <= 0 )
					return Result.TIMEOUT;
				long wait = Math.min( poll, toDeadline );
				if ( quietMs > 0 && anyMessage )
					wait = Math.min( wait, Math.max( 1L, lastMessageAtMs + quietMs - now ) );
				try
				{
					lock.wait( wait );
				}
				catch ( final InterruptedException e )
				{
					Thread.currentThread().interrupt();
					return Result.TIMEOUT;
				}
			}
		}
	}

	public String getCompletionMessage()
	{
		return completionMessage;
	}

	/**
	 * Whether this request's completion has been reported, after the server's own progress messages for it (its
	 * frames and epochs). A terminal notification arriving with nothing before it may be an EARLIER request's,
	 * delivered late after a reconnection, and does not count.
	 */
	public boolean completedThisRequest()
	{
		return completed && completedAfterActivity;
	}

	private void relay( final String s )
	{
		if ( sink != null )
			sink.accept( s );
	}

	@Override
	public void close()
	{
		if ( attached )
		{
			final ElephantRabbitMqMonitor s = shared;
			if ( s != null )
				s.listeners.remove( this );
			attached = false;
			return;
		}
		try
		{
			if ( channel != null && channel.isOpen() )
				channel.close();
		}
		catch ( final Exception ignored )
		{}
		try
		{
			if ( connection != null && connection.isOpen() )
				connection.close();
		}
		catch ( final Exception ignored )
		{}
		channel = null;
		connection = null;
	}

	private static SSLContext trustAllContext() throws Exception
	{
		final SSLContext ctx = SSLContext.getInstance( "TLSv1.2" );
		ctx.init( null, new TrustManager[] { new X509TrustManager()
		{
			@Override
			public void checkClientTrusted( final X509Certificate[] chain, final String authType )
			{}

			@Override
			public void checkServerTrusted( final X509Certificate[] chain, final String authType )
			{}

			@Override
			public X509Certificate[] getAcceptedIssuers()
			{
				return new X509Certificate[ 0 ];
			}
		} }, new SecureRandom() );
		return ctx;
	}
}
