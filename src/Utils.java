import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class Utils {

	private static final int[] base64 = { 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 62, 64, 64, 64, 63, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 64, 64, 64, 64, 64, 64, 64, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 64, 64, 64, 64, 64, 64, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64 };

	public static class TeeInputStream extends InputStream {
		private final InputStream in;
		private final ByteArrayOutputStream text = new ByteArrayOutputStream();

		public TeeInputStream(InputStream in) {
			this.in = in;
		}

		public int read() throws IOException {
			int ret = in.read();
			text.write(ret);
			text.flush();
			return ret;
		}

		public int read(byte[] b, int off, int len) throws IOException {
			int ret = in.read(b, off, len);
			if (ret != -1) {
				text.write(b, off, ret);
				text.flush();
			}
			return ret;
		}

		public void close() throws IOException {
			in.close();
			text.close();
		}

		public String toString() {
			return text.toString();
		}
	}

	public static boolean isNullOrEmpty(String value) {
		return value == null || value.isEmpty();
	}

	public static String coalesce(String first, String... rest) {
		if (first == null) {
			for (String str : rest) {
				if (str != null) {
					return str;
				}
			}
		}
		return first;
	}

	public static String join(String separator, Iterable values) {
		StringBuilder result = new StringBuilder();
		for (Object value : values) {
			if (result.length() > 0) {
				result.append(separator);
			}
			result.append(String.valueOf(value));
		}
		return result.toString();
	}

	public static String join(String separator, Object... values) {
		return join(separator, Arrays.asList(values));
	}

	public static String readStream(InputStream is) {
		ByteArrayOutputStream contents = new ByteArrayOutputStream();
		try {
			copyStream(contents, is);
			return contents.toString();
		}
		catch (Exception e) {
			// TODO: logError()
		}
		return null;
	}

	public static void copyStream(OutputStream out, InputStream in) throws IOException {
		int len;
		byte[] buff = new byte[1024];
		while ((len = in.read(buff)) > 0) {
			out.write(buff, 0, len);
			Thread.yield();
		}
	}

	public static String toString(File file) throws IOException {
		return readStream(new FileInputStream(file));
	}


	private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy.MM.dd");

	public static String formatDate(long value) {
		return dateFormat.format(new Date(value));
	}

	public static String formatTime(long value) {
		if (value > TimeUnit.MINUTES.toMillis(1)) {
			double val = (double)value / TimeUnit.MINUTES.toMillis(1);
			return String.format("%.2f min", val);
		}
		if (value > TimeUnit.SECONDS.toMillis(1)) {
			double val = (double)value / TimeUnit.SECONDS.toMillis(1);
			return String.format("%.2f sec", val);
		}
		double val = (double)value / TimeUnit.MILLISECONDS.toMillis(1);
		return String.format("%.2f ms", val);
	}

	public static String formatSize(long sizeBytes) {
		if (sizeBytes > (1 << 30)) {
			return String.format("%.2f GB", (double) sizeBytes / (1 << 30));
		}
		if (sizeBytes > (1 << 20)) {
			return String.format("%.2f MB", (double) sizeBytes / (1 << 20));
		}
		if (sizeBytes > (1 << 10)) {
			return String.format("%.2f KB", (double) sizeBytes / (1 << 10));
		}
		return String.format("%d Bytes", sizeBytes);
	}

	public static String formatSpeed(long sizeBytes, long timeMillis) {
		if (timeMillis > 0) {
			sizeBytes = sizeBytes * 1000 / timeMillis;
		}
		if (sizeBytes > (1 << 30)) {
			return String.format("%.2f GB/s", (double) sizeBytes / (1 << 30));
		}
		if (sizeBytes > (1 << 20)) {
			return String.format("%.2f MB/s", (double) sizeBytes / (1 << 20));
		}
		if (sizeBytes > (1 << 10)) {
			return String.format("%.2f KB/s", (double) sizeBytes / (1 << 10));
		}
		return String.format("%d Bytes/s", sizeBytes);
	}

	public static String base64Decode(String orig) {
		char[] chars = orig.toCharArray();
		StringBuffer sb = new StringBuffer();
		int i = 0;

		int shift = 0;
		int acc = 0;

		for (i = 0; i < chars.length; i++) {
			int v = base64[(chars[i] & 0xFF)];

			if (v >= 64) {
				if (chars[i] != '=') {
					System.out.println("Wrong char in base64: " + chars[i]);
				}
			}
			else {
				acc = acc << 6 | v;
				shift += 6;
				if (shift >= 8) {
					shift -= 8;
					sb.append((char)(acc >> shift & 0xFF));
				}
			}
		}
		return sb.toString();
	}

	public static long addToArchive(ZipOutputStream out, String prefix, File file) {
		if (Utils.isNullOrEmpty(prefix)) {
			prefix = file.getName();
		} else {
			prefix += '/' + file.getName();
		}

		FileInputStream src = null;
		long size = 0;
		try {
			if (file.isDirectory()) {
				File[] files = file.listFiles();
				if (files == null) {
					throw new IOException("Can not list content of: " + file.getPath());
				}
				for (File item : files) {
					size += addToArchive(out, prefix, item);
				}
			} else {
				src = new FileInputStream(file);
				out.putNextEntry(new ZipEntry(prefix));
				Utils.copyStream(out, src);
				out.closeEntry();
				out.flush();
				size += file.length();
			}
		} catch (Exception e) {
			HttpServer.log(e, "Error archiving: `%s`", file.getPath());
		} finally {
			if (src != null) {
				try {
					src.close();
				} catch (Exception e) {
					//
				}
			}
		}
		return size;
	}

	interface FileProcessor {
		void onFile(String path, File file);
		void onDirectory(String path, File file);
		void onError(String path, File file, Exception error);
	}

	public static void processFilesRecursive(String path, File file, FileProcessor processor) {
		if (file.isDirectory()) {
			File[] files = file.listFiles();
			if (files == null) {
				processor.onError(path, file, new IOException("Can not list content of: " + file.getPath()));
				return;
			}
			processor.onDirectory(path, file);
			for (File item : files) {
				processFilesRecursive(path + '/' + item.getName(), item, processor);
			}
		}
		else {
			processor.onFile(path, file);
		}
	}
}
