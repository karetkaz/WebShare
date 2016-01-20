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
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

public class HttpFileProxy implements HttpHandler {

	private final String repo;
	private final boolean readOnly;
	private final HttpServer server;

	public HttpFileProxy(WebShare server, String repo) {
		this.repo = repo;
		this.server = server;
		this.readOnly = server.readOnly;

		// customize headers
		this.remapHeaderKeys.put("Accept-encoding", null);	// gzipped content not supported
		this.remapHeaderKeys.put("Origin", repo);
		this.remapHeaderKeys.put("Host", repo);
	}

	protected static void writeResponse(HttpExchange context, int responseCode, String string) throws IOException {
		byte[] response = string.getBytes();
		context.getResponseHeaders().add(HttpServer.CONTENT_TYPE, HttpServer.CONTENT_TYPE_TEXT_HTML_CHARSET);
		context.sendResponseHeaders(responseCode, response.length);
		context.getResponseBody().write(response);
	}

	private void writeResponse(HttpExchange context, int responseCode, File file) throws IOException {
		InputStream in = null;
		try {
			context.getResponseHeaders().add(HttpServer.CONTENT_TYPE, this.server.getContentType(file));
			context.sendResponseHeaders(responseCode, file.length());

			OutputStream out = context.getResponseBody();
			in = new FileInputStream(file);
			Utils.copyStream(out, in);
		}
		finally {
			Utils.close(in);
		}
	}

	private Map<String, String> remapHeaderKeys = new HashMap<String, String>();

	private static class CloneOutputStream extends OutputStream {
		final OutputStream output1;
		final OutputStream output2;

		public CloneOutputStream(OutputStream output1, OutputStream output2) {
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
			output1.close();
			output2.close();
		}
	}

	@Override
	public void handle(HttpExchange context) {
		long ts = System.currentTimeMillis();
		String method = context.getRequestMethod();
		String path = context.getRequestURI().getPath();
		String query = context.getRequestURI().getQuery();

		File file = server.getLocalPath(path);
		try {
			if (file.exists() && !file.isDirectory()) {
				// return it from disk no matter if POST or GET.
				writeResponse(context, HttpURLConnection.HTTP_OK, file);
				method = HttpServer.METHOD_CACHED;
				return;
			}

			// fallback to method directory
			File file2 = server.getLocalPath(String.format("__%s%s", method, path));
			if (file2.exists() && !file2.isDirectory()) {
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
			CRC32 crc = new CRC32();
			if (!Utils.isNullOrEmpty(query)) {
				crc.update(query.getBytes());
			}

			conn.setRequestMethod(method);

			// send request headers
			for (String key : context.getRequestHeaders().keySet()) {
				// set the Referer page
				if (HttpServer.REFERER.equalsIgnoreCase(key)) {
					String oldPath = URI.create(context.getRequestHeaders().getFirst(key)).getPath();
					conn.setRequestProperty(key, URI.create(this.repo + oldPath).toString());
					continue;
				}
				if (remapHeaderKeys.containsKey(key)) {
					String headerValue = remapHeaderKeys.get(key);
					if (headerValue != null) {
						conn.setRequestProperty(key, headerValue);
					}
					continue;
				}
				conn.setRequestProperty(key, Utils.join(";", context.getRequestHeaders().get(key)));
			}

			// send request body
			try {
				// TODO: safe open and close streams
				InputStream in = context.getRequestBody();
				//Utils.copyStream(out, in);

				int firstByte = in.read();
				if (firstByte != -1) {
					conn.setDoOutput(true);
					OutputStream out = conn.getOutputStream();

					crc.update(firstByte);
					out.write(firstByte);
					int len;
					byte[] buff = new byte[1024];
					while ((len = in.read(buff)) > 0) {
						crc.update(buff, 0, len);
						out.write(buff, 0, len);
						Thread.yield();
					}

					out.close();
				}
				in.close();
			}
			catch (Exception e) {
				WebShare.log(e, "Error forwarding request: `%s`", path);
			}

			// get response headers
			for (String key : conn.getHeaderFields().keySet()) {
				if (key == null) {
					continue;
				}
				context.getResponseHeaders().add(key, Utils.join(";", conn.getHeaderFields().get(key)));
			}
			int responseCode = conn.getResponseCode();
			context.sendResponseHeaders(responseCode, responseCode == 304 ? -1 : 0);//conn.getContentLengthLong());
			context.getResponseHeaders().add(HttpServer.CONTENT_TYPE, conn.getContentType());

			// read and save response body
			boolean cacheFile = !this.readOnly;
			if (responseCode == 304) {
				cacheFile = false;
			}

			OutputStream out = context.getResponseBody();
			if (cacheFile) {
				if (!file.exists() || file.isDirectory()) {
					String code = path;
					if (code.endsWith("/")) {
						code = code.substring(0, code.length() - 1);
					}
					if (code.startsWith("/")) {
						code = code.substring(1, code.length());
					}

					// no query, no body, and not requesting a directory
					if (crc.getValue() == 0 && !path.endsWith("/")) {
						file = server.getLocalPath(String.format("%s", code));
					}
					else {
						file = server.getLocalPath(String.format("__%s/%s.%08x", method, code, crc.getValue()));
					}
				}
				if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
					throw new IOException("can not create path for: " + file.getAbsolutePath());
				}
				out = new CloneOutputStream(out, new FileOutputStream(file));
			}

			InputStream in = null;
			try {
				in = conn.getInputStream();
				Utils.copyStream(out, in);
			}
			catch (Exception e) {
				WebShare.log(e);
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
			WebShare.log("%s[%f]: %s -> %s", method, time , context.getRequestURI().toString(), file.getAbsolutePath());
		}
	}
}
