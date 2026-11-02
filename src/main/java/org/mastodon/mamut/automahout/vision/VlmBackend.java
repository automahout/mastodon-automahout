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
package org.mastodon.mamut.automahout.vision;

import java.io.IOException;
import java.util.List;

/**
 * Abstraction over a Vision Language Model (VLM) / LLM backend.
 *
 * <p>Implementations provide two modes:</p>
 * <ul>
 *   <li>{@link #analyze} — single-turn call with optional images (VLM mode)</li>
 *   <li>{@link #chat} — multi-turn text conversation (LLM agent mode)</li>
 * </ul>
 *
 * <p>Implementations must be thread-safe and may block the calling thread
 * while the network request is in flight.</p>
 */
public interface VlmBackend
{

	/**
	 * Sends a vision request and returns the model's text response.
	 *
	 * @param model       model name / tag understood by the backend
	 * @param prompt      text instruction appended after the images
	 * @param base64Images base-64-encoded PNG images in display order
	 * @return the model's text reply
	 * @throws IOException on network or API error
	 */
	String analyze( String model, String prompt, List< String > base64Images )
			throws IOException;

	/**
	 * Sends a multi-turn text conversation and returns the model's next reply.
	 *
	 * <p>The messages list must alternate {@code "user"} / {@code "assistant"}
	 * roles and end with a {@code "user"} message.</p>
	 *
	 * @param model    model name / tag
	 * @param messages conversation history; last element must have role
	 *                 {@code "user"}
	 * @return the model's reply text
	 * @throws IOException on network or API error
	 */
	String chat( String model, List< LlmMessage > messages ) throws IOException;
}
