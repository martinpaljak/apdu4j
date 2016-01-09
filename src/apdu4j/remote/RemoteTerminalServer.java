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
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import apdu4j.SCTool;

public class RemoteTerminalServer {
	private static Logger logger = LoggerFactory.getLogger(RemoteTerminalServer.class);

	// Properties for configuration after compilation
	private final static String BACKLOG = "apdu4j.remote.http.backlog";
	private final static String HTTPPOOL = "apdu4j.remote.http.threadpool";
	private final static String SESSIONS = "apdu4j.remote.http.maxsessions";
	private final static String BACKENDPOOL = "apdu4j.remote.backend.threadpool";

	class Session {
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
	private int port;

	public RemoteTerminalServer(Class<? extends RemoteTerminalThread> task) {
		e = Executors.newFixedThreadPool(Integer.valueOf(System.getProperty(HTTPPOOL, "200")));
		sessions = new ConcurrentHashMap<>(Integer.valueOf(System.getProperty(SESSIONS, "200")));
		processor = task;
	}

	// Everything that is not 200 is considered as bad request.
	public static void drop(HttpExchange req) throws IOException {
		setStandardHeaders(req);
		req.sendResponseHeaders(418, 0);
		try (OutputStream body = req.getResponseBody()) {
			body.write(SCTool.getVersion(SCTool.class).getBytes());
		}
	}

	public void start() throws IOException {

		InetSocketAddress addr = new InetSocketAddress(10000); // FIXME: have this as argument.
		server = HttpServer.create(addr, Integer.valueOf(System.getProperty(BACKLOG, "10")));
		// threadpool!
		server.setExecutor(Executors.newWorkStealingPool(Integer.valueOf(System.getProperty(HTTPPOOL, "10"))));
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
	private static void setStandardHeaders(HttpExchange req) {
		Headers h = req.getResponseHeaders();
		h.set("Server", "apdu4j/"+SCTool.getVersion(SCTool.class));
	}

	private class MsgHandler implements HttpHandler {

		private void transceive(HttpExchange r, Map<String, Object> msg, Session session) throws IOException {
			try {
				logger.debug("to thread: {}", new JSONObject(msg).toJSONString());
				if (!session.toThread.offer(msg)) {
					logger.error("Could not add to thread queue!");
					throw new IOException("Could not add to thread queue!");
				}
				// backend has 30 seconds to figure out the next action.
				Map<String, Object> resp = session.fromThread.poll(30, TimeUnit.SECONDS);
				// Log the respone from thread.
				logger.debug("from thread: {}", new JSONObject(resp).toJSONString());

				if (resp != null) {
					// Add session ID to message from worker.
					resp.put("session", session.id.toString());
					// Convert message to JSON
					JSONObject respjson = new JSONObject(resp);
					logger.debug("SEND: {}", respjson.toJSONString());

					// Send response
					setStandardHeaders(r);
					r.getResponseHeaders().set("Content-type", "application/json");
					byte [] payload = respjson.toJSONString().getBytes("UTF-8");
					r.sendResponseHeaders(200, payload.length);
					// Close stream
					try (OutputStream body = r.getResponseBody()) {
						body.write(payload);
					}
				} else {
					// HORRIBLE STUFF
				}
			} catch (InterruptedException e) {
				logger.debug("Timeout from thread");
				// Reading from thread timed out. We close the session
				throw new IOException(e);
			}
		}

		@Override
		public void handle(HttpExchange req) throws IOException {
			if (req.getRequestMethod().equals("POST")) {
				// Parse the input
				try (InputStream inp = req.getRequestBody()) {
					Headers h = req.getRequestHeaders();
					// Why it does not have integer method?
					int len = Integer.parseInt(h.getFirst("Content-Length"));
					logger.trace("Content-length: {}", len);
					if (len > 2048 || len <= 0) {
						logger.info("Too huge requst, dropping");
						drop(req);
					} else {
						// Read the data
						byte [] data = new byte[len];
						int readlen = inp.read(data);

						if (readlen == len) {
							// Read the message from the interweb.
							JSONObject obj = (JSONObject) JSONValue.parse(new String(data, "UTF-8"));
							logger.debug("RECV: {}", obj.toJSONString());

							// Convert to standard map
							HashMap<String, Object> msg = new HashMap<>(obj);
							msg.putAll(obj);

							// check for session
							if (!msg.containsKey("session")) {
								try {
									// Generate session ID
									UUID sid = UUID.randomUUID();
									logger.debug("New session: {}", sid.toString());
									Session sess = new Session(sid);

									// Pack in session (and other things from header)
									msg.put("session", sid.toString());

									// Initiate a thread with the queue
									Constructor<? extends RemoteTerminalThread> thrd = processor.asSubclass(RemoteTerminalThread.class).getConstructor(BlockingQueue.class, BlockingQueue.class);
									RemoteTerminalThread thread = thrd.newInstance(sess.toThread, sess.fromThread);
									logger.debug("starting thread");
									// execute created thread with queues
									e.execute(thread);
									logger.debug("thread started.");
									// Put into session map.
									sessions.put(sid, sess);
									// Transceive first message to thread
									transceive(req, msg, sess);
								} catch (NoSuchMethodException | SecurityException | InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
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
									logger.debug("Resuming session {}", sid.toString());
									// get session
									Session sess = sessions.get(sid);
									sess.timestamp = System.currentTimeMillis();
									// trancieve message
									transceive(req, msg, sess);
								}
							}
						} else {
							logger.debug("Read {} instead", readlen);
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
			logger.trace("Message processed");
		}
	}

	private class StatusHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange req) throws IOException {
			setStandardHeaders(req);
			req.sendResponseHeaders(200, 0);
			try (OutputStream body = req.getResponseBody()) {
				String s = "OK: " + sessions.size();
				body.write(s.getBytes());
			}
		}
	}
}
