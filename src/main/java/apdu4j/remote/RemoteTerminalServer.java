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

import apdu4j.SCTool;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

public class RemoteTerminalServer {
	private static Logger logger = LoggerFactory.getLogger(RemoteTerminalServer.class);

	// Properties for configuration after compilation
	private final static String BACKLOG = "apdu4j.remote.http.backlog";
	private final static String HTTPPOOL = "apdu4j.remote.http.threadpool";
	private final static String SESSIONS = "apdu4j.remote.http.maxsessions";
	private final static String THREADTIMEOUT = "apdu4j.remote.thread.timeout";
	private final static String BACKENDPOOL = "apdu4j.remote.backend.threadpool";


	static class Session {
		// TODO: STATE
		final UUID id;
		final BlockingQueue<Map<String, Object>> toThread;
		final BlockingQueue<Map<String, Object>> fromThread;

		long timestamp;
		Session(UUID sid) {
			id = sid;
			toThread = new ArrayBlockingQueue<>(1);
			fromThread = new ArrayBlockingQueue<>(1);
			timestamp = System.currentTimeMillis();
		}

	}

	private final ConcurrentHashMap<UUID, Session> sessions;
	private final ExecutorService e;
	private final Class<? extends RemoteTerminalThread> processor;
	private HttpServer server;

	public RemoteTerminalServer(Class<? extends RemoteTerminalThread> task) {
		e = Executors.newFixedThreadPool(Integer.parseInt(System.getProperty(HTTPPOOL, "200")));
		sessions = new ConcurrentHashMap<>(Integer.parseInt(System.getProperty(SESSIONS, "200")));
		processor = task;
	}

	// Everything that is not 200 is considered as bad request.
	public static void drop(HttpExchange req) throws IOException {
		setStandardHeaders(req);
		req.sendResponseHeaders(418, 0);
		try (OutputStream body = req.getResponseBody()) {
			body.write(("apdu4j/"+SCTool.getVersion()).getBytes(StandardCharsets.UTF_8));
		}
	}

	public void start(InetSocketAddress address) throws IOException {

		server = HttpServer.create(address, Integer.parseInt(System.getProperty(BACKLOG, "10")));
		// threadpool!
		server.setExecutor(Executors.newWorkStealingPool(Integer.parseInt(System.getProperty(HTTPPOOL, "10"))));
		// Only two handlers.
		server.createContext("/", new MsgHandler());
		server.createContext("/status", new StatusHandler());

		logger.info("Server started on {} ", server.getAddress());
		// Starts in separate thread.
		server.start();
	}

	public void stop(int timeout) {
		server.stop(timeout);
	}

	public void gc(long oldest) {
		for (Session s: sessions.values()) {
			if (s.timestamp < oldest) {
				logger.debug("Pruning session: {}", s.id);
				sessions.remove(s.id);
			}
		}
	}
	private static void setStandardHeaders(HttpExchange req) {
		Headers h = req.getResponseHeaders();
		h.set("Server", "apdu4j/"+SCTool.getVersion());
	}

	private class MsgHandler implements HttpHandler {

		private void transceive(HttpExchange r, Map<String, Object> msg, Session session) throws IOException {
			try {
				session.timestamp = System.currentTimeMillis();
				logger.trace("to thread: {}", new JSONObject(msg).toJSONString());
				if (!session.toThread.offer(msg)) {
					logger.warn("Could not add to thread queue!");
					throw new IOException("Could not add to thread queue!");
				}
				// backend has 60 seconds to figure out the next action.
				Map<String, Object> resp = session.fromThread.poll(Long.parseLong(System.getProperty(THREADTIMEOUT, "60")), TimeUnit.SECONDS);
				if (resp == null) {
					logger.warn("Timeout");
					Map<String, Object> stop = new HashMap<>();
					stop.put("cmd", "STOP");
					stop.put("message", "Timeout waiting for reply from thread");
					// If the thread does wake up, signal the closed session.
					if (!session.toThread.offer(stop))
						logger.warn("Could not queue STOP message");
					throw new IOException("Timeout");
				}
				// Log the respone from thread.
				logger.trace("from thread: {}", new JSONObject(resp).toJSONString());

				// Add session ID to message from worker.
				resp.put("session", session.id.toString());
				// Convert message to JSON
				JSONObject respjson = new JSONObject(resp);
				logger.trace("SEND: {}", respjson.toJSONString());

				// Send response
				setStandardHeaders(r);
				r.getResponseHeaders().set("Content-type", "application/json");
				byte[] payload = respjson.toJSONString().getBytes("UTF-8");
				r.sendResponseHeaders(200, payload.length);
				// Close stream
				try (OutputStream body = r.getResponseBody()) {
					body.write(payload);
				}
			} catch (InterruptedException e) {
				logger.debug("Interrupted");
				throw new IOException(e);
			}
		}

