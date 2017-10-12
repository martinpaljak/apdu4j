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

import java.util.HashMap;
import java.util.Map;

import apdu4j.remote.RemoteTerminal.UserCancelExcption;

class JSONProtocol {

	// User by client
	public static Map<String, Object> nok(Map<String, Object> msg, String error) {
		HashMap<String, Object> r = new HashMap<>();
		r.put((String) msg.get("cmd"), "NOK");
		r.put("session", msg.get("session"));
		if (error != null) {
			r.put("ERROR", error);
		}
		return r;
	}

	// Used by client
	public static Map<String, Object> ok(Map<String, Object> msg) {
		HashMap<String, Object> r = new HashMap<>();
		r.put((String) msg.get("cmd"), "OK");
		r.put("session", msg.get("session"));
		return r;
	}

	// Used by server. Server adds session ID transparently.
	public static Map<String, Object> cmd(String c) {
		HashMap<String, Object> r = new HashMap<>();
		r.put("cmd", c.toUpperCase());
		return r;
	}

	public static void check_cancel(Map<String, Object> r) throws UserCancelExcption {
		if (r.containsKey("button") && r.get("button").equals("red"))
			throw new UserCancelExcption("User pressed the red button");
	}
	// User by server
	public static boolean check(Map<String, Object> m, Map<String, Object> r) {
		if (r.get(m.get("cmd")).equals("OK")) {
			return true;
		}
		return false;
	}
}
