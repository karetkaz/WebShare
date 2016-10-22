package kmz.webshare;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class Utils {

	private static final int[] base64 = {64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 62, 64, 64, 64, 63, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 64, 64, 64, 64, 64, 64, 64, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 64, 64, 64, 64, 64, 64, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64};

	public static boolean isNullOrEmpty(String value) {
		return value == null || value.isEmpty();
	}

	public static String coalesce(String... values) {
		for (String str : values) {
			if (str != null) {
				return str;
			}
		}
		return null;
	}

	public static String toString(String separator, Iterable values) {
		StringBuilder result = new StringBuilder();
		for (Object value : values) {
			if (result.length() > 0) {
				result.append(separator);
			}
			result.append(String.valueOf(value));
		}
		return result.toString();
	}

	public static String toString(String separator, Object... values) {
		return toString(separator, Arrays.asList(values));
	}

	public static String toString(InputStream is) {
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

	public static void close(Closeable closeable) {
		try {
			if (closeable != null) {
				closeable.close();
			}
		}
		catch (Exception ignore) {
		}
	}


	private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy.MM.dd");

	public static String formatDate(long value) {
		return dateFormat.format(new Date(value));
	}

	public static String formatTime(long millis) {
		double value = millis;
		String suffix = "millis";

		if (millis > TimeUnit.DAYS.toMillis(1)) {
			value /= TimeUnit.DAYS.toMillis(1);
			suffix = TimeUnit.DAYS.name().toLowerCase();
		}
		else if (millis > TimeUnit.HOURS.toMillis(1)) {
			value /= TimeUnit.HOURS.toMillis(1);
			suffix = TimeUnit.HOURS.name().toLowerCase();
		}
		else if (millis > TimeUnit.MINUTES.toMillis(1)) {
			value /= TimeUnit.MINUTES.toMillis(1);
			suffix = TimeUnit.MINUTES.name().toLowerCase();
		}
		else if (millis > TimeUnit.SECONDS.toMillis(1)) {
			value /= TimeUnit.SECONDS.toMillis(1);
			suffix = TimeUnit.SECONDS.name().toLowerCase();
		}

		return String.format("%.1f %s", value, suffix);
	}

	public static String formatSize(long bytes) {
		double value = bytes;
		String suffix = "Bytes";

		if (bytes > (1 << 30)) {
			value /= (1 << 30);
			suffix = "GB";
		}
		else if (bytes > (1 << 20)) {
			value /= (1 << 20);
			suffix = "MB";
		}
		else if (bytes > (1 << 10)) {
			value /= (1 << 10);
			suffix = "KB";
		}

		return String.format("%.2f %s", value, suffix);
	}

	public static String formatSpeed(long bytes, long millis) {
		if (millis <= 0 || bytes <= 0) {
			return "Unknown";
		}

		double value = bytes * 1000 / millis;
		String suffix = "Bytes/s";

		bytes = bytes * 1000 / millis;
		if (bytes > (1 << 30)) {
			value /= (1 << 30);
			suffix = "GB/s";
		}
		else if (bytes > (1 << 20)) {
			value /= (1 << 20);
			suffix = "MB/s";
		}
		else if (bytes > (1 << 10)) {
			value /= (1 << 10);
			suffix = "KB/s";
		}
		return String.format("%.2f %s", value, suffix);
	}

	public static String encodeUri(String s) {
		String result;

		try {
			result = URLEncoder.encode(s, "UTF-8")
					.replaceAll("\\+", "%20")
					.replaceAll("\\%21", "!")
					.replaceAll("\\%27", "'")
					.replaceAll("\\%28", "(")
					.replaceAll("\\%29", ")")
					.replaceAll("\\%7E", "~");
		}
		catch (Exception e) {
			result = s;
		}

		return result;
	}

	public static String decodeUri(String s) {
		String result;

		try {
			result = URLDecoder.decode(s, "UTF-8");
		}
		catch (Exception e) {
			result = s;
		}

		return result;
	}

	public static String decodeBase64(String string) {
		char[] chars = string.toCharArray();
		StringBuilder sb = new StringBuilder();

		int shift = 0;
		int acc = 0;

		for (char chr : chars) {
			int v = base64[(chr & 0xFF)];

			if (v >= 64) {
				if (chr != '=') {
					System.out.println("Wrong char in base64: " + chr);
				}
			}
			else {
				acc = acc << 6 | v;
				shift += 6;
				if (shift >= 8) {
					shift -= 8;
					sb.append((char) (acc >> shift & 0xFF));
				}
			}
		}
		return sb.toString();
	}

	public static void addToArchive(ZipOutputStream out, String prefix, File file) {
		if (Utils.isNullOrEmpty(prefix)) {
			prefix = file.getName();
		}
		else {
			prefix += '/' + file.getName();
		}

		FileInputStream src = null;
		try {
			if (file.isDirectory()) {
				File[] files = file.listFiles();
				if (files == null) {
					throw new IOException("Can not list content of: " + file.getPath());
				}
				for (File item : files) {
					addToArchive(out, prefix, item);
				}
			}
			else {
				src = new FileInputStream(file);
				out.putNextEntry(new ZipEntry(prefix));
				Utils.copyStream(out, src);
				out.closeEntry();
				out.flush();
			}
		}
		catch (Exception ignore) {
		}
		finally {
			Utils.close(src);
		}
	}

	public interface FileProcessor {

		void onFile(String path, File file);

		boolean onDirectory(String path, File file);

		void onError(String path, File file, Exception error);
	}

	public static void processFilesRecursive(String path, File file, FileProcessor processor) {
		if (file.isDirectory()) {
			File[] files = file.listFiles();
			if (files == null) {
				processor.onError(path, file, new IOException("Can not list content of: " + file.getPath()));
				return;
			}
			if (!processor.onDirectory(path, file)) {
				return;
			}
			for (File item : files) {
				processFilesRecursive(path + '/' + item.getName(), item, processor);
			}
		}
		else {
			processor.onFile(path, file);
		}
	}
}
