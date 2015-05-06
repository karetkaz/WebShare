import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.text.ParseException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template engine
 * a template can contain blocks and variables:
 * <.name/>                             // simple variable
 * <.name=…/>                          // simple variable with default value
 * <!--.name/-->                        // hidden template variable
 * <!--.name=…/-->                     // hidden template variable with default value
 * <!--.name-->…<!--./name-->          // block variable with visible template content
 *
 */
public class HtmlTemplate {
	private static final boolean debugMode = false;

	private static final Pattern templateBlock = Pattern.compile("<(?:!--)?[.](/)?([a-zA-Z0-9_]+)((=[ a-zA-Z0-9_]+)?/)?(?:--)?>");

	private final Map<String, List<HtmlTemplate>> blocks = new HashMap<String, List<HtmlTemplate>>();
	private HtmlTemplate[] content = null;
	private StringBuffer value = null;
	private String defValue = null;

	public HtmlTemplate(File html) {
		try {
			parse(templateBlock.matcher(Utils.toString(html)));
		}
		catch (Exception e) {
			this.value = new StringBuffer(e.toString());
		}
	}

	private HtmlTemplate(String value) {
		if (value != null) {
			this.value = new StringBuffer(value);
		}
		else {
			this.value = null;
		}
	}

	// set a variable
	public void set(String key, String value, boolean recursive) {
		if (this.blocks.containsKey(key)) {
			for (HtmlTemplate var : this.blocks.get(key)) {
				if (value == null) {
					var.value = new StringBuffer(var.defValue);
				}
				else {
					var.value = new StringBuffer(value);
				}
			}
		}
		if (recursive) {
			for (HtmlTemplate vars : this.content) {
				vars.set(key, value, true);
			}
		}
	}

	// set a variable
	public void set(String key, String value) {
		set(key, value, false);
	}

	public void set(String key, boolean visible) {
		//set(key, visible ? null : "", false);
		List<HtmlTemplate> result = this.blocks.get(key);
		if (result != null) {
			for (HtmlTemplate template : result) {
				template.value = visible ? new StringBuffer(template.defValue) : null;
			}
		}
	}

	/*/ set block visibility
	public void show(String key, boolean visible) {
		List<HtmlTemplate> result = this.blocks.get(key);
		if (result != null) {
			for (HtmlTemplate template : result) {
				template.value = visible ? new StringBuffer(template.defValue) : null;
			}
		}
	}*/

	// add a block
	public HtmlTemplate add(String key) {
		List<HtmlTemplate> blocks = this.blocks.get(key);
		if (blocks == null || blocks.size() != 1) {
			return null;
		}

		HtmlTemplate template = blocks.get(0);
		if (template.value != null) {
			// flush previous values.
			template.append(template.value);
		}
		else {
			template.value = new StringBuffer();
		}
		return template;
	}

	public void clean() {
		if (this.content != null) {
			for (HtmlTemplate blocks : this.content) {
				blocks.clean(true);
			}
		}
	}

	private void clean(boolean recursive) {
		if (this.content != null) {
			if (recursive) {
				for (HtmlTemplate blocks : this.content) {
					blocks.clean(true);
				}
			}
			this.value = null;
		}
	}

	private void parse(Matcher matcher) throws ParseException {

		class ScopeStack {

			class Entry {
				final String name;
				final HtmlTemplate template;
				final List<HtmlTemplate> content;

				Entry(HtmlTemplate template, String name) {
					this.name = name;
					this.template = template;
					this.content = new ArrayList<HtmlTemplate>();
				}
			}

			private final Stack<Entry> stack;

			public HtmlTemplate pop() {
				Entry item = stack.pop();
				item.template.content = item.content.toArray(new HtmlTemplate[item.content.size()]);
				return item.template;
			}

			public ScopeStack(HtmlTemplate root) {
				stack = new Stack<Entry>();
				stack.push(new Entry(root, null));
			}

			public void push(HtmlTemplate block, String name) {
				add(block, name);
				stack.push(new Entry(block, name));
			}

			public void add(HtmlTemplate block) {
				add(block, null);
			}

			public void add(HtmlTemplate block, String name) {
				stack.peek().content.add(block);
				if (!Utils.isNullOrEmpty(name)) {
					HtmlTemplate tpl = stack.peek().template;
					if (!tpl.blocks.containsKey(name)) {
						tpl.blocks.put(name, new ArrayList<HtmlTemplate>());
					}
					tpl.blocks.get(name).add(block);
				}
			}

			public boolean empty() {
				return stack.empty();
			}

			public String name() {
				return stack.peek().name;
			}
		}

		ScopeStack stack = new ScopeStack(this);

		while (matcher.find()) {

			StringBuffer sb = new StringBuffer();
			matcher.appendReplacement(sb, "");
			stack.add(new HtmlTemplate(sb.toString()));

			String blockEnd = matcher.group(1);
			String blockName = matcher.group(2);
			String blockValue = matcher.group(3);

			if (Utils.isNullOrEmpty(blockName)) {
				throw new ParseException("Each template block must have a name.", matcher.start());
			}

			// end a template block
			if (!Utils.isNullOrEmpty(blockEnd)) {
				if (!Utils.isNullOrEmpty(blockValue)) {
					throw new ParseException("Invalid block: " + matcher.group(), matcher.start());
				}
				if (stack.empty()) {
					throw new ParseException("Unexpected end of block", matcher.start());
				}
				if (!blockName.equals(stack.name())) {
					throw new ParseException(String.format("Unmatched end of block: expected: %s / got: %s", stack.name(), blockName), matcher.start());
				}
				stack.pop();
			}

			// begin a template block or variable
			else {
				HtmlTemplate template = new HtmlTemplate((String)null);
				if (!Utils.isNullOrEmpty(blockValue)) {
					stack.add(template, blockName);
					if (blockValue.equals("/")) {
						if (debugMode) {
							template.defValue = matcher.group();
							template.value = new StringBuffer(template.defValue);
						}
					}
					else {
						template.defValue = blockValue.substring(1, blockValue.length() - 1);
					}
				}
				else {
					stack.push(template, blockName);
				}
			}
		}

		StringBuffer sb = new StringBuffer();
		matcher.appendTail(sb);
		stack.add(new HtmlTemplate(sb.toString()));

		String blockName = stack.name();
		if (stack.pop() != this && !stack.empty()) {
			throw new ParseException("unclosed block: " + blockName, 0);
		}
		this.value = new StringBuffer();
	}

	private void append(StringBuffer sb) {
		if (this.value == null) {
			// hidden block
			return;
		}

		if (sb != this.value) {
			sb.append(this.value);
		}
		if (this.content != null) {
			for (HtmlTemplate blocks : this.content) {
				blocks.append(sb);
			}
		}
	}

	public Writer write(Writer out) throws IOException {
		if (this.value == null) {
			// hidden block
			return out;
		}

		out.write(this.value.toString());
		if (this.content != null) {
			for (HtmlTemplate blocks : this.content) {
				blocks.write(out);
			}
		}
		return out;
	}

	@Override
	public String toString() {
		StringWriter result = new StringWriter();
		try {
			this.write(result);
		} catch (IOException e) {
			result.write(e.getMessage());
		}
		return result.toString();
	}
}
