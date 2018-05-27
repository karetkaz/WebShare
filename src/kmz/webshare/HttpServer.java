package kmz.webshare;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.tastefuljava.sceyefi.multipart.Multipart;
import org.tastefuljava.sceyefi.multipart.Part;
import org.tastefuljava.sceyefi.multipart.ValueParser;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipOutputStream;

public abstract class HttpServer implements HttpHandler {

	protected class Request {
		public final String path;
		public final String method;
		private final HttpExchange context;
		private final Map<String, Object> extras;

		public Request(HttpExchange context) {
			this.context = context;
			this.method = context.getRequestMethod();
			this.path = context.getRequestURI().getPath();
			this.extras = new HashMap<>();
		}

		public File getLocalPath() {
			return HttpServer.this.getLocalPath(this.path);
		}

		public File getLocalPath(String path) {
			return new File(this.getLocalPath(), path);
		}

		public Object getExtra(String key) {
			return this.extras.get(key);
		}

		public void putExtra(String key, Object value) {
			this.extras.put(key, value);
		}

		public Headers getHeaders() {
			return this.context.getRequestHeaders();
		}

		public String getFirstHeader(String key) {
			return this.context.getRequestHeaders().getFirst(key);
		}

		public String getQuery() {
			return this.context.getRequestURI().getQuery();
		}

		public InputStream getBody() {
			return this.context.getRequestBody();
		}

		public InetSocketAddress getRemoteAddress() {
			return this.context.getRemoteAddress();
		}
	}

	protected class Response {
		private final Request request;
		private final HttpExchange context;
		private final Map<String, Object> extras;

		private String contentType = null;
		private int responseCode = HttpURLConnection.HTTP_OK;

		public Response(Request request) {
			this.context = request.context;
			this.extras = request.extras;
			this.request = request;
		}

		public File getLocalPath() {
			return this.request.getLocalPath();
		}

		public Object getExtra(String key) {
			return this.extras.get(key);
		}

		public void putExtra(String key, Object value) {
			this.extras.put(key, value);
		}

		public void setResponseCode(int responseCode) {
			this.responseCode = responseCode;
		}

		public void setContentType(String contentType) {
			this.contentType = contentType;
		}

		public Headers getHeaders() {
			return this.context.getResponseHeaders();
		}

		public List<String> getHeader(String key) {
			return this.context.getResponseHeaders().get(key);
		}

		public String getFirstHeader(String key) {
			return this.context.getResponseHeaders().getFirst(key);
		}

		public void putHeader(String key, String... values) {
			this.context.getResponseHeaders().put(key, Arrays.asList(values));
		}

		private void sendResponseHeaders(long responseLength) throws IOException {
			this.context.sendResponseHeaders(this.responseCode, responseLength);
			//this.headersSent = true;
		}

		public void write(String string) throws IOException {
			byte[] response = string.getBytes();
			this.context.getResponseHeaders().add(CONTENT_TYPE, CONTENT_TYPE_TEXT_HTML_CHARSET);
			this.sendResponseHeaders(response.length);
			this.context.getResponseBody().write(response);
		}

		public long write(String attachment, File file) throws IOException {
			InputStream in = null;
			if (this.contentType == null) {
				this.contentType = HttpServer.this.getContentType(file);
			}
			try {
				long start = 0, end = file.length();
				if (attachment != null) {
					context.getResponseHeaders().add(CONTENT_DISPOSITION, "attachment; filename=" + attachment);
				}
				String range = context.getRequestHeaders().getFirst(HttpServer.RANGE);
				if (range != null && range.startsWith("bytes=")) {
					int startPos = 6;
					int endPos = range.indexOf('-', 6);
					start = Long.parseLong(range.substring(startPos, endPos));

					if (endPos > 0 && endPos + 1 < range.length()) {
						end = Math.min(end, Long.parseLong(range.substring(endPos + 1)));
					}
					String contentRange = String.format("bytes %d-%d/%d", start, end - 1, file.length());
					context.getResponseHeaders().add(HttpServer.CONTENT_RANGE, contentRange);
					this.setResponseCode(HttpURLConnection.HTTP_PARTIAL);

					WebShare.log("Range request: %s, response: %s", range, contentRange);
				}
				this.context.getResponseHeaders().add(CONTENT_TYPE, this.contentType);
				this.sendResponseHeaders(end - start);

				OutputStream out = this.context.getResponseBody();
				in = new FileInputStream(file);
				byte[] buff = new byte[1024];
				start = in.skip(start);
				while (start < end) {
					int n = in.read(buff);
					out.write(buff, 0, n);
					start += n;
				}
				return end - start;
			}
			finally {
				Utils.close(in);
			}
		}

		public long writeZip(String attachment, File... files) throws IOException {
			if (attachment != null) {
				this.context.getResponseHeaders().add(CONTENT_DISPOSITION, "attachment; filename=" + attachment);
			}
			this.context.getResponseHeaders().add(CONTENT_TYPE, CONTENT_TYPE_ARCHIVE_ZIP);
			this.sendResponseHeaders(0);

			ZipOutputStream out = null;
			try {
				out = new ZipOutputStream(this.context.getResponseBody());
				for (File toZip : files) {
					Utils.addToArchive(out, "", toZip);
				}
			}
			finally {
				Utils.close(out);
			}
			return 0;
		}

		public long write(HtmlTemplate template) throws IOException {
			this.context.getResponseHeaders().add(CONTENT_TYPE, CONTENT_TYPE_TEXT_HTML_CHARSET);
			this.sendResponseHeaders(0);
			Writer out = new OutputStreamWriter(this.context.getResponseBody());
			template.append(out);
			out.flush();
			return 0;
		}
	}

