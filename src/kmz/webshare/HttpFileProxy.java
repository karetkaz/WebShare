package kmz.webshare;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Collections;

public class HttpFileProxy implements HttpHandler {

	private static final boolean DEBUG = false;

	private final String repo;
	private final boolean readOnly;
	private final HttpServer server;

	public HttpFileProxy(WebShare server, String repo) {
		this.repo = repo;
		this.server = server;
		this.readOnly = server.readOnly;

		// customize headers
		server.headerMap.put("Accept-encoding", "");	// gzipped content not supported
		server.headerMap.put("Origin", repo);
		server.headerMap.put("Host", repo);
	}

	protected static void writeResponse(HttpExchange context, int responseCode, String string) throws IOException {
		byte[] response = string.getBytes();
		context.getResponseHeaders().add(HttpServer.CONTENT_TYPE, HttpServer.CONTENT_TYPE_TEXT_HTML_CHARSET);
		context.sendResponseHeaders(responseCode, response.length);
		context.getResponseBody().write(response);
	}

	private void writeResponse(HttpExchange context, int responseCode, File file) throws IOException {
		FileInputStream in = null;
		try {
			String range = context.getRequestHeaders().getFirst(HttpServer.RANGE);
			long start = 0, end = file.length();
			if (range != null && range.startsWith("bytes=")) {
				int startPos = 6;
				int endPos = range.indexOf('-', 6);
				start = Long.parseLong(range.substring(startPos, endPos));

				if (endPos > 0 && endPos + 1 < range.length()) {
					end = Math.min(end, Long.parseLong(range.substring(endPos + 1)));
				}
				String contentRange = String.format("bytes %d-%d/%d", start, end - 1, file.length());
				context.getResponseHeaders().add(HttpServer.CONTENT_RANGE, contentRange);
				WebShare.log("Range request: %d - %d: %s", start, end, range);
				WebShare.log("Range response: %s", contentRange);
			}
			context.getResponseHeaders().add(HttpServer.CONTENT_TYPE, this.server.getContentType(file));
			context.sendResponseHeaders(responseCode, file.length());

			OutputStream out = context.getResponseBody();
			in = new FileInputStream(file);
			in.skip(start);
			byte[] buff = new byte[1024];
			while (start < end) {
				int n = in.read(buff);
				out.write(buff, 0, n);
				start += n;
			}
		}
		finally {
			Utils.close(in);
		}
	}

	private static class CloneOutputStream extends OutputStream {
		final OutputStream output1;
		final OutputStream output2;

		CloneOutputStream(OutputStream output1, OutputStream output2) {
			this.output1 = output1;
			this.output2 = output2;
		}

		@Override
		public void write(int b) throws IOException {
			output1.write(b);
			output2.write(b);
		}

		@Override
		public void write(byte[] b) throws IOException {
			output1.write(b);
			output2.write(b);
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			output1.write(b, off, len);
			output2.write(b, off, len);
		}

		@Override
		public void flush() throws IOException {
			super.flush();
			output1.flush();
			output2.flush();
		}

		@Override
		public void close() throws IOException {
			super.close();
			Utils.close(output1);
			Utils.close(output2);
		}
	}

