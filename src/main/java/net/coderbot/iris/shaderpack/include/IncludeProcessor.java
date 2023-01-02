package net.coderbot.iris.shaderpack.include;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import net.coderbot.iris.Iris;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Stack;

// TODO: Write tests for this code
public class IncludeProcessor {
	private final IncludeGraph graph;
	private final Map<AbsolutePackPath, ImmutableList<String>> cache;

	public IncludeProcessor(IncludeGraph graph) {
		this.graph = graph;
		this.cache = new HashMap<>();
	}

	// TODO: Actual error handling

	private int currentFile = 0;
	private int maxNumber = 0;
	private Stack<Integer> fileNumbers = new Stack<>();
	private String mainfile = "";

	public ImmutableList<String> getIncludedFile(AbsolutePackPath path) {
		if (currentFile>0) {
			if (currentFile == 1) {
				Iris.logger.info("include files for: {}", mainfile);
			}
			Iris.logger.info("File {}: {}", currentFile, path.getPathString());
		}
		else {
			mainfile = path.getPathString();
			fileNumbers.clear();
			maxNumber = 0;
		}
		ImmutableList<String> lines = cache.get(path);

		if (lines == null) {
			lines = process(path);
			cache.put(path, lines);
		}

		return lines;
	}

	private ImmutableList<String> process(AbsolutePackPath path) {
		FileNode fileNode = graph.getNodes().get(path);

		if (fileNode == null) {
			return null;
		}

		ImmutableList.Builder<String> builder = ImmutableList.builder();

		ImmutableList<String> lines = fileNode.getLines();
		ImmutableMap<Integer, AbsolutePackPath> includes = fileNode.getIncludes();

		for (int i = 0; i < lines.size(); i++) {
			AbsolutePackPath include = includes.get(i);

			if (include != null) {
				// TODO: Don't recurse like this, and check for cycles
				// TODO: Better diagnostics
				fileNumbers.push(currentFile);
				currentFile = ++maxNumber;
				builder.addAll(Objects.requireNonNull(getIncludedFile(include)));
				currentFile = fileNumbers.pop();
				builder.add("vec2 glsltransformer_line_" + i + "_" + currentFile+";");
			} else {
				builder.add("vec2 glsltransformer_line_" + i + "_" + currentFile+";");
				builder.add(lines.get(i));
			}
		}

		return builder.build();
	}
}