	protected static class Error extends Exception {
		private final int responseCode;

		public Error(int responseCode, String message, Throwable cause) {
			super(message, cause);
			this.responseCode = responseCode;
		}

		public Error(Exception cause) {
			this(HttpURLConnection.HTTP_INTERNAL_ERROR, cause.getMessage(), cause);
		}

		public Error(String message) {
			this(HttpURLConnection.HTTP_INTERNAL_ERROR, message, null);
		}
	}

	protected abstract File getLocalPath(String path);

	protected abstract String getContentType(File file);

	protected abstract String remapHeader(String key, String value);

	// Enforce to be authenticated.
	abstract boolean isAuthenticated(Request request);

	// starting a new request, return false to skip the request.
	abstract boolean beginRequest(Request request) throws Error;

	// process the request get and post params.
	abstract void processParam(Request request, String name, InputStream value, Map<String, String> params) throws Error;

	// handle write response to client.
	abstract long writeResponse(Response response, Exception error) throws IOException;

	public static final String RANGE = "Range";
	public static final String REFERER = "Referer";

	public static final String METHOD_GET = "GET";
	public static final String METHOD_POST = "POST";
	public static final String METHOD_CACHED = "FILE";
	public static final String DEFAULT_ENCODING = "UTF-8";

	protected static final String CONTENT_TYPE = "Content-type";
	protected static final String CONTENT_RANGE = "Content-range";
	protected static final String CONTENT_LENGTH = "Content-length";
	protected static final String CONTENT_DISPOSITION = "content-disposition";

	protected static final String CONTENT_TYPE_ARCHIVE_ZIP = "application/zip";
	//protected static final String CONTENT_TYPE_OCTET_STREAM = "application/octet-stream";
	protected static final String CONTENT_TYPE_URL_ENCODED_FORM = "application/x-www-form-urlencoded";

	protected static final String CONTENT_TYPE_TEXT_HTML_CHARSET = "text/html; charset=" + DEFAULT_ENCODING;
	protected static final String CONTENT_TYPE_TEXT_PLAIN_CHARSET = "text/plain; charset=" + DEFAULT_ENCODING;

	private static final InputStream emptyInputStream = new InputStream() {
		@Override
		public int read() throws IOException {
			return -1;
		}
	};

	private void processParam(Request request, String encodedParams) throws Exception {
		if (!Utils.isNullOrEmpty(encodedParams)) {
			for (String str : encodedParams.split("&")) {
				int eq = str.indexOf('=');
				if (eq >= 0) {
					String key = URLDecoder.decode(str.substring(0, eq), DEFAULT_ENCODING);
					String value = URLDecoder.decode(str.substring(eq + 1), DEFAULT_ENCODING);
					InputStream body = new ByteArrayInputStream(value.getBytes());
					HttpServer.this.processParam(request, key, body, null);
				}
				else {
					HttpServer.this.processParam(request, str, emptyInputStream, null);
				}
			}
		}
	}

	@Override
	public void handle(final HttpExchange context) {
		Exception error = null;
		long requestStart = System.currentTimeMillis();
		long responseStart = requestStart;
		long responseLength = -1;
		final Request request = new Request(context);
		final Response response = new Response(request);

		try {
			if (!this.isAuthenticated(request)) {
				response.putHeader("WWW-Authenticate", "Basic realm=\"Home Server\"");
				response.setResponseCode(HttpURLConnection.HTTP_UNAUTHORIZED);
				response.write("401 Access denied");
				return;
			}

			try {
				// begin request.
				if (!this.beginRequest(request)) {
					return;
				}

				// process query params first.
				this.processParam(request, context.getRequestURI().getQuery());

				// process post params.
				if (METHOD_POST.equals(context.getRequestMethod())) {
					String contentType = context.getRequestHeaders().getFirst(CONTENT_TYPE);

					InputStream input = context.getRequestBody();

					if (contentType != null && contentType.startsWith("multipart")) {
						Map<String, String> params = ValueParser.parse(contentType);

						String encoding = params.get("charset");
						if (encoding == null) {
							encoding = DEFAULT_ENCODING;
						}

						byte[] boundary = params.get("boundary").getBytes(encoding);
						Multipart mp = new Multipart(input, encoding, boundary);
						Part p = mp.nextPart();

						while (p != null) {
							InputStream body = p.getBody();
							try {
								String partType = p.getFirstValue(CONTENT_DISPOSITION);
								Map<String, String> param = ValueParser.parse(partType);
								String key = param.get("name");
								// process multipart params.
								HttpServer.this.processParam(request, key, body, param);
							}
							finally {
								Utils.close(body);
							}
							p = mp.nextPart();
						}
					}
					else if (CONTENT_TYPE_URL_ENCODED_FORM.equals(contentType)) {
						// process form data params.
						this.processParam(request, Utils.toString(input));
					}
					else {
						throw new Exception("content type not known: " + contentType);
					}
				}
			}
			catch (Exception e) {
				error = e;
			}

			responseStart = System.currentTimeMillis();
			responseLength = this.writeResponse(response, error);
		}
		catch (Exception e) {
			// error sending response
			error = e;
		}
		finally {
			context.close();
			long now = System.currentTimeMillis();
			String responseTime = Utils.formatTime(now - requestStart);
			if (responseLength > 0) {
				String responseSize = Utils.formatSize(responseLength);
				String responseSpeed = Utils.formatSpeed(responseLength, now - responseStart);
				WebShare.log(error, "response: %s in %s at %s: `%s`", responseSize, responseTime, responseSpeed, request.path);
			}
			else {
				WebShare.log(error, "response: in %s: `%s`", responseTime, request.path);
			}
		}
	}
}
