package kmz.webshare;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.text.ParseException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template engine
 * a template can contain blocks and variables:
 * <.name/>                      // simple variable
 * <.name="…"/>                  // simple variable with default value
 * <!--.name/-->                 // hidden template variable
 * <!--.name='…'/-->             // hidden template variable with default value
 * <!--.name-->…<!--./name-->    // block variable with visible template content
 * TODO: javadoc
 */
public class HtmlTemplate {
	private static final boolean debugMode = false;

	private static final Pattern templateBlock = Pattern.compile("<(?:!--)?[.](/)?([a-zA-Z0-9_]+)((='[^']+')?/)?(?:--)?>");

	private final Map<String, List<HtmlTemplate>> variables = new HashMap<>();
	private HtmlTemplate[] blocks = null;

	private final StringBuffer value = new StringBuffer();
	private String defValue = null;
	private boolean visible = true;

	public HtmlTemplate() {
	}

	public void parse(InputStream stream) throws ParseException {
		class ScopeStack {

			class Entry {
				final String name;
				final HtmlTemplate template;
				final List<HtmlTemplate> content;

				Entry(HtmlTemplate template, String name) {
					this.name = name;
					this.template = template;
					this.content = new ArrayList<>();
				}
			}

			private final Stack<Entry> stack;

			private HtmlTemplate pop() {
				Entry item = stack.pop();
				item.template.blocks = item.content.toArray(new HtmlTemplate[item.content.size()]);
				return item.template;
			}

			private ScopeStack(HtmlTemplate root) {
				stack = new Stack<>();
				stack.push(new Entry(root, null));
			}

			private void push(HtmlTemplate block, String name) {
				add(block, name);
				stack.push(new Entry(block, name));
			}

			private void add(HtmlTemplate block) {
				add(block, null);
			}

			private void add(HtmlTemplate block, String name) {
				stack.peek().content.add(block);
				if (!Utils.isNullOrEmpty(name)) {
					HtmlTemplate tpl = stack.peek().template;
					if (!tpl.variables.containsKey(name)) {
						tpl.variables.put(name, new ArrayList<HtmlTemplate>());
					}
					tpl.variables.get(name).add(block);
				}
			}

			private boolean empty() {
				return stack.empty();
			}

			private String name() {
				return stack.peek().name;
			}
		}

		ScopeStack stack = new ScopeStack(this);
		String fileContent = Utils.toString(stream);
		Matcher matcher = templateBlock.matcher(Utils.coalesce(fileContent, ""));

		while (matcher.find()) {

			HtmlTemplate block = new HtmlTemplate();
			matcher.appendReplacement(block.value, "");
			stack.add(block);

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
				HtmlTemplate template = new HtmlTemplate();
				if (!Utils.isNullOrEmpty(blockValue)) {
					stack.add(template, blockName);
					if (blockValue.equals("/")) {
						if (debugMode) {
							template.defValue = matcher.group();
							template.value.append(template.defValue);
						}
					}
					else {
						template.defValue = blockValue.substring(2, blockValue.length() - 2);
					}
				}
				else {
					stack.push(template, blockName);
				}
			}
		}

		HtmlTemplate block = new HtmlTemplate();
		matcher.appendTail(block.value);
		block.value.trimToSize();
		stack.add(block);

		String blockName = stack.name();
		if (stack.pop() != this && !stack.empty()) {
			throw new ParseException("unclosed block: " + blockName, 0);
		}
	}

	// set a variable
	private void set(String key, String value, boolean recursive) {
		if (this.variables.containsKey(key)) {
			for (HtmlTemplate var : this.variables.get(key)) {
				var.value.setLength(0);
				var.visible = true;
				if (value == null) {
					var.value.append(var.defValue);
				}
				else {
					var.value.append(value);
				}
			}
		}
		if (recursive) {
			for (HtmlTemplate vars : this.blocks) {
				vars.set(key, value, true);
			}
		}
	}

	// reset a variable to its default value
	public void reset(String key) {
		this.set(key, null, false);
	}

	// set the value of a variable
	public void set(String key, String value) {
		this.set(key, value, false);
	}

	// set the value of every variable in the document.
	public void setAll(String key, String value) {
		this.set(key, value, true);
	}

	public void set(String key, boolean visible) {
		List<HtmlTemplate> result = this.variables.get(key);
		if (result != null) {
			for (HtmlTemplate template : result) {
				template.value.setLength(0);
				template.visible = visible;
				if (visible) {
					template.value.append(template.defValue);
				}
			}
		}
	}

	// add a block
	public HtmlTemplate add(String key) {
		List<HtmlTemplate> blocks = this.variables.get(key);
		if (blocks == null || blocks.size() != 1) {
			return null;
		}

		HtmlTemplate template = blocks.get(0);
		// flush previous values.
		try {
			// StringBuffer should not throw IOException.
			template.append(template.value);
			template.visible = true;
			template.reset();
		}
		catch (IOException ignore) {}
		return template;
	}

	public void reset() {
		Collection<List<HtmlTemplate>> variables = this.variables.values();
		for (List<HtmlTemplate> blocks : variables) {
			for (HtmlTemplate block : blocks) {
				block.value.setLength(0);
				if (block.defValue != null) {
					block.visible = true;
					block.value.append(block.defValue);
				}
				else {
					block.visible = false;
				}
				block.reset();
			}
		}
	}

	public Appendable append(Appendable out) throws IOException {
		if (!debugMode && !this.visible) {
			// hidden block
			return out;
		}

		if (out != this.value) {
			out.append(this.value);
		}
		if (this.blocks != null) {
			for (HtmlTemplate blocks : this.blocks) {
				blocks.append(out);
			}
		}
		return out;
	}

	@Override
	public String toString() {
		StringWriter result = new StringWriter();
		try {
			this.append(result);
		}
		catch (IOException ignore) {}
		return result.toString();
	}
}
