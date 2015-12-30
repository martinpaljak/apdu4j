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
package apdu4j.json;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

class JSONProtocol {

	public static Map<String, Object> nok(Map<String, Object> msg, String error) {
		HashMap<String, Object> r = new HashMap<>();
		r.put((String) msg.get("cmd"), "NOK");
		r.put("id", msg.get("id"));
		if (error != null) {
			r.put("ERROR", error);
		}
		return r;
	}

	public static Map<String, Object> ok(Map<String, Object> msg) {
		HashMap<String, Object> r = new HashMap<>();
		r.put((String) msg.get("cmd"), "OK");
		r.put("id", msg.get("id"));
		return r;
	}

	public static Map<String, Object> cmd(String c) {
		HashMap<String, Object> r = new HashMap<>();
		r.put("cmd", c.toUpperCase());
		r.put("id", UUID.randomUUID().toString());
		return r;
	}

	public static boolean check(Map<String, Object> m, Map<String, Object> r , String key, Object v) {
		if (key != null && !(r.containsKey(key) && r.get(key).equals(v))) {
			return false;
		}
		if (r.get(m.get("cmd")).equals("OK")) {
			return true;
		}
		return false;
	}

}