		@Override
		@SuppressWarnings("unchecked")
		public void handle(HttpExchange req) throws IOException {
			if (req.getRequestMethod().equals("POST")) {
				// Parse the input
				try (InputStream inp = req.getRequestBody()) {
					Headers h = req.getRequestHeaders();
					// Why it does not have integer method?
					int len = Integer.parseInt(h.getFirst("Content-Length"));
					logger.trace("Content-length: {}", len);
					if (len > 2048 || len <= 0) {
						logger.info("Too huge request, dropping");
						drop(req);
					} else {
						// Read the data
						byte [] data = new byte[len];
						int readlen = inp.read(data);

						if (readlen == len) {
							// Read the message from the interweb.
							final Map<String, Object> msg;
							try {
								JSONObject obj = (JSONObject) JSONValue.parseWithException(new String(data, "UTF-8"));
								logger.trace("RECV: {}", obj.toJSONString());
								msg = obj;
							} catch (ParseException e) {
								throw new IOException("Could not parse JSON", e);
							}

							// Add client IP
							if (req.getRequestHeaders().containsKey("X-Forwarded-For")) {
								msg.put("clientip", req.getRequestHeaders().getFirst("X-Forwarded-For"));
							} else {
								msg.put("clientip", req.getRemoteAddress().getHostString());
							}

							// check for session
							if (!msg.containsKey("session")) {
								try {
									// Generate session ID
									UUID sid = UUID.randomUUID();
									logger.debug("New session: {}", sid.toString());
									Session sess = new Session(sid);

									// Pack in session
									msg.put("session", sid.toString());
									if (req.getRequestHeaders().containsKey("User-Agent")) {
										msg.put("useragent", req.getRequestHeaders().getFirst("User-Agent"));
									}

									// Initiate a thread with the queue
									RemoteTerminalThread thread = processor.newInstance();
									thread.setQueues(sess.toThread, sess.fromThread);
									thread.setSession(sid.toString());
									// execute created thread with queues
									e.execute(thread);

									// Transceive first message to thread
									transceive(req, msg, sess);

									// Put into session map if it did not throw.
									sessions.put(sid, sess);

								} catch (SecurityException | InstantiationException | IllegalAccessException | IllegalArgumentException e) {
									logger.error("Could not start worker thread", e);
									throw new RuntimeException("Could not initiate a worker thread!", e);
								}
							} else {
								UUID sid = UUID.fromString((String)msg.get("session"));
								if (!sessions.containsKey(sid)) {
									logger.warn("Session {} not found", sid.toString());
									drop(req);
									return;
								} else {
									logger.trace("Resuming session {}", sid.toString());
									// get session
									Session sess = sessions.get(sid);
									// trancieve message catching errors.
									try {
										transceive(req, msg, sess);
									} catch (IOException e) {
										logger.debug("Thread communication failed, removing session", e);
										sessions.remove(sid);
										throw e;
									}
								}
							}
						} else {
							logger.debug("Read {} instead, closing", readlen);
							drop(req);
							return;
						}
					}
				}
			} else {
				// not POST
				drop(req);
				return;
			}
		}
	}

	private class StatusHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange req) throws IOException {
			setStandardHeaders(req);
			req.sendResponseHeaders(200, 0);
			try (OutputStream body = req.getResponseBody()) {
				String s = "apdu4j/"+SCTool.getVersion() + " OK: " + sessions.size();
				body.write(s.getBytes(StandardCharsets.UTF_8));
			}
		}
	}
}