	@Override
	public void handle(HttpExchange context) {
		long ts = System.currentTimeMillis();
		String method = context.getRequestMethod();
		String path = context.getRequestURI().getPath();
		String query = context.getRequestURI().getQuery();

		WebShare.log("handle: %s", path);
		File file = server.getLocalPath(path);
		try {
			// try to fallback to index.html
			if (HttpServer.METHOD_GET.equals(method)) {
				if (file.exists() && file.isDirectory()) {
					file = new File(file, "index.html");
				}
				else if (path.endsWith("/")) {
					file = new File(file, "index.html");
				}
			}

			if (file.exists() && file.isFile()) {
				// return it from disk no matter if POST or GET.
				writeResponse(context, HttpURLConnection.HTTP_OK, file);
				method = HttpServer.METHOD_CACHED;
				return;
			}

			// fallback to method directory
			File file2 = server.getLocalPath(String.format("__%s/%s", method, path));
			if (file2.exists() && file2.isFile()) {
				// return it from disk no matter if POST or GET.
				writeResponse(context, HttpURLConnection.HTTP_OK, file2);
				method = HttpServer.METHOD_CACHED;
				return;
			}

			if (Utils.isNullOrEmpty(this.repo)) {
				writeResponse(context, HttpURLConnection.HTTP_NOT_FOUND, "Not found");
			}

			// send request to repo backend
			URL url = new URL(this.repo + context.getRequestURI());
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();

			conn.setRequestMethod(method);
			copyRequestHeaders(context, conn);

			// send request body
			sendRequestBody(context, conn);

			// get response code, headers, body
			int responseCode = conn.getResponseCode();
			sendResponseHeaders(context, conn, responseCode);

			boolean cacheFile = !this.readOnly;
			if (responseCode >= 300) {
				cacheFile = false;
			}

			// read and save response body
			InputStream in = null;
			OutputStream out = null;
			try {
				out = context.getResponseBody();
				if (cacheFile) {
					if (!HttpServer.METHOD_GET.equals(method) || !Utils.isNullOrEmpty(query)) {
						file = server.getLocalPath(String.format("__%s/%s.%08x", method, path, ts));
					}
					if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
						throw new IOException("can not create path for: " + file.getParentFile().getCanonicalPath());
					}
					out = new CloneOutputStream(out, new FileOutputStream(file));
				}

				in = conn.getInputStream();
				Utils.copyStream(out, in);
			}
			catch (Exception e) {
				//WebShare.log(e);
			}
			finally {
				Utils.close(in);
				Utils.close(out);
			}
		}
		catch (Exception e) {
			WebShare.log(e, "Failed to download: `%s`", path);
		}
		finally {
			context.close();
			double time = (System.currentTimeMillis() - ts) / 1000.;
			WebShare.log("%s[%f]: %s -> %s", method, time, context.getRequestURI().toString(), file.getAbsolutePath());
		}
	}

	private void copyRequestHeaders(HttpExchange context, HttpURLConnection conn) {
		for (String key : context.getRequestHeaders().keySet()) {

			String original = context.getRequestHeaders().getFirst(key);
			String value = server.remapHeader(key, original);

			if (HttpServer.REFERER.equalsIgnoreCase(key)) {
				String path = URI.create(original).getPath();
				value = URI.create(this.repo + path).toString();
			}

			if (DEBUG && (value == null || !value.equals(original))) {
				WebShare.log("header [%s]: `%s` => `%s`", key, original, value);
			}

			if (value == null || value.isEmpty()) {
				// ignore header
				continue;
			}

			conn.setRequestProperty(key, value);
		}
	}

	private void sendResponseHeaders(HttpExchange context, HttpURLConnection conn, int responseCode) throws IOException {
		for (String key : conn.getHeaderFields().keySet()) {
			if (key == null) {
				continue;
			}
			context.getResponseHeaders().put(key, conn.getHeaderFields().get(key));
		}
		context.getResponseHeaders().put(HttpServer.CONTENT_TYPE, Collections.singletonList(conn.getContentType()));
		context.sendResponseHeaders(responseCode, conn.getHeaderFieldLong(HttpServer.CONTENT_LENGTH, 0));
		//context.sendResponseHeaders(responseCode, responseCode == 304 ? -1 : 0);
	}

	private void sendRequestBody(HttpExchange context, HttpURLConnection conn) {
		InputStream in = null;
		OutputStream out = null;
		try {
			in = context.getRequestBody();
			int firstByte = in.read();
			if (firstByte != -1) {
				conn.setDoOutput(true);
				out = conn.getOutputStream();

				out.write(firstByte);
				int len;
				byte[] buff = new byte[1024];
				while ((len = in.read(buff)) > 0) {
					out.write(buff, 0, len);
					Thread.yield();
				}
			}
		}
		catch (Exception e) {
			String path = context.getRequestURI().getPath();
			WebShare.log(e, "Error forwarding request: `%s`", path);
		}
		finally {
			Utils.close(in);
			Utils.close(out);
		}
	}
}
