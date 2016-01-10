/*
 * Copyright (c) 2016 Martin Paljak
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Used by the client.
public class HTTPTransport implements JSONMessagePipe {
	private static Logger logger = LoggerFactory.getLogger(HTTPTransport.class);

	private final URL u;
	HttpURLConnection c = null;
	private SSLSocketFactory f = null;

	private HTTPTransport(URL url) {
		this.u = url;
	}



	// Open a connection to a URL, with a pinned cert (or no checking)
	public static HTTPTransport open(URL u, X509Certificate pinned) throws IOException {
		HTTPTransport t = new HTTPTransport(u);
		t.f = SocketTransport.get_ssl_socket_factory(pinned);
		return t;
	}


	private HttpURLConnection connect() throws IOException {
		c = (HttpURLConnection)u.openConnection();
		c.setDoOutput(true);
		c.setDoInput(true);
		c.setRequestProperty("Content-Type", "application/json");
		c.setRequestProperty("Accept", "application/json");
		c.setRequestMethod("POST");

		// Set SSL options
		if (c instanceof HttpsURLConnection) {
			if (f != null) {
				HttpsURLConnection https = (HttpsURLConnection) c;
				https.setSSLSocketFactory(f);
			}
		}
		return c;
	}

	@Override
	public synchronized void send(Map<String, Object> msg) throws IOException {
		// Always open a new connection when sending.
		c = connect();

		JSONObject obj = new JSONObject(msg);
		logger.trace("SEND: {}", obj.toJSONString());
		byte[] data = obj.toJSONString().getBytes(Charset.forName("UTF-8"));
		// Close the stream
		try (OutputStream out = c.getOutputStream()) {
			out.write(data);
		}
	}

	@Override
	public synchronized Map<String, Object> recv() throws IOException {
		HashMap<String, Object> r = new HashMap<>();
		if (c.getResponseCode() == 200) {
			try (InputStream in = c.getInputStream()) {
				byte [] response = new byte[c.getContentLength()];
				int len = in.read(response);
				if (len != c.getContentLength()) {
					logger.trace("Read {} instead of {}", len, c.getContentLength());
					throw new IOException("Read only " + len + " bytes instead of " + c.getContentLength());
				}
				try {
					JSONObject obj = (JSONObject) JSONValue.parseWithException(new String(response, "UTF-8"));
					logger.trace("RECV: {}", obj.toJSONString());
					r.putAll(obj);
				} catch (ParseException e) {
					throw new IOException("Could not parse JSON", e);
				}

			}
		} else {
			logger.trace("Got response code {}", c.getResponseCode());
			throw new IOException("Got response code " + c.getResponseCode());
		}
		return r;
	}

	@Override
	public void close() {
		// Disconnect, if connection open.
		if (c!= null) {
			c.disconnect();
		}
	}
}
