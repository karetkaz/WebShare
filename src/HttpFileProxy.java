import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.zip.CRC32;

public class HttpFileProxy implements HttpHandler {

	public static final String REFERER = "Referer";

	public static void main(String[] args) throws IOException {
		String host = "http://localhost";
		int port = 8090;
		String wdir = ".";
		String repoUrl = null;
		int threads = 256;

		if (args.length > 0) {
			int arg;
			for (arg = 0; arg < args.length - 1; arg += 1) {
				if ("-repo".equals(args[arg])) {
					if ((arg += 1) < args.length) {
						repoUrl = args[arg];
					}
				}
				else if ("-host".equals(args[arg])) {
					if ((arg += 1) < args.length) {
						host = args[arg];
					}
				}
				else if ("-port".equals(args[arg])) {
					if ((arg += 1) < args.length) {
						port = Integer.parseInt(args[arg]);
					}
				}
				/*else if ("-log".equals(args[arg])) {
					if ((arg += 1) < args.length) {
						logFile = new File(args[arg]);
					}
				}*/
				else if ("-n".equals(args[arg])) {
					if ((arg += 1) < args.length) {
						threads = Integer.parseInt(args[arg]);
					}
				}
				else {
					HttpServer.log("invalid argument: `%s`", args[arg]);
					return;
				}
			}
			if (arg < args.length) {
				String path = args[arg].trim();
				if (!path.isEmpty()) {
					wdir = path;
				}
			}
		}

		final File root = new File(wdir).getCanonicalFile();
		com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0);
		server.createContext("/", new HttpFileProxy(root, repoUrl));
		if (threads > 0) {
			server.setExecutor(Executors.newFixedThreadPool(threads));
		}
		server.start();
		HttpServer.log("Server started: %s:%s using %d threads in folder: `%s`", host, server.getAddress().getPort(), threads, root.getAbsolutePath());
	}

	private final File root;
	private final String repo;

	public HttpFileProxy(File root, String repo) {
		this.root = root;
		this.repo = repo;

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
			context.getResponseHeaders().add(HttpServer.CONTENT_TYPE, HttpServer.getContentType(file));
			context.sendResponseHeaders(responseCode, file.length());

			OutputStream out = context.getResponseBody();
			in = new FileInputStream(file);
			Utils.copyStream(out, in);
		}
		finally {
			if (in != null) {
				in.close();
			}
		}
	}

	private Map<String, String> remapHeaderKeys = new HashMap<String, String>();

	@Override
	public void handle(HttpExchange context) {
		final String method = context.getRequestMethod();
		final String path = context.getRequestURI().getPath();
		final String query = context.getRequestURI().getQuery();

		File file = new File(root, path);
		try {
			if (file.exists() && !file.isDirectory()) {
				// return it from disk.
				//HttpServer.log("cached: `%s`", file.getName());
				writeResponse(context, 200, file);
				return;
			}

			ByteArrayOutputStream postBody = new ByteArrayOutputStream();
			if (HttpServer.METHOD_POST.equals(method)) {
				try {
					Utils.copyStream(postBody, context.getRequestBody());
				}
				catch (Exception e) {
					HttpServer.log(e, "Error forwarding POST data");
				}
				if (!file.exists() || file.isDirectory()) {
					CRC32 crc = new CRC32();
					crc.update(postBody.toByteArray());

					String from = null;
					try {
						from = context.getRequestHeaders().getFirst(REFERER);
						if (from != null) {
							from = URI.create(from).getPath();
						}
					}
					catch (Exception e) {
						HttpServer.log(e);
					}
					String code = path;
					if (code.endsWith("/")) {
						code = code.substring(0, code.length() - 1);
					}
					if (code.startsWith("/")) {
						code = code.substring(1, code.length());
					}
					if (from != null && !from.isEmpty()) {
						if (from.endsWith("/")) {
							from = from.substring(0, from.length() - 1);
						}
						if (from.startsWith("/")) {
							from = from.substring(1, from.length());
						}
						//code += "/" + from;
						from = from.replace('/', '.');
					}
					file = new File(root, String.format("%s.post/%s.%08x", code, from, crc.getValue()));
				}
			}
			else if (file.isDirectory() || path.endsWith("/") || query != null) {
				CRC32 crc = new CRC32();
				if (query != null) {
					crc.update(query.getBytes());
				}
				file = new File(new File(root, path), String.format("index.%08x.html", crc.getValue()));
			}

			//log("get: %s -> %s", context.getRequestURI(), file.getName());

			if (file.exists() && !file.isDirectory()) {
				// return it from disk.
				writeResponse(context, 200, file);
			}
			else if (!Utils.isNullOrEmpty(this.repo)) {
				URL url = new URL(this.repo + context.getRequestURI());
				HttpURLConnection conn = (HttpURLConnection) url.openConnection();
				conn.setRequestMethod(method);
				// send request headers
				for (String key : context.getRequestHeaders().keySet()) {
					// set the referer page
					if (REFERER.equalsIgnoreCase(key)) {
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
					//InputStream in = context.getRequestBody();
					InputStream in = new ByteArrayInputStream(postBody.toByteArray());
					if (in.available() > 0) {
						conn.setDoOutput(true);
						OutputStream out = conn.getOutputStream();
						Utils.copyStream(out, in);
						out.close();
					}
					in.close();
				}
				catch (Exception e) {
					HttpServer.log(e, "Error forwarding request: `%s`", path);
				}

				context.sendResponseHeaders(conn.getResponseCode(), 0);
				context.getResponseHeaders().add(HttpServer.CONTENT_TYPE, conn.getContentType());

				// skip response headers
				// read and save response body
				HttpServer.log("downloading: %s -> %s", url.toString(), file.getAbsolutePath());
				if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
					throw new IOException("can not create path for: " + file.getAbsolutePath());
				}

				OutputStream outHttp = context.getResponseBody();
				OutputStream outFile = new FileOutputStream(file);
				InputStream in = conn.getInputStream();

				int len;
				byte[] buff = new byte[1024];
				while ((len = in.read(buff)) > 0) {
					outFile.write(buff, 0, len);
					try {
						outHttp.write(buff, 0, len);
					}
					catch (Exception e) {
						// broken pipe ?
						//log(e.getMessage());
					}
					//Thread.yield();
				}
				in.close();
				outFile.close();
				outHttp.close();
			}
			else {
				writeResponse(context, 404, "Not found");
			}
		}
		catch (Exception e) {
			HttpServer.log(e, "Failed to download: `%s`", path);
		}
		finally {
			context.close();
		}
	}

}
