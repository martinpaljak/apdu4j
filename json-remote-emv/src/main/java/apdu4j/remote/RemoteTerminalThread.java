/*
 * Copyright (c) 2014-2015 Martin Paljak
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package apdu4j.remote;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class RemoteTerminalThread implements Runnable, JSONMessagePipe {
	private static Logger logger = LoggerFactory.getLogger(RemoteTerminalThread.class);

	private BlockingQueue<Map<String, Object>> toThread;
	private BlockingQueue<Map<String, Object>> fromThread;
	protected String session;


	private long timeout_minutes = 3;
	protected RemoteTerminal terminal;

	void setQueues(BlockingQueue<Map<String, Object>> in, BlockingQueue<Map<String, Object>> out) {
		toThread = in;
		fromThread = out;
		terminal = new RemoteTerminal(this);
	}

	void setTimeout(long minutes) {
		timeout_minutes = minutes;
	}

	void setSession(String session) {
		this.session = session;
	}

	@Override
	public void send(Map<String, Object> msg) throws IOException {
		logger.trace("sending: {}", new JSONObject(msg).toJSONString());
		if (!fromThread.offer(msg)) {
			throw new IOException("Output queue is full");
		}
	}

	@Override
	public Map<String, Object> recv() throws IOException {
		logger.trace("receiving ...");
		try {
			// Client times out after 3 minutes.
			Map<String, Object> msg = toThread.poll(timeout_minutes, TimeUnit.MINUTES);
			if (msg == null) {
				logger.warn("Timeout");
				throw new IOException("Timeout");
			}
			logger.trace("received: {}", new JSONObject(msg).toJSONString());
			return msg;
		} catch (InterruptedException e) {
			logger.warn("Interrupted", e);
			throw new IOException("Interrupted", e);
		}
	}

	@Override
	public abstract void run();

	@Override
	public void close() {
		// TODO: do anything here ?
	}
}
