/*
 * Copyright (c) 2015 Martin Paljak
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

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Map;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import apdu4j.HexUtils;

/**
 * Based on the <a href=
 * "https://developer.chrome.com/extensions/nativeMessaging#native-messaging-host-protocol">
 * NativeMessaging protocol from Chrome</a> (but uses big endian uint32
 * as is common in network protocols). Messages bigger than 1024 bytes are
 * rejected by this implementation and the underlying socket is closed.
 *
 * @author Martin Paljak
 */
public class SocketTransport implements JSONMessagePipe {
	private static Logger logger = LoggerFactory.getLogger(SocketTransport.class);

	private final Socket socket;
	private final ByteBuffer length = ByteBuffer.allocate(Integer.SIZE / Byte.SIZE);

	public SocketTransport(Socket s) {
		socket = s;
		length.order(ByteOrder.BIG_ENDIAN);
	}


	/**
	 * Connects to the mentioned host and port with SSL without checking certificate chain.
	 *
	 * @param address host:port to connect to
	 * @return instance of this class
	 * @throws IOException if establishing the connection fails
	 */
	public static SocketTransport connect_insecure(InetSocketAddress address) throws IOException {
		return connect(address, null);
	}


	public static KeyManagerFactory get_key_manager_factory(String pkcs12path, String pkcs12pass) throws IOException {
		try {
			KeyStore ks = KeyStore.getInstance("PKCS12");
			ks.load(new FileInputStream(pkcs12path), pkcs12pass.toCharArray());
			KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			kmf.init(ks, pkcs12pass.toCharArray());
			return kmf;
		} catch (UnrecoverableKeyException | KeyStoreException | NoSuchAlgorithmException | CertificateException | IOException e) {
			throw new IOException("Could not load client key!", e);
		}
	}
	protected static SSLSocketFactory get_ssl_socket_factory(KeyManagerFactory kmf, X509Certificate pinnedcert) throws IOException {
		try {
			// Create a trust manager that does not validate certificate chains
			TrustManager[] trustAllCerts = new TrustManager[] {
					new X509TrustManager() {
						@Override
						public X509Certificate[] getAcceptedIssuers() {
							return new X509Certificate[0];
						}
						@Override
						public void checkServerTrusted(X509Certificate[] arg0, String arg1) throws CertificateException {
						}
						@Override
						public void checkClientTrusted(X509Certificate[] arg0, String arg1) throws CertificateException {
						}
					}
			};

			// Trust managers for SSL context
			SSLContext sc = SSLContext.getInstance("TLS");

			if (pinnedcert != null) {
				TrustManagerFactory tmf = TrustManagerFactory.getInstance("X509");
				KeyStore ks = null;
				ks = KeyStore.getInstance(KeyStore.getDefaultType());
				// Generate an empty one
				ks.load(null, null);
				ks.setCertificateEntry("pinned", pinnedcert);
				tmf.init(ks);
				sc.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new java.security.SecureRandom());
			} else {
				sc.init(kmf.getKeyManagers(), trustAllCerts, new java.security.SecureRandom());
			}
			// Connect with created parameters
			return sc.getSocketFactory();
		} catch (KeyManagementException | KeyStoreException | NoSuchAlgorithmException | CertificateException e) {
			throw new IOException("Could not connect", e);
		}

	}
	// Returns a SSLSocketFactory that either does no checking or checks for a pinnned certificate
	protected static SSLSocketFactory get_ssl_socket_factory(X509Certificate pinnedcert) throws IOException {
		return get_ssl_socket_factory(null, pinnedcert);
	}

	/**
	 * Connects to the mentioned host and port with SSL and checks the certificate against the pinned version.
	 *
	 * @param address host:port to connect to
	 * @param pinnedcert expected certificate of the server
	 * @return instance of this class
	 * @throws IOException if establishing the connection fails
	 */
	public static SocketTransport connect(InetSocketAddress address, X509Certificate pinnedcert) throws IOException {
		SSLSocketFactory factory = get_ssl_socket_factory(pinnedcert);
		Socket s = factory.createSocket(address.getHostString(), address.getPort());
		return new SocketTransport(s);
	}

	// Helper class to make a SSL server
	public static ServerSocket make_server(int port, String pkcs12Path, String pkcs12Pass)
			throws IOException, KeyStoreException, NoSuchAlgorithmException, CertificateException, UnrecoverableKeyException, KeyManagementException {
		try (InputStream in = new FileInputStream(pkcs12Path)) {
			return make_server(port, in, pkcs12Pass);
		}
	}

	// Helper class to make a SSL socket server
	private static ServerSocket make_server(int port, InputStream pkcs12stream, String pkcs12Pass)
			throws IOException, KeyStoreException, NoSuchAlgorithmException, CertificateException, UnrecoverableKeyException, KeyManagementException {
		KeyStore ks = KeyStore.getInstance("PKCS12");
		ks.load(pkcs12stream, pkcs12Pass.toCharArray());
		KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		kmf.init(ks, pkcs12Pass.toCharArray());
		SSLContext sc = SSLContext.getInstance("TLS");
		sc.init(kmf.getKeyManagers(), null, null);
		return sc.getServerSocketFactory().createServerSocket(port);
	}


	@Override
	@SuppressWarnings("unchecked")
	public synchronized void send(Map<String, Object> msg) throws IOException {
		JSONObject obj = new JSONObject();
		obj.putAll(msg);
		byte[] data = obj.toJSONString().getBytes(Charset.forName("UTF-8"));
		socket.getOutputStream().write(length.putInt(0, data.length).array());
		socket.getOutputStream().write(data);
		logger.debug("> ({}) {}", HexUtils.encodeHexString(length.array()), obj.toJSONString());
	}

	@Override
	@SuppressWarnings("unchecked")
	public synchronized Map<String, Object> recv() throws IOException {
		if (socket.isClosed()) {
			throw new IOException("Connection closed");
		}
		length.putInt(0, 0);
		socket.getInputStream().read(length.array());
		int len = length.getInt(0);
		if (len == 0) {
			throw new IOException("Failed to read data (length)");
		}
		if (len > 1024) {
			throw new IOException("Bad message length > 1024");
		}
		// Read data
		byte[] data = new byte[len];

		int readlen = socket.getInputStream().read(data);
		if (readlen != len) {
			throw new IOException("Read " + readlen + " instead of " + len);
		}
		JSONObject obj;
		try {
			obj = (JSONObject) JSONValue.parseWithException(new String(data, "UTF-8"));
		} catch (ParseException e) {
			throw new IOException("Could not parse JSON", e);
		}
		logger.debug("< ({}) {}",  HexUtils.encodeHexString(length.array()), obj.toJSONString());
		return obj;
	}

	@Override
	public void close() {
		try {
			socket.close();
		} catch (IOException e) {
			logger.trace("Could not close socket", e);
		}
	}
}
